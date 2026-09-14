package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlinx.coroutines.CancellationException

interface RepositoryOperationClient {
    fun supports(project: Project): Boolean = true
    suspend fun start(project: Project, operation: String): ExternalExecutionRun
    suspend fun getRun(project: Project, runId: String): ExternalExecutionRun
}

class RepositoryOperationExecutorIntegration(
    private val client: RepositoryOperationClient,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.RepositoryOperation

    override fun supports(executor: TaskExecutor, project: Project): Boolean =
        executor is TaskExecutor.RepositoryOperation && client.supports(project)

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.RepositoryOperation
        return repositoryOperation("Repository operation '${executor.operation}' failed") {
            client.start(context.project, executor.operation).toRepositoryTaskExecution(context)
        }
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "Repository operation task ${context.task.id.value} is missing its external run ID"
        }
        return repositoryOperation("Repository operation '${context.task.name}' could not be reconciled") {
            client.getRun(context.project, runId).toRepositoryTaskExecution(context)
        }
    }

    private suspend fun repositoryOperation(
        fallback: String,
        block: suspend () -> TaskExecutorExecution,
    ): TaskExecutorExecution = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        TaskExecutorExecution(
            status = TaskRunStatus.Failed,
            progressMessage = failure.message?.takeIf(String::isNotBlank) ?: fallback,
        )
    }

    private fun ExternalExecutionRun.toRepositoryTaskExecution(context: TaskExecutorContext): TaskExecutorExecution =
        copy(
            artifacts = artifacts.map { artifact ->
                artifact.copy(taskRunId = context.taskRun.id)
            },
        ).toTaskExecution()
}

interface ExternalServiceClient {
    suspend fun start(project: Project, service: String, operation: String?): ExternalExecutionRun
    suspend fun getRun(project: Project, runId: String): ExternalExecutionRun
}

class ExternalServiceExecutorIntegration(
    private val client: ExternalServiceClient,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.ExternalService

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.ExternalService
        return client.start(context.project, executor.service, executor.operation).toTaskExecution()
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "External service task ${context.task.id.value} is missing its external run ID"
        }
        return client.getRun(context.project, runId).toTaskExecution()
    }
}

interface NestedWorkflowClient {
    suspend fun start(
        project: Project,
        workflowDefinitionId: WorkflowDefinitionId,
        targetProjectId: ProjectId? = null,
    ): ExternalExecutionRun
    suspend fun getRun(project: Project, runId: String): ExternalExecutionRun
}

class NestedWorkflowExecutorIntegration(
    private val client: NestedWorkflowClient,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.NestedWorkflow

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.NestedWorkflow
        return client.start(context.project, executor.workflowDefinitionId, executor.projectId).toTaskExecution()
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "Nested workflow task ${context.task.id.value} is missing its external run ID"
        }
        return client.getRun(context.project, runId).toTaskExecution()
    }
}
