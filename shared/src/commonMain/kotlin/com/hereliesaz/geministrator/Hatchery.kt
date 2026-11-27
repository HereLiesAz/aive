package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.domain.requiresToolFilter
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.ProviderSelectionRequest
import com.hereliesaz.geministrator.workflow.providerCapabilitiesFor
import com.hereliesaz.geministrator.workflow.providerRepositoryFor
import com.hereliesaz.geministrator.workflow.usesHostFileContext
import com.hereliesaz.geministrator.providers.HabitReplayAgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import kotlinx.coroutines.CancellationException
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 6 (docs/Swarm-OS.md, docs/Node-Creature-Genome.md "Birth"): a run that clears
 * hatches one new config from the agents that did its work. With one contributor it is a clone;
 * with more, it selects the most closely related viable pair of completed contributors. The child is born unadopted (disabled), with
 * its parents and the run recorded; the person adopts it from the lineage. Nothing is invented: the
 * child is made from its parents' configs, with bounded instruction, tool and model-source mutation at birth.
 */
internal fun hatchFromRun(
    run: WorkflowRun, roles: Collection<RoleDefinition>, echo: Map<RoleDefinitionId, Int> = emptyMap(),
    reports: List<ProviderUsageRecorded> = emptyList(),
    configuredModels: Map<AgentProviderId, String> = emptyMap(),
    genera: Map<RoleDefinitionId, String> = emptyMap(),
    extraParents: Boolean = true,
): RoleDefinition? {
    if (run.status != WorkflowRunStatus.Completed) return null
    val contributors = hatchContributors(run, roles)
    val first = contributors.firstOrNull() ?: return null
    val childId = RoleDefinitionId("hatch-${run.id.value}")
    if (contributors.size == 1) return mutateHatchedGenome(run, first.copy(
        id = childId,
        name = "${first.name} · clone",
        enabled = false,
        parents = listOf(first.id),
        // Freeze the parent's effective allowance at birth, independently of later keeper edits.
        budgetClass = null,
        tokenBudget = null,
        geneticTokenCap = first.effectiveTokenBudget(),
        hatchedFromRunId = run.id.value,
    ), echo, reports, configuredModels)
    // Fertility is not inferred from the currently edited roster or provider names.
    if (run.roleSnapshot.isEmpty()) return null
    val kinships = hatchKinships(run, contributors, reports)
    val comparable = kinships.keys.flatten().toSet()
    val knownGenera = comparable.mapNotNull(genera::get).filter(String::isNotBlank).toSet().size
    val pairs = contributors.flatMapIndexed { i, a ->
        contributors.drop(i + 1).mapNotNull { b ->
            val score = kinships[setOf(a.id, b.id)] ?: return@mapNotNull null
            val bridge = contributors.any { c -> c.id != a.id && c.id != b.id &&
                (kinships[setOf(a.id, c.id)] ?: -1) >= 75 &&
                (kinships[setOf(b.id, c.id)] ?: -1) >= 75 }
            if (!fertilityAllowsPair(score, bridge, comparable.size, knownGenera)) return@mapNotNull null
            // Empty means no ceiling; an empty intersection would widen two disjoint restrictions.
            if (a.toolCeiling.isNotEmpty() && b.toolCeiling.isNotEmpty() &&
                a.toolCeiling.none { it in b.toolCeiling }) return@mapNotNull null
            if ((a.surfaces + b.surfaces).groupBy { it.alias }.values.any { it.distinct().size > 1 }) return@mapNotNull null
            Triple(a, b, score)
        }
    }
    val (a, b) = pairs.maxByOrNull { it.third } ?: return null
    val ceiling = when {
        a.toolCeiling.isEmpty() -> b.toolCeiling
        b.toolCeiling.isEmpty() -> a.toolCeiling
        else -> a.toolCeiling.filter { it in b.toolCeiling }
    }
    val pairChild = a.copy(
        id = childId,
        name = "${a.name} × ${b.name}",
        instructions = listOf(a.instructions, b.instructions).filter(String::isNotBlank).distinct().joinToString("\n\n"),
        toolCeiling = ceiling,
        tokenBudget = null,
        geneticTokenCap = medianParentalCap(a.effectiveTokenBudget(), b.effectiveTokenBudget()),
        budgetClass = null,
        preferredProviderId = b.preferredProviderId ?: a.preferredProviderId,
        capabilitiesRequired = a.capabilitiesRequired + b.capabilitiesRequired,
        surfaces = (a.surfaces + b.surfaces).distinctBy { it.alias },
        enabled = false,
        parents = listOf(a.id, b.id),
        hatchedFromRunId = run.id.value,
    )
    val grown = if (extraParents) withExtraParents(run, pairChild, contributors.filter { it.id != a.id && it.id != b.id })
    else pairChild
    return mutateHatchedGenome(run, grown, echo, reports, configuredModels)
}

