package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.events.ProviderUsageRecorded

/*
 * The reviewer as pollinator (docs/Node-Creature-Genome.md, "The reviewer pollinates"). A reviewer visits the work
 * it reviews and carries the producers' genes forward: its nth review in a workflow is born to the reviewer and to
 * every distinct producer it has reviewed so far (reviews 1 to n, across runs). Everything here is read from the
 * runs' own records; no review is inferred.
 */

/** Reviewers whose reviews pollinate: the reviewer kinds except the Antagonist, which is not the keeper's reviewer. */
private fun pollinates(role: RoleDefinition): Boolean =
    role.executionSource == RoleExecutionSource.Agent && isReviewerRoleName(role.name) &&
        classifyNodeCreatureRole(role.name) != NodeCreatureRoleKind.Antagonist

/** One attributable review: [reviewer]'s role reviewed [producer]'s role's work, whatever the verdict. */
internal data class PollenVisit(
    val reviewer: RoleDefinitionId,
    val producer: RoleDefinitionId,
    val atEpochMillis: Long,
    val consumerTask: String,
)

/**
 * The run's attributable reviews, in the order the review sessions first received the producer's work. Only a
 * single-source review with a stated, attributable verdict ([reportedReviewVerdict]) counts, so a multi-source or
 * unverdicted review is not a visit. Replayed work and stale sessions are excluded by the delivery evidence.
 */
internal fun pollenVisits(run: WorkflowRun, reports: List<ProviderUsageRecorded>): List<PollenVisit> {
    val definition = run.definitionSnapshot ?: return emptyList()
    val roles = run.roleSnapshot.associateBy { it.id }
    if (roles.isEmpty()) return emptyList()
    val deliveries = reportedArtifactDeliveries(run, reports)
    val tasks = definition.tasks.associateBy { it.id }
    val visits = mutableListOf<PollenVisit>()
    for (consumer in definition.tasks) {
        val target = run.taskRuns[consumer.id] ?: continue
        if (!(target.executor ?: consumer.effectiveExecutor()).isAgent) continue
        val reviewer = target.assignedRoleId?.let(roles::get)?.takeIf(::pollinates) ?: continue
        val sourceId = consumer.dependsOn.singleOrNull() ?: continue
        val source = run.taskRuns[sourceId] ?: continue
        val sourceDef = tasks[sourceId] ?: continue
        if (!(source.executor ?: sourceDef.effectiveExecutor()).isAgent) continue
        val producer = source.assignedRoleId?.let(roles::get)?.takeIf { it.executionSource == RoleExecutionSource.Agent } ?: continue
        reportedReviewVerdict(run, sourceId, consumer, deliveries) ?: continue
        val artifacts = source.artifacts.filter { it.taskRunId == source.id }.map { it.id.value }.toSet()
        val time = reports.asSequence().filter { event ->
            event.workflowRunId == run.id && event.taskDefinitionId == consumer.id &&
                event.providerId == target.assignedProviderId && event.providerRunId == target.providerRunId &&
                event.tokensAvoided == 0L && event.deliveredArtifactIds.orEmpty().any { it in artifacts }
        }.minOfOrNull { it.occurredAtEpochMillis } ?: continue
        visits += PollenVisit(reviewer.id, producer.id, time, consumer.id.value)
    }
    return visits.sortedWith(compareBy({ it.atEpochMillis }, { it.consumerTask }))
}

/** The nth review a reviewer performed in a workflow, and who its child is born to. */
internal data class Pollination(
    val workflow: WorkflowDefinitionId,
    val reviewer: RoleDefinitionId,
    /** 1 for the reviewer's first review in the workflow. */
    val index: Int,
    /** The reviewer first, then the distinct producers it has reviewed through review [index], oldest first. */
    val parents: List<RoleDefinitionId>,
    val sourceRun: WorkflowRunId,
) {
    val childId: RoleDefinitionId get() = RoleDefinitionId("pollen-${workflow.value}-${reviewer.value}-$index")
}

/** Most parents a child carries: the reviewer and the seven most recently reviewed distinct producers. */
internal const val MAX_POLLEN_PARENTS = 8

