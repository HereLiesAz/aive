package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus

data class ExternalExecutionRun(
    val id: String,
    val status: ExternalExecutionStatus,
    val artifacts: List<ArtifactRef> = emptyList(),
    val progress: Float? = null,
    val message: String? = null,
) {
    init {
        require(progress == null || progress in 0f..1f) { "Execution progress must be normalized 0f..1f" }
    }
}

enum class ExternalExecutionStatus {
    Queued,
    Running,
    Verifying,
    Completed,
    Failed,
}

interface TestRunnerClient {
    /** Whether this client can run tests for [project] (for example, only a linked local folder). */
    fun supports(project: Project): Boolean = true

    suspend fun start(project: Project, command: String?): ExternalExecutionRun

    /**
     * Starts a test run with the whole task context: the run's upstream work (where to run) and its
     * approvals. Clients that only need the project keep the default.
     */
    suspend fun start(context: TaskExecutorContext, command: String?): ExternalExecutionRun =
        start(context.project, command)

    suspend fun getRun(project: Project, runId: String): ExternalExecutionRun
}

class TestRunnerExecutorIntegration(
    private val client: TestRunnerClient,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.TestRunner

    override fun supports(executor: TaskExecutor, project: Project): Boolean =
        executor is TaskExecutor.TestRunner && client.supports(project)

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.TestRunner
        return client.start(context, executor.command).toTaskExecution()
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "Test runner task ${context.task.id.value} is missing its external run ID"
        }
        return client.getRun(context.project, runId).toTaskExecution()
    }
}

interface DeploymentClient {
    suspend fun deploy(project: Project, environment: String): ExternalExecutionRun
    suspend fun getRun(project: Project, runId: String): ExternalExecutionRun
}

class DeploymentExecutorIntegration(
    private val client: DeploymentClient,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.Deployment

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.Deployment
        return client.deploy(context.project, executor.environment).toTaskExecution()
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "Deployment task ${context.task.id.value} is missing its external run ID"
        }
        return client.getRun(context.project, runId).toTaskExecution()
    }
}

internal fun ExternalExecutionRun.toTaskExecution(): TaskExecutorExecution = TaskExecutorExecution(
    status = when (status) {
        ExternalExecutionStatus.Queued,
        ExternalExecutionStatus.Running,
        -> TaskRunStatus.Running
        ExternalExecutionStatus.Verifying -> TaskRunStatus.Verifying
        ExternalExecutionStatus.Completed -> TaskRunStatus.Completed
        ExternalExecutionStatus.Failed -> TaskRunStatus.Failed
    },
    externalRunId = id,
    artifacts = artifacts,
    progress = progress ?: if (status == ExternalExecutionStatus.Completed) 1f else null,
    progressMessage = message,
)