/** A child has at most this many parents, the same bound a pollinated child has. */
internal const val MAX_HATCH_PARENTS = 8

/**
 * The functional loci a contributor outside the chosen pair can give (docs/Node-Creature-Genome.md,
 * Birth: "each other contributor gives one part locus"). Each one only adds to the child, and none
 * touches its authorities or its tool ceiling, so an extra parent can never widen what the child may do.
 */
internal enum class ExtraLocus { Instructions, Capabilities, Surfaces, Source }

/**
 * Give each additional contributor one functional locus, in the order of work done. The locus is a
 * stable draw from the run and the contributor, so retrying a hatch gives the same child. A contributor
 * is a parent only if it actually changed the child: a locus that adds nothing, an Antagonist, and a
 * contributor whose completed work needs an authority the child lacks are passed over, and are not
 * recorded as parents.
 */
internal fun withExtraParents(run: WorkflowRun, child: RoleDefinition, extras: List<RoleDefinition>): RoleDefinition {
    val tasks = run.definitionSnapshot?.tasks?.associateBy { it.id }.orEmpty()
    var grown = child
    for (extra in extras) {
        if (grown.parents.size >= MAX_HATCH_PARENTS) break
        if (!heritable(extra)) continue
        val needed = run.taskRuns.values.filter { it.assignedRoleId == extra.id && it.status == TaskRunStatus.Completed && it.byAgent }
            .mapNotNull { tasks[it.taskDefinitionId]?.requiredRoleAuthority }
        if (needed.any { it !in grown.authorities }) continue
        val locus = ExtraLocus.entries[(("${run.id.value}:${extra.id.value}:extra-locus-v1").hashCode().toUInt() %
            ExtraLocus.entries.size.toUInt()).toInt()]
        val changed = when (locus) {
            ExtraLocus.Instructions -> extra.instructions.takeIf { it.isNotBlank() && it !in grown.instructions }?.let {
                grown.copy(instructions = listOf(grown.instructions, it).filter(String::isNotBlank).joinToString("\n\n"))
            }
            ExtraLocus.Capabilities -> (extra.capabilitiesRequired - grown.capabilitiesRequired).takeIf { it.isNotEmpty() }?.let {
                grown.copy(capabilitiesRequired = grown.capabilitiesRequired + it)
            }
            ExtraLocus.Surfaces -> {
                val merged = grown.surfaces + extra.surfaces
                // A conflicting alias cannot recombine, the same rule the pair obeys.
                if (extra.surfaces.isEmpty() || merged.groupBy { it.alias }.values.any { it.distinct().size > 1 }) null
                else merged.distinctBy { it.alias }.takeIf { it.size > grown.surfaces.size }?.let { grown.copy(surfaces = it) }
            }
            ExtraLocus.Source -> extra.preferredProviderId?.takeIf { grown.preferredProviderId == null }?.let {
                grown.copy(preferredProviderId = it)
            }
        } ?: continue
        grown = changed.copy(parents = grown.parents + extra.id)
    }
    return grown
}

/**
 * Median of two finite effective parent caps, rounded down to whole tokens without overflow.
 * An unlimited parent has no finite observation: use the other parent's cap, or null if neither
 * has one. The result is a birth default, not a keeper override or a mutation locus.
 */
internal fun medianParentalCap(a: Long?, b: Long?): Long? {
    if (a == null || b == null) return a ?: b
    val lower = minOf(a, b)
    val upper = maxOf(a, b)
    return lower + (upper - lower) / 2
}

/** Actual completed agent contributors, ordered by work count then ID; deterministic work is not a parent. */
internal fun hatchContributors(run: WorkflowRun, roles: Collection<RoleDefinition>): List<RoleDefinition> {
    val rolesById = (run.roleSnapshot.ifEmpty { roles.toList() }).associateBy(RoleDefinition::id)
    val tasks = run.definitionSnapshot?.tasks?.associateBy { it.id }.orEmpty()
    return run.taskRuns.values.filter { task ->
        task.status == TaskRunStatus.Completed && task.byAgent &&
            (tasks[task.taskDefinitionId]?.effectiveExecutor()?.let { task.executor ?: it }?.isAgent != false)
    }.mapNotNull { it.assignedRoleId }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<RoleDefinitionId, Int>> { it.value }.thenBy { it.key.value })
        .mapNotNull { rolesById[it.key] }.filter { it.executionSource == RoleExecutionSource.Agent }
}