/**
 * Per-workflow tally of what each reviewer has reviewed, across the workflow's settled runs. Feed it only runs of
 * one workflow, oldest first. Replaying a run is a no-op, so a rebuilt history gives the same pollinations.
 */
internal data class PollenHistory(
    val workflow: WorkflowDefinitionId,
    private val reviewed: Map<RoleDefinitionId, List<RoleDefinitionId>> = emptyMap(),
    val pollinations: List<Pollination> = emptyList(),
    private val seen: Set<WorkflowRunId> = emptySet(),
) {
    fun advance(run: WorkflowRun, reports: List<ProviderUsageRecorded>): PollenHistory {
        if (run.id in seen || run.workflowDefinitionId != workflow) return this
        val tally = reviewed.toMutableMap()
        val born = pollinations.toMutableList()
        for (visit in pollenVisits(run, reports)) {
            val list = tally[visit.reviewer].orEmpty() + visit.producer
            tally[visit.reviewer] = list
            // Newest last; a producer reviewed again moves to its newest position, and the reviewer is never its own pollen.
            val producers = list.filter { it != visit.reviewer }.asReversed().distinct().asReversed()
            val parents = listOf(visit.reviewer) + producers.takeLast(MAX_POLLEN_PARENTS - 1)
            born += Pollination(workflow, visit.reviewer, list.size, parents, run.id)
        }
        return PollenHistory(workflow, tally, born, seen + run.id)
    }
}

/**
 * The child of one pollination, born unadopted (disabled) like every hatch, or null when its parents cannot be
 * joined: a parent missing from [roles], not an agent, disjoint tool ceilings, or one surface alias bound two ways.
 * A deterministic draw per child decides whose line it follows: half follow the reviewer, so the reviewer lineage
 * can itself improve, and the rest follow one of the producers. The child takes its lead's name, authorities and
 * keeper instructions; the other parents add instructions, capabilities and surfaces. It never gains an authority.
 */
internal fun pollinatedChild(pollination: Pollination, roles: Map<RoleDefinitionId, RoleDefinition>): RoleDefinition? {
    val parents = pollination.parents.map { roles[it] ?: return null }
    if (parents.any { it.executionSource != RoleExecutionSource.Agent }) return null
    val reviewer = parents.first()
    val producers = parents.drop(1)
    val id = pollination.childId
    val draw = "${id.value}:lead-v1".hashCode().toUInt()
    val lead = if (producers.isEmpty() || draw % 2u == 0u) reviewer
        else producers[(("${id.value}:producer-v1").hashCode().toUInt() % producers.size.toUInt()).toInt()]
    var ceiling: List<String>? = null
    for (parent in parents) {
        if (parent.toolCeiling.isEmpty()) continue
        ceiling = ceiling?.filter { it in parent.toolCeiling } ?: parent.toolCeiling
        // Empty means no ceiling, and an empty intersection would widen disjoint restrictions.
        if (ceiling.isEmpty()) return null
    }
    val surfaces = parents.flatMap { it.surfaces }
    if (surfaces.groupBy { it.alias }.values.any { it.distinct().size > 1 }) return null
    val caps = parents.mapNotNull { it.effectiveTokenBudget() }.sorted()
    val cap = when {
        caps.isEmpty() -> null
        caps.size % 2 == 1 -> caps[caps.size / 2]
        else -> medianParentalCap(caps[caps.size / 2 - 1], caps[caps.size / 2])
    }
    val ordered = listOf(lead) + parents.filter { it.id != lead.id }
    return lead.copy(
        id = id,
        name = "${lead.name} · pollen ${pollination.index}",
        instructions = ordered.map { it.instructions }.filter(String::isNotBlank).distinct().joinToString("\n\n"),
        toolCeiling = ceiling.orEmpty(),
        tokenBudget = null,
        budgetClass = null,
        geneticTokenCap = cap,
        preferredProviderId = lead.preferredProviderId ?: ordered.firstNotNullOfOrNull { it.preferredProviderId },
        capabilitiesRequired = parents.flatMapTo(linkedSetOf()) { it.capabilitiesRequired },
        surfaces = surfaces.distinctBy { it.alias },
        enabled = false,
        parents = pollination.parents,
        hatchedFromRunId = pollination.sourceRun.value,
    )
}
