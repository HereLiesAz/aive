package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 6 (docs/Swarm-OS.md, docs/Node-Creature-Genome.md "Birth"): a run that clears
 * hatches one new config from the agents that did its work. With one contributor it is a clone;
 * with more, it recombines the two that did the most. The child is born unadopted (disabled), with
 * its parents and the run recorded; the person adopts it from the lineage. Nothing is invented: the
 * child is made from its parents' configs, with bounded instruction-locus mutation at birth.
 */
internal fun hatchFromRun(
    run: WorkflowRun, roles: Collection<RoleDefinition>, echo: Map<RoleDefinitionId, Int> = emptyMap(),
    reports: List<ProviderUsageRecorded> = emptyList(),
    configuredModels: Map<AgentProviderId, String> = emptyMap(),
): RoleDefinition? {
    if (run.status != WorkflowRunStatus.Completed) return null
    val rolesById = (run.roleSnapshot.ifEmpty { roles.toList() }).associateBy(RoleDefinition::id)
    // Contributors, most completed tasks first; ties by id so the same run always hatches the same child.
    val contributors = run.taskRuns.values
        .filter { it.status == TaskRunStatus.Completed && it.byAgent }
        .mapNotNull { it.assignedRoleId }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<RoleDefinitionId, Int>> { it.value }.thenBy { it.key.value })
        .mapNotNull { rolesById[it.key] }
        // Programmatic roles (a script or an action) only connect; they are never a parent.
        .filter { it.executionSource == com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
    val a = contributors.firstOrNull() ?: return null
    val childId = RoleDefinitionId("hatch-${run.id.value}")
    val b = contributors.getOrNull(1)
        ?: return mutateHatchedGenome(run, a.copy(
            id = childId,
            name = "${a.name} · clone",
            enabled = false,
            parents = listOf(a.id),
            hatchedFromRunId = run.id.value,
        ), echo, reports, configuredModels)
    return mutateHatchedGenome(run, a.copy(
        id = childId,
        name = "${a.name} × ${b.name}",
        instructions = listOf(a.instructions, b.instructions).filter(String::isNotBlank).joinToString("\n\n"),
        preferredProviderId = b.preferredProviderId ?: a.preferredProviderId,
        capabilitiesRequired = a.capabilitiesRequired + b.capabilitiesRequired,
        surfaces = (a.surfaces + b.surfaces).distinctBy { it.alias },
        enabled = false,
        parents = listOf(a.id, b.id),
        hatchedFromRunId = run.id.value,
    ), echo, reports, configuredModels)
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
