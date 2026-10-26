package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 1 (docs/Swarm-OS.md): the workflow field drawn as neural tissue.
 *
 * Everything here is read from the run, never invented (docs/Ecology-and-the-Swarm.md, the binding
 * rule): each provider a task was assigned to is a capillary, the run itself is an egg that grows as
 * tasks complete, and the tails feeding it come from the tasks being worked on right now.
 */
internal data class TissueScene(
    val capillaries: List<TissueCapillary>,
    /** Task id → the provider id it was assigned to; tasks never assigned have no entry. */
    val providerOf: Map<String, String>,
    /** Null when nothing has run yet (a resting or build-mode workflow has no egg). */
    val egg: TissueEgg?,
    /** Tethers (from, to) carrying a knot other than on-success; drawn dashed. */
    val conditional: Set<Pair<String, String>> = emptySet(),
    /** Creatures waiting on a person (approval or escalation): they rise to the surface. */
    val atSurface: Set<String> = emptySet(),
    /** Each creature's Echo state, from the needs (docs/Swarm-OS.md); grounded creatures are left out. */
    val echo: Map<String, EchoState> = emptyMap(),
    /** Creatures whose output is held from consumers: quarantined, ringed in white. */
    val held: Set<String> = emptySet(),
)

/** A provider, drawn as a band across the field in normalised coordinates. */
internal data class TissueCapillary(
    val providerId: String,
    val start: H2g2TerrariumPosition,
    val end: H2g2TerrariumPosition,
)

internal data class TissueEgg(
    val position: H2g2TerrariumPosition,
    /** Completed tasks over all tasks, 0..1. */
    val growth: Float,
    /** Tasks being worked on now; each feeds the egg with its tail. */
    val feederIds: List<String>,
    val outcome: TissueEggOutcome,
)

internal enum class TissueEggOutcome { Gestating, Hatched, Withered }

private val FEEDING = setOf(
    TaskRunStatus.Planning,
    TaskRunStatus.Running,
    TaskRunStatus.Verifying,
    TaskRunStatus.Retrying,
)

internal fun projectTissue(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    positions: Map<String, H2g2TerrariumPosition>,
    roles: Collection<com.hereliesaz.geministrator.domain.RoleDefinition> = emptyList(),
): TissueScene {
    val taskIds = definition.tasks.map { it.id.value }
    val providerOf = run.taskRuns.entries
        .mapNotNull { (id, taskRun) -> taskRun.assignedProviderId?.let { id.value to it.value } }
        .toMap()
    val providers = providerOf.values.distinct().sorted()
    // Bands sit at even heights with a slight alternating tilt, so neighbouring providers never merge.
    val capillaries = providers.mapIndexed { index, providerId ->
        val y = (index + 1f) / (providers.size + 1f)
        val tilt = if (index % 2 == 0) 0.06f else -0.06f
        TissueCapillary(
            providerId = providerId,
            start = H2g2TerrariumPosition(-0.05f, y - tilt),
            end = H2g2TerrariumPosition(1.05f, y + tilt),
        )
    }

    val started = run.status != WorkflowRunStatus.Created ||
        run.taskRuns.values.any { it.status != TaskRunStatus.Created }
    val egg = if (!started || taskIds.isEmpty()) {
        null
    } else {
        val statusOf = definition.tasks.associate { it.id.value to run.taskRuns[it.id]?.status }
        val completed = taskIds.count { statusOf[it] == TaskRunStatus.Completed }
        val feeders = taskIds.filter { statusOf[it] in FEEDING }
        val anchors = (feeders.ifEmpty { taskIds }).mapNotNull(positions::get)
        val centre = if (anchors.isEmpty()) {
            H2g2TerrariumPosition(0.5f, 0.5f)
        } else {
            H2g2TerrariumPosition(
                x = anchors.map { it.x }.average().toFloat(),
                y = (anchors.map { it.y }.average().toFloat() + 0.08f).coerceAtMost(0.95f),
            )
        }
        TissueEgg(
            position = centre,
            growth = completed.toFloat() / taskIds.size,
            feederIds = feeders,
            outcome = when (run.status) {
                WorkflowRunStatus.Completed -> TissueEggOutcome.Hatched
                WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> TissueEggOutcome.Withered
                else -> TissueEggOutcome.Gestating
            },
        )
    }
    val conditional = definition.tasks.mapNotNullTo(mutableSetOf()) { task ->
        when (val condition = task.condition) {
            is TaskCondition.OnAnyOutcome -> condition.ofTask.value to task.id.value
            is TaskCondition.OnFailure -> condition.ofTask.value to task.id.value
            TaskCondition.Always -> null
        }
    }
    val atSurface = run.taskRuns.entries
        .filter { (_, taskRun) -> taskRun.status == TaskRunStatus.AwaitingApproval || taskRun.status == TaskRunStatus.Escalated }
        .mapTo(mutableSetOf()) { (id, _) -> id.value }
    return TissueScene(
        capillaries = capillaries,
        providerOf = providerOf,
        egg = egg,
        conditional = conditional,
        atSurface = atSurface,
        held = definition.tasks.filter { it.holdOutput }.mapTo(mutableSetOf()) { it.id.value },
        echo = definition.tasks.mapNotNull { task ->
            creatureNeeds(task.id, definition, run, roles)
                ?.echoState
                ?.takeIf { it != EchoState.Grounded }
                ?.let { task.id.value to it }
        }.toMap(),
    )
}
