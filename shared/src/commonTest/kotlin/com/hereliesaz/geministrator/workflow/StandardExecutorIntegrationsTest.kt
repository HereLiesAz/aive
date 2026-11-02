package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardExecutorIntegrationsTest {
    @Test
    fun testRunnerDispatchAndReconcileUseExternalRunId() = runBlocking {
        val client = FakeTestRunnerClient()
        val integration = TestRunnerExecutorIntegration(client)
        val context = context(TaskExecutor.TestRunner("./gradlew test"))

        val started = integration.dispatch(context)
        assertEquals("./gradlew test", client.command)
        assertEquals(TaskRunStatus.Running, started.status)
        assertEquals("test-1", started.externalRunId)

        val completed = integration.reconcile(context.withExternalRun("test-1"))
        assertEquals("test-1", client.runId)
        assertEquals(TaskRunStatus.Completed, completed.status)
        assertEquals(1f, completed.progress)
    }

    @Test
    fun testRunnerDispatchHandsTheClientTheTaskContextAndAsksItAboutTheProject() = runBlocking {
        val client = ContextTestRunnerClient()
        val integration = TestRunnerExecutorIntegration(client)
        val context = context(TaskExecutor.TestRunner("npm test"))

        assertEquals(false, integration.supports(context.executor, context.project))
        client.supported = true
        assertEquals(true, integration.supports(context.executor, context.project))

        integration.dispatch(context)
        assertEquals(context, client.context)
        assertEquals("npm test", client.command)
    }

    @Test
    fun deploymentDispatchAndReconcileUseEnvironmentAndRunId() = runBlocking {
        val client = FakeDeploymentClient()
        val integration = DeploymentExecutorIntegration(client)
        val context = context(TaskExecutor.Deployment("production"))

        val started = integration.dispatch(context)
        assertEquals("production", client.environment)
        assertEquals("deploy-1", started.externalRunId)
        assertEquals(TaskRunStatus.Running, started.status)

        val completed = integration.reconcile(context.withExternalRun("deploy-1"))
        assertEquals("deploy-1", client.runId)
        assertEquals(TaskRunStatus.Completed, completed.status)
    }

    @Test
    fun repositoryOperationDispatchAndReconcileUseOperationAndRunId() = runBlocking {
        val client = FakeRepositoryOperationClient()
        val integration = RepositoryOperationExecutorIntegration(client)
        val context = context(TaskExecutor.RepositoryOperation("create-release-branch"))

        val started = integration.dispatch(context)
        assertEquals("create-release-branch", client.operation)
        assertEquals("repo-op-1", started.externalRunId)

        val completed = integration.reconcile(context.withExternalRun("repo-op-1"))
        assertEquals("repo-op-1", client.runId)
        assertEquals(TaskRunStatus.Completed, completed.status)
    }

    @Test
    fun externalServiceDispatchAndReconcileUseServiceOperationAndRunId() = runBlocking {
        val client = FakeExternalServiceClient()
        val integration = ExternalServiceExecutorIntegration(client)
        val context = context(TaskExecutor.ExternalService("sentry", "create-release"))

        val started = integration.dispatch(context)
        assertEquals("sentry", client.service)
        assertEquals("create-release", client.operation)
        assertEquals("service-1", started.externalRunId)

        val completed = integration.reconcile(context.withExternalRun("service-1"))
        assertEquals("service-1", client.runId)
        assertEquals(TaskRunStatus.Completed, completed.status)
    }

    @Test
    fun nestedWorkflowDispatchAndReconcileUseDefinitionTargetProjectAndRunId() = runBlocking {
        val client = FakeNestedWorkflowClient()
        val integration = NestedWorkflowExecutorIntegration(client)
        val childDefinitionId = WorkflowDefinitionId("child-workflow")
        val targetProjectId = ProjectId("child-project")
        val context = context(TaskExecutor.NestedWorkflow(childDefinitionId, targetProjectId))

        val started = integration.dispatch(context)
        assertEquals(childDefinitionId, client.workflowDefinitionId)
        assertEquals(targetProjectId, client.targetProjectId)
        assertEquals("nested-1", started.externalRunId)

        val completed = integration.reconcile(context.withExternalRun("nested-1"))
        assertEquals("nested-1", client.runId)
        assertEquals(TaskRunStatus.Completed, completed.status)
    }

    private fun context(executor: TaskExecutor): TaskExecutorContext {
        val taskId = TaskDefinitionId("task")
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val task = TaskDefinition(
            id = taskId,
            name = "Task",
            objective = "Execute",
            roleId = null,
            executor = executor,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Workflow",
            tasks = listOf(task),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val taskRun = TaskRun(
            id = TaskRunId("task-run"),
            taskDefinitionId = taskId,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = executor,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        return TaskExecutorContext(project, definition, run, task, taskRun, executor, 2L)
    }

    private fun TaskExecutorContext.withExternalRun(runId: String): TaskExecutorContext = copy(
        taskRun = taskRun.copy(
            status = TaskRunStatus.Running,
            externalRunId = runId,
        ),
    )
}

private class FakeTestRunnerClient : TestRunnerClient {
    var command: String? = null
    var runId: String? = null

    override suspend fun start(project: Project, command: String?): ExternalExecutionRun {
        this.command = command
        return ExternalExecutionRun("test-1", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
    }
}

private class ContextTestRunnerClient : TestRunnerClient {
    var supported = false
    var context: TaskExecutorContext? = null
    var command: String? = null

    override fun supports(project: Project): Boolean = supported

    override suspend fun start(project: Project, command: String?): ExternalExecutionRun =
        error("the context-aware start should be used")

    override suspend fun start(context: TaskExecutorContext, command: String?): ExternalExecutionRun {
        this.context = context
        this.command = command
        return ExternalExecutionRun("test-2", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        ExternalExecutionRun(runId, ExternalExecutionStatus.Running)
}

private class FakeDeploymentClient : DeploymentClient {
    var environment: String? = null
    var runId: String? = null

    override suspend fun deploy(project: Project, environment: String): ExternalExecutionRun {
        this.environment = environment
        return ExternalExecutionRun("deploy-1", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
    }
}

private class FakeRepositoryOperationClient : RepositoryOperationClient {
    var operation: String? = null
    var runId: String? = null

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        this.operation = operation
        return ExternalExecutionRun("repo-op-1", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
    }
}

private class FakeExternalServiceClient : ExternalServiceClient {
    var service: String? = null
    var operation: String? = null
    var runId: String? = null

    override suspend fun start(project: Project, service: String, operation: String?): ExternalExecutionRun {
        this.service = service
        this.operation = operation
        return ExternalExecutionRun("service-1", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
    }
}

private class FakeNestedWorkflowClient : NestedWorkflowClient {
    var workflowDefinitionId: WorkflowDefinitionId? = null
    var targetProjectId: ProjectId? = null
    var runId: String? = null

    override suspend fun start(
        project: Project,
        workflowDefinitionId: WorkflowDefinitionId,
        targetProjectId: ProjectId?,
    ): ExternalExecutionRun {
        this.workflowDefinitionId = workflowDefinitionId
        this.targetProjectId = targetProjectId
        return ExternalExecutionRun("nested-1", ExternalExecutionStatus.Running)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
    }
}
