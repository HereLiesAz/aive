package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Rehearse (docs/Swarm-OS.md): the workflow played out with no provider called. Each frame is a
 * run: every task whose feeders have finished starts, then finishes on the next frame, so the
 * creatures act out the order and the egg grows and hatches. Conditional tasks rehearse the
 * success path: an only-on-failure task is skipped (cancelled). Nothing here is persisted.
 */
internal fun rehearsalFrames(definition: WorkflowDefinition, base: WorkflowRun): List<WorkflowRun> {
    val status = definition.tasks.associate { it.id to TaskRunStatus.Created }.toMutableMap()
    val frames = mutableListOf<WorkflowRun>()
    fun snapshot(runStatus: WorkflowRunStatus) = base.copy(
        id = com.hereliesaz.geministrator.domain.WorkflowRunId("rehearsal--${definition.id.value}"),
        status = runStatus,
        taskRuns = status.mapValues { (id, s) -> TaskRun(TaskRunId("rehearsal-${id.value}"), id, s, assignedRoleId = definition.tasks.first { it.id == id }.roleId) },
    )
    val done = setOf(TaskRunStatus.Completed, TaskRunStatus.Cancelled)
    while (status.values.any { it !in done }) {
        // finish what was running
        status.filterValues { it == TaskRunStatus.Running }.keys.forEach { status[it] = TaskRunStatus.Completed }
        // start everything whose feeders have finished
        val ready = definition.tasks.filter { task ->
            status[task.id] == TaskRunStatus.Created && task.dependsOn.all { status[it] in done }
        }
        if (ready.isEmpty() && status.values.none { it == TaskRunStatus.Running }) {
            // whatever is left can never start (it waits on a skipped path)
            status.filterValues { it == TaskRunStatus.Created }.keys.forEach { status[it] = TaskRunStatus.Cancelled }
        }
        ready.forEach { task ->
            status[task.id] = if (task.condition is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure) {
                TaskRunStatus.Cancelled
            } else {
                TaskRunStatus.Running
            }
        }
        frames += snapshot(WorkflowRunStatus.Running)
    }
    frames += snapshot(WorkflowRunStatus.Completed)
    return frames
}

