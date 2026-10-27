package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Swarm OS step 4 (docs/Swarm-OS.md): the district, all the way out. Every workflow is a
 * neighbourhood of one tissue, read from its definition and its latest run.
 */
internal data class Neighbourhood(
    val definitionId: String,
    val name: String,
    val creatures: Int,
    /** Its most recent run, or null if it never ran. */
    val latestRunId: String?,
    val state: NeighbourhoodState,
    /** Completed tasks over all tasks in the latest run, 0..1. */
    val growth: Float,
    /** Tasks in the latest run waiting on a person (approval or escalation): the creature at the surface. */
    val needsYou: Int,
    /** Runs that finished, cleared or failed: the scars. */
    val scars: Int,
    /** Up to three of its creatures, by role, to draw inside it. */
    val creatureRoles: List<String> = emptyList(),
)

internal enum class NeighbourhoodState { Idle, Gestating, Hatched, Withered }

internal fun projectDistrict(
    definitions: List<WorkflowDefinition>,
    runs: List<WorkflowRun>,
): List<Neighbourhood> {
    val runsByDefinition = runs.groupBy { it.workflowDefinitionId.value }
    return definitions.map { definition ->
        val own = runsByDefinition[definition.id.value].orEmpty()
        val latest = own.maxByOrNull { it.updatedAtEpochMillis }
        val total = definition.tasks.size.coerceAtLeast(1)
        Neighbourhood(
            definitionId = definition.id.value,
            name = definition.name,
            creatures = definition.tasks.size,
            latestRunId = latest?.id?.value,
            state = when (latest?.status) {
                null, WorkflowRunStatus.Created -> NeighbourhoodState.Idle
                WorkflowRunStatus.Completed -> NeighbourhoodState.Hatched
                WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> NeighbourhoodState.Withered
                WorkflowRunStatus.Running, WorkflowRunStatus.AwaitingHuman -> NeighbourhoodState.Gestating
            },
            growth = (latest?.taskRuns?.values?.count { it.status == TaskRunStatus.Completed } ?: 0).toFloat() / total,
            needsYou = latest?.taskRuns?.values?.count {
                it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
            } ?: 0,
            scars = own.count { it.status in setOf(WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled) },
            creatureRoles = definition.tasks.mapNotNull { it.roleId?.value }.distinct().take(3)
                .map { id -> id.split('-').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) } },
        )
    }
}
