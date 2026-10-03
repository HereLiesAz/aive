package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskRunStatus

object TaskRunTransitions {
    private val allowed: Map<TaskRunStatus, Set<TaskRunStatus>> = mapOf(
        TaskRunStatus.Created to setOf(
            TaskRunStatus.Blocked,
            TaskRunStatus.Ready,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Blocked to setOf(
            TaskRunStatus.Ready,
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Ready to setOf(
            TaskRunStatus.Blocked,
            TaskRunStatus.Planning,
            TaskRunStatus.AwaitingApproval,
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Planning to setOf(
            TaskRunStatus.AwaitingApproval,
            TaskRunStatus.Running,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.AwaitingApproval to setOf(
            // An approved engine-drafted plan: no run exists yet, so the task becomes dispatchable.
            TaskRunStatus.Ready,
            TaskRunStatus.Running,
            TaskRunStatus.Failed,
            TaskRunStatus.Retrying,
            TaskRunStatus.Escalated,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Running to setOf(
            TaskRunStatus.Blocked,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Retrying,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Verifying to setOf(
            TaskRunStatus.Blocked,
            TaskRunStatus.Completed,
            TaskRunStatus.Retrying,
            TaskRunStatus.Failed,
            TaskRunStatus.Escalated,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Retrying to setOf(
            TaskRunStatus.Blocked,
            TaskRunStatus.Planning,
            TaskRunStatus.AwaitingApproval,
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Escalated,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Completed to emptySet(),
        TaskRunStatus.Failed to setOf(
            TaskRunStatus.Retrying,
            TaskRunStatus.Escalated,
        ),
        TaskRunStatus.Escalated to setOf(
            TaskRunStatus.Retrying,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        ),
        TaskRunStatus.Cancelled to emptySet(),
    )

    fun canTransition(from: TaskRunStatus, to: TaskRunStatus): Boolean =
        to in allowed.getValue(from)

    fun requireAllowed(from: TaskRunStatus, to: TaskRunStatus) {
        require(canTransition(from, to)) { "Illegal task transition: $from -> $to" }
    }
}
