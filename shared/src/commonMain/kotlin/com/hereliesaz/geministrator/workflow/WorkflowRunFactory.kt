package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
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
        repository: RepositoryRef? = null,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
    ): WorkflowRun {
        WorkflowGraphValidator.requireValid(definition)

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
                executor = task.effectiveExecutor(),
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
            val hasFailedDependency = dependencyRuns.any {
                it.status == TaskRunStatus.Failed ||
                    it.status == TaskRunStatus.Escalated ||
                    it.status == TaskRunStatus.Cancelled
            }
            val allCompleted = dependencyRuns.size == task.dependsOn.size &&
                dependencyRuns.all { it.status == TaskRunStatus.Completed }
            val allTerminal = dependencyRuns.size == task.dependsOn.size &&
                dependencyRuns.all { it.status.isTerminal() }

            val conditionMet: Boolean = when (val c = task.condition) {
                is TaskCondition.Always -> allCompleted
                is TaskCondition.OnAnyOutcome -> {
                    val targetRun = run.taskRuns[c.ofTask]
                    allTerminal && targetRun != null && targetRun.status.isTerminal()
                }
                is TaskCondition.OnFailure -> {
                    val targetRun = run.taskRuns[c.ofTask]
                    allTerminal && targetRun != null &&
                        (targetRun.status == TaskRunStatus.Failed ||
                            targetRun.status == TaskRunStatus.Escalated ||
                            targetRun.status == TaskRunStatus.Cancelled)
                }
            }

            val conditionUnreachable = allTerminal && !conditionMet
            when {
                conditionMet -> taskRun.copy(
                    status = TaskRunStatus.Ready,
                    blockingReason = null,
                )
                hasFailedDependency && task.condition is TaskCondition.Always -> taskRun.copy(
                    blockingReason = BlockingReason(
                        code = "DEPENDENCY_FAILED",
                        message = "A dependency did not complete successfully.",
                    ),
                )
                conditionUnreachable -> taskRun.copy(
                    status = TaskRunStatus.Cancelled,
                    blockingReason = BlockingReason(
                        code = "CONDITION_NOT_MET",
                        message = "Task condition was not satisfied; task will not run.",
                    ),
                )
                else -> taskRun
            }
        }

        return run.copy(
            taskRuns = refreshed,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }
}
