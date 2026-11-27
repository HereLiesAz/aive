package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.memory.MemorySnapshot
import kotlin.math.max

/*
 * Echo beyond reads (docs/Node-Creature-Genome.md, "Echo: feedback from the self"): memory it wrote itself, a
 * criticism that came back to it, a fevered creature ignoring criticism, and a septic producer's contagion. Each is
 * read from the run's own records and fails closed: a missing report, an unknown kinship or an unattributed memory
 * is no step. These are steps of [EchoHistory.advance]; nothing here reads the GPU or the live UI.
 */

internal enum class EchoStepKind { Read, Memory, Criticism }

/** One change to a role's Echo, in hundredths, ordered by [time] then [a], [b], kind. [source] is the sending role of a read. */
internal data class EchoStep(
    val time: Long, val a: String, val b: String, val role: RoleDefinitionId,
    val delta: Int, val kind: EchoStepKind, val source: RoleDefinitionId?,
)

/** Fevered: it stops responding to criticism (60 and up, in hundredths). */
internal const val ECHO_FEVERED = 6_000

/** Insane, and so septic: its output reaches its readers uncontested (85 and up). */
internal const val ECHO_SEPTIC = 8_500

/** A reader of a septic producer's work takes +3 Echo (the documented rate), on top of what the read itself rates. */
internal const val ECHO_SEPTIC_CONTAGION = 300

/**
 * Reading memory its own role wrote is feedback from the self, at the rate of reading its own output (+0.6). One step
 * per task however many such nodes were carried, so a long memory does not swamp a run (provisional). Only memory
 * written by its own role alone counts: authors' provider and model are not recorded, so a near-clone or a stranger
 * cannot be told from the memory layer, and that exposure stays unreported. [memoryAuthors] maps `memory-node:<id>`
 * to the roles that wrote it, read from the memory layer's episodes.
 */
internal fun memoryEchoSteps(
    run: WorkflowRun, reports: List<ProviderUsageRecorded>, roles: Map<RoleDefinitionId, RoleDefinition>,
    memoryAuthors: Map<String, Set<RoleDefinitionId>>,
): List<EchoStep> {
    if (memoryAuthors.isEmpty()) return emptyList()
    val definition = run.definitionSnapshot ?: return emptyList()
    val tasks = definition.tasks.associateBy { it.id }
    return reportedMemoryReads(run, reports).mapNotNull { (taskId, nodes) ->
        val target = run.taskRuns[taskId] ?: return@mapNotNull null
        val task = tasks[taskId] ?: return@mapNotNull null
        if (!(target.executor ?: task.effectiveExecutor()).isAgent) return@mapNotNull null
        val own = target.assignedRoleId?.let(roles::get)?.takeIf { it.executionSource == RoleExecutionSource.Agent && !isAntagonist(it) }
            ?: return@mapNotNull null
        if (nodes.none { memoryAuthors[it] == setOf(own.id) }) return@mapNotNull null
        val time = reports.asSequence().filter { event ->
            event.workflowRunId == run.id && event.taskDefinitionId == taskId &&
                event.providerId == target.assignedProviderId && event.providerRunId == target.providerRunId &&
                event.tokensAvoided == 0L && event.deliveredMemoryAddresses != null
        }.minOfOrNull { it.occurredAtEpochMillis } ?: return@mapNotNull null
        EchoStep(time, taskId.value, "memory", own.id, 60, EchoStepKind.Memory, null)
    }
}

/**
 * A review that turned work back (Revise or Reject), reaching the producer: the producer's current session received
 * the reviewer's artifact, so the criticism was actually returned. It lowers the producer's Echo by
 * 15 x (90 - kinship) hundredths (the documented -15 x (0.9 - kinship)), nothing for a near-clone reviewer.
 * A verdict nobody returned to the producer is not criticism it heard.
 */
internal fun criticismEchoSteps(
    run: WorkflowRun, reports: List<ProviderUsageRecorded>, roles: Map<RoleDefinitionId, RoleDefinition>,
    models: Map<TaskDefinitionId, String>,
): List<EchoStep> {
    val definition = run.definitionSnapshot ?: return emptyList()
    val tasks = definition.tasks.associateBy { it.id }
    val deliveries = reportedArtifactDeliveries(run, reports)
    return definition.tasks.mapNotNull { review ->
        val reviewRun = run.taskRuns[review.id] ?: return@mapNotNull null
        if (!(reviewRun.executor ?: review.effectiveExecutor()).isAgent) return@mapNotNull null
        val reviewer = reviewRun.assignedRoleId?.let(roles::get) ?: return@mapNotNull null
        if (!isReviewerRoleName(reviewer.name)) return@mapNotNull null
        val producerId = review.dependsOn.singleOrNull() ?: return@mapNotNull null
        val producerRun = run.taskRuns[producerId] ?: return@mapNotNull null
        val producerDef = tasks[producerId] ?: return@mapNotNull null
        if (!(producerRun.executor ?: producerDef.effectiveExecutor()).isAgent) return@mapNotNull null
        val producer = producerRun.assignedRoleId?.let(roles::get)
            ?.takeIf { it.executionSource == RoleExecutionSource.Agent && !isAntagonist(it) } ?: return@mapNotNull null
        reportedReviewVerdict(run, producerId, review, deliveries)?.takeIf { it.blocks } ?: return@mapNotNull null
        val criticism = reviewRun.artifacts.filter { it.taskRunId == reviewRun.id }.map { it.id.value }.toSet()
        val time = reports.asSequence().filter { event ->
            event.workflowRunId == run.id && event.taskDefinitionId == producerId &&
                event.providerId == producerRun.assignedProviderId && event.providerRunId == producerRun.providerRunId &&
                event.tokensAvoided == 0L && event.deliveredArtifactIds.orEmpty().any { it in criticism }
        }.minOfOrNull { it.occurredAtEpochMillis } ?: return@mapNotNull null
        val kinship = configurationKinship(producer, producerRun.assignedProviderId?.value, reviewer,
            reviewRun.assignedProviderId?.value, models[producerId], models[review.id]) ?: return@mapNotNull null
        val delta = -15 * max(0, 90 - kinship)
        if (delta == 0) return@mapNotNull null
        EchoStep(time, producerId.value, review.id.value, producer.id, delta, EchoStepKind.Criticism, null)
    }
}

/**
 * `memory-node:<id>` to the roles that wrote it, from the memory layer's episodes (an episode records its role). A node
 * with no source episode, or any source episode that names no role (a keeper's own memory, say), is left out: unknown
 * authorship is not "written by itself".
 */
internal fun memoryAuthorsOf(snapshot: MemorySnapshot): Map<String, Set<RoleDefinitionId>> {
    val roleOf = snapshot.episodes.associate { it.id to it.roleId?.takeIf(String::isNotBlank) }
    return snapshot.nodes.mapNotNull { node ->
        val roles = node.sourceEpisodeIds.map { roleOf[it] }
        if (roles.isEmpty() || roles.any { it == null }) null
        else "memory-node:${node.id.value}" to roles.filterNotNull().mapTo(linkedSetOf(), ::RoleDefinitionId)
    }.toMap()
}