/**
 * Pairwise configuration kinship from a role's unambiguous actual provider/model across ALL
 * completed agent sessions in this run. Missing or mixed sessions cannot establish a pair.
 */
internal fun hatchKinships(
    run: WorkflowRun, contributors: List<RoleDefinition>, reports: List<ProviderUsageRecorded>,
): Map<Set<RoleDefinitionId>, Int> {
    val models = reportedSessionModels(run, reports)
    val profiles = contributors.mapNotNull { role ->
        val sessions = run.taskRuns.values.filter { it.assignedRoleId == role.id &&
            it.status == TaskRunStatus.Completed && it.byAgent }
        val identities = sessions.map { task ->
            val provider = task.assignedProviderId?.value
            val model = models[task.taskDefinitionId]
            if (provider == null || model == null) null else provider to model
        }
        if (identities.isEmpty() || identities.any { it == null }) return@mapNotNull null
        identities.filterNotNull().distinct().singleOrNull()?.let { role.id to it }
    }.toMap()
    return contributors.flatMapIndexed { i, a ->
        contributors.drop(i + 1).mapNotNull { b ->
            val x = profiles[a.id] ?: return@mapNotNull null
            val y = profiles[b.id] ?: return@mapNotNull null
            configurationKinship(a, x.first, b, y.first, x.second, y.second)
                ?.let { setOf(a.id, b.id) to it }
        }
    }.toMap()
}

/** Documented viability thresholds; a bridge has at least 75 kinship to both parents. */
internal fun fertilityAllowsPair(score: Int, bridge: Boolean, contributors: Int, genera: Int): Boolean =
    when {
        score >= 75 -> true
        score >= 60 -> bridge || contributors >= 4
        else -> contributors >= 3 && genera >= 3
    }

/**
 * First functional mutation locus: change how the child approaches work, never what it may do.
 * The documented 2% base rises to 8% between Echo 30 and 60, capped there until higher rates
 * have a defined policy. Unknown parent balances supply no multiplier. The run-ID draw makes
 * retrying a hatch reproducible. Only the new disabled configuration is changed.
 */
private fun mutateHatchedInstructions(
    run: WorkflowRun, child: RoleDefinition, echo: Map<RoleDefinitionId, Int>,
): RoleDefinition {
    if (!mutationDraw(run, child, echo, "instruction")) return child
    val variants = listOf(
        "Within the assigned scope, compare plausible explanations against the available evidence before choosing an approach.",
        "Within the assigned scope, prefer a small falsifiable check of a material assumption before committing to an approach.",
        "When otherwise comparable approaches are available within the assigned scope, favor reversible choices and identify material trade-offs.",
    ).filterNot { it in child.instructions }
    if (variants.isEmpty()) return child
    val choice = (("${run.id.value}:instruction-variant-v1").hashCode().toUInt() % variants.size.toUInt()).toInt()
    return child.copy(
        name = if (child.parents.size == 1) child.name.removeSuffix(" · clone") + " · variant" else child.name,
        instructions = listOf(child.instructions, variants[choice]).filter(String::isNotBlank).joinToString("\n\n"),
    )
}

/** Each functional locus has its own stable, independently keyed draw. */
private fun mutationDraw(run: WorkflowRun, child: RoleDefinition, echo: Map<RoleDefinitionId, Int>, locus: String): Boolean {
    val highest = child.parents.mapNotNull(echo::get).maxOrNull() ?: 0
    val chance = 200 + (highest.coerceIn(3_000, 6_000) - 3_000) * 600 / 3_000
    return ("${run.id.value}:$locus-mutation-v1").hashCode().toUInt() % 10_000u < chance.toUInt()
}

/**
 * Persist actual instruction, tool and model-source changes. An empty ceiling means unrestricted,
 * so never remove its last member. A model-source variant is restricted to a solo parent's own
 * successful sessions on different currently configured models. No arbitrary model name or
 * capability is invented. The regular provider selection and approval checks still govern work.
 */
