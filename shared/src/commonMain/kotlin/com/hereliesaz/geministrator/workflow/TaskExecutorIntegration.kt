package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/**
 * Runtime driver for executor kinds that are not agent sessions or human approval gates.
 *
 * Implementations own the external system lifecycle and return normalized task state so the
 * workflow runtime never has to pretend an undriven executor is running.
 */
interface TaskExecutorIntegration {
    fun supports(executor: TaskExecutor): Boolean

    fun supports(executor: TaskExecutor, project: Project): Boolean = supports(executor)

    suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution

    suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution
}

data class TaskExecutorContext(
    val project: Project,
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val task: TaskDefinition,
    val taskRun: TaskRun,
    val executor: TaskExecutor,
    val nowEpochMillis: Long,
)

data class TaskExecutorExecution(
    val status: TaskRunStatus,
    val externalRunId: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val progress: Float? = null,
    val progressMessage: String? = null,
) {
    init {
        require(status in supportedStatuses) {
            "Executor integrations may only report Running, Verifying, Completed, or Failed"
        }
        require(progress == null || progress in 0f..1f) { "Task progress must be normalized 0f..1f" }
    }

    private companion object {
        val supportedStatuses = setOf(
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
        )
    }
}

class TaskExecutorIntegrationRegistry(
    integrations: Collection<TaskExecutorIntegration> = emptyList(),
) {
    private val integrations = integrations.toList()

    fun integrationFor(executor: TaskExecutor): TaskExecutorIntegration? =
        integrations.firstOrNull { it.supports(executor) }

    fun integrationFor(executor: TaskExecutor, project: Project): TaskExecutorIntegration? =
        integrations.firstOrNull { it.supports(executor, project) }

    fun isAvailable(executor: TaskExecutor): Boolean = integrationFor(executor) != null

    fun isAvailable(executor: TaskExecutor, project: Project): Boolean =
        integrationFor(executor, project) != null

    fun withIntegration(integration: TaskExecutorIntegration): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(integrations + integration)

    companion object {
        val Empty = TaskExecutorIntegrationRegistry()
    }
}

fun TaskExecutor.isSystemExecutor(): Boolean = when (this) {
    is TaskExecutor.GitHubAction,
    is TaskExecutor.TestRunner,
    is TaskExecutor.Deployment,
    is TaskExecutor.RepositoryOperation,
    is TaskExecutor.ExternalService,
    is TaskExecutor.NestedWorkflow,
    -> true
    is TaskExecutor.RoleAgent,
    is TaskExecutor.HumanApproval,
    -> false
}
