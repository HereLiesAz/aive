package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor

object WorkflowRunFactory {
    const val EXECUTOR_INTEGRATION_UNAVAILABLE = "EXECUTOR_INTEGRATION_UNAVAILABLE"

    fun create(
        definition: WorkflowDefinition,
        workflowRunId: WorkflowRunId,
        projectId: ProjectId,
        objective: String,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
        repository: RepositoryRef? = null,
        roles: Collection<RoleDefinition> = emptyList(),
        /** Runs this run continues (one) or merges (several); empty for a root run. */
        parentWorkflowRunIds: List<WorkflowRunId> = emptyList(),
    ): WorkflowRun {
        WorkflowGraphValidator.requireValid(definition)
        val rolesById = roles.associateBy(RoleDefinition::id)

        val taskRuns = definition.tasks.associate { task ->
            val conditionTask = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> c.ofTask
                is TaskCondition.OnFailure -> c.ofTask
            }
            val blocked = task.dependsOn.isNotEmpty() ||
                (conditionTask != null && conditionTask != task.id)
            val status = if (blocked) TaskRunStatus.Blocked else TaskRunStatus.Ready
            task.id to TaskRun(
                id = taskRunIdFactory(task.id),
                taskDefinitionId = task.id,
                status = status,
                assignedRoleId = task.roleId,
                executor = task.effectiveExecutor(task.roleId?.let(rolesById::get)),
                blockingReason = if (status == TaskRunStatus.Blocked) {
                    BlockingReason(
                        code = "WAITING_FOR_DEPENDENCIES",
                        message = "Waiting for task dependencies.",
                    )
                } else {
                    null
                },
            )
        }

        return WorkflowRun(
            id = workflowRunId,
            projectId = projectId,
            workflowDefinitionId = definition.id,
            objective = objective,
            status = WorkflowRunStatus.Created,
            taskRuns = taskRuns,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            repositorySnapshot = repository,
            roleSnapshot = roles
                .filterNot { it.id.value == ROLE_COLLECTION_MARKER_ID }
                .distinctBy(RoleDefinition::id),
            parentWorkflowRunIds = parentWorkflowRunIds.distinct(),
        )
    }

    fun refreshReadiness(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        nowEpochMillis: Long,
    ): WorkflowRun {
        val definitionsById = definition.tasks.associateBy { it.id }
        val refreshed = run.taskRuns.mapValues { (taskId, taskRun) ->
            if (taskRun.status != TaskRunStatus.Blocked) return@mapValues taskRun
            if (taskRun.blockingReason?.code == EXECUTOR_INTEGRATION_UNAVAILABLE) return@mapValues taskRun

            val task = definitionsById[taskId] ?: return@mapValues taskRun
            val dependencyRuns = task.dependsOn.mapNotNull(run.taskRuns::get)
            val allTerminal = dependencyRuns.size == task.dependsOn.size &&
                dependencyRuns.all { it.status.isTerminal() }

            // A dependency skipped because its own condition was not met is a branch that was not
            // taken, not a failure: an Always join after it proceeds on the branches that did run.
            val allCompletedOrSkipped = allTerminal &&
                dependencyRuns.all { it.status == TaskRunStatus.Completed || it.isSkippedBranch() }
            val anyCompleted = dependencyRuns.isEmpty() ||
                dependencyRuns.any { it.status == TaskRunStatus.Completed }

            val conditionTargetRun = when (val c = task.condition) {
                is TaskCondition.Always -> null
                is TaskCondition.OnAnyOutcome -> run.taskRuns[c.ofTask]
                is TaskCondition.OnFailure -> run.taskRuns[c.ofTask]
            }
            // A condition reference is a real scheduling edge even when it is not duplicated in
            // dependsOn. Do not decide the branch until both explicit dependencies and the
            // condition target have reached terminal state.
            val conditionSettled = allTerminal &&
                (conditionTargetRun == null || conditionTargetRun.status.isTerminal())
            val conditionMet: Boolean = when (task.condition) {
                is TaskCondition.Always -> allCompletedOrSkipped && anyCompleted
                is TaskCondition.OnAnyOutcome -> conditionSettled && conditionTargetRun != null
                is TaskCondition.OnFailure -> conditionSettled && conditionTargetRun != null &&
                    (conditionTargetRun.status == TaskRunStatus.Failed ||
                        conditionTargetRun.status == TaskRunStatus.Escalated ||
                        conditionTargetRun.status == TaskRunStatus.Cancelled)
            }

            // Always-conditioned tasks stay Blocked when a dep fails rather than being cancelled:
            // the happy-path semantics let the workflow continue via failure handlers without
            // prematurely cancelling tasks that haven't had a chance to run yet. Once every
            // dependency is Completed or Cancelled, though, nothing can unblock them, so a join
            // that no completed branch reaches is skipped instead of waiting forever.
            val conditionUnreachable = if (task.condition is TaskCondition.Always) {
                allTerminal && !conditionMet &&
                    dependencyRuns.all { it.status == TaskRunStatus.Completed || it.status == TaskRunStatus.Cancelled }
            } else {
                conditionSettled && !conditionMet
            }
            when {
                conditionMet -> taskRun.copy(
                    status = TaskRunStatus.Ready,
                    blockingReason = null,
                )
                conditionUnreachable -> taskRun.copy(
                    status = TaskRunStatus.Cancelled,
                    blockingReason = BlockingReason(
                        code = CONDITION_NOT_MET,
                        message = "Task condition was not satisfied; task will not run.",
                    ),
                )
                else -> taskRun
            }
        }

        // Unchanged readiness leaves the run untouched, so an idle cycle can recognise it has
        // nothing to do instead of rewriting the run every second.
        if (refreshed == run.taskRuns) return run
        return run.copy(
            taskRuns = refreshed,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    private fun TaskRun.isSkippedBranch() =
        status == TaskRunStatus.Cancelled && blockingReason?.code == CONDITION_NOT_MET

    private const val CONDITION_NOT_MET = "CONDITION_NOT_MET"
}