private fun mutateHatchedGenome(
    run: WorkflowRun, child: RoleDefinition, echo: Map<RoleDefinitionId, Int>,
    reports: List<ProviderUsageRecorded>, configuredModels: Map<AgentProviderId, String>,
): RoleDefinition {
    var variant = mutateHatchedInstructions(run, child, echo)
    val ceiling = child.toolCeiling.distinct().sorted()
    if (ceiling.size > 1 && mutationDraw(run, child, echo, "tool")) {
        val index = (("${run.id.value}:tool-variant-v1").hashCode().toUInt() % ceiling.size.toUInt()).toInt()
        variant = variant.copy(toolCeiling = ceiling.filterIndexed { i, _ -> i != index })
    }
    val parent = child.parents.singleOrNull()
    if (parent != null && mutationDraw(run, child, echo, "source")) {
        val models = reportedSessionModels(run, reports)
        val observed = run.taskRuns.values.filter { it.assignedRoleId == parent &&
            it.status == TaskRunStatus.Completed && it.byAgent }.mapNotNull { task ->
            val provider = task.assignedProviderId ?: return@mapNotNull null
            val model = models[task.taskDefinitionId] ?: return@mapNotNull null
            if (configuredModels[provider] == model) provider to model else null
        }
        val unambiguous = observed.groupBy({ it.first }, { it.second })
            .mapNotNull { (provider, values) -> values.distinct().singleOrNull()?.let { provider to it } }
            .toMap()
        val baseline = child.preferredProviderId ?: observed.groupingBy { it.first }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<AgentProviderId, Int>> { it.value }
                .thenBy { it.key.value }).firstOrNull()?.key
        val baselineModel = baseline?.let(unambiguous::get)
        if (baselineModel != null) {
            val alternatives = unambiguous.entries.filter { it.key != baseline && it.value != baselineModel }
                .sortedBy { it.key.value }
            if (alternatives.isNotEmpty()) {
                val index = (("${run.id.value}:source-variant-v1").hashCode().toUInt() % alternatives.size.toUInt()).toInt()
                variant = variant.copy(preferredProviderId = alternatives[index].key)
            }
        }
    }
    if (variant != child && child.parents.size == 1) {
        variant = variant.copy(name = child.name.removeSuffix(" · clone") + " · variant")
    }
    return variant
}

/**
 * Birth-time compatibility preflight using the actual completed parent task contracts and the
 * existing provider registry's resolved-provider validation. This neither dispatches work nor
 * reserves a provider slot or keyless request. It cannot certify arbitrary future tasks.
 * Unknown snapshots, unavailable providers, ambiguous surface aliases or missing task authority
 * fail closed; a child never receives extra authority merely to make a contract fit.
 */
internal suspend fun hatchCanExecute(
    run: WorkflowRun, child: RoleDefinition, registry: AgentProviderRegistry,
): Boolean {
    if (child.enabled || child.executionSource != RoleExecutionSource.Agent ||
        child.parents.isEmpty() || child.hatchedFromRunId != run.id.value ||
        run.status != WorkflowRunStatus.Completed) return false
    val definition = run.definitionSnapshot ?: return false
    if (child.surfaces.groupBy { it.alias }.values.any { it.distinct().size > 1 }) return false
    if (child.effectiveTokenBudget()?.let { it <= 0 } == true) return false
    val completed = run.taskRuns.values.filter {
        it.assignedRoleId in child.parents && it.status == TaskRunStatus.Completed && it.byAgent
    }
    if (completed.isEmpty() || completed.mapNotNull { it.assignedRoleId }.toSet() != child.parents.toSet()) return false
    val tasks = definition.tasks.associateBy { it.id }
    val checkedParents = mutableSetOf<RoleDefinitionId>()
    for (entry in completed) {
        val task = tasks[entry.taskDefinitionId] ?: return false
        if (!(entry.executor ?: task.effectiveExecutor()).isAgent) continue
        if (task.requiredRoleAuthority?.let { it !in child.authorities } == true) return false
        val requiredSource = (task.providerConstraints as? ProviderConstraints.RequireProvider)?.providerId
        val pinned = requiredSource ?: child.preferredProviderId
        val ids = if (pinned != null) listOf(pinned) else registry.linkedSourceIds().map(::AgentProviderId)
        var compatible = false
        for (id in ids) {
            val provider = registry.provider(id) ?: continue
            // A role-level preflight cannot certify the size/availability of an unknown future local prompt.
            if (provider is OnDeviceTextAgentProvider || id == HabitReplayAgentProvider.ID) continue
            val request = ProviderSelectionRequest(
                preferredProviderId = child.preferredProviderId,
                requiredCapabilities = task.providerCapabilitiesFor(child),
                constraints = task.providerConstraints,
                repository = task.providerRepositoryFor(run.repositorySnapshot),
                requiresToolFilter = task.usesHostFileContext || task.contract?.requiresToolFilter() == true ||
                    child.toolCeiling.isNotEmpty(),
                resolvedProviderId = id,
            )
            try {
                if (registry.select(request).id == id) {
                    compatible = true
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No compatible provider established by this probe.
            }
        }
        if (!compatible) return false
        entry.assignedRoleId?.let(checkedParents::add)
    }
    return checkedParents == child.parents.toSet()
}
