package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
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
import kotlin.test.assertTrue

class ServiceExecutorIntegrationsTest {
    @Test
    fun repositoryOperationDispatchesAndCompletes() = runBlocking {
        val client = RecordingRepositoryClient()
        val integration = RepositoryOperationExecutorIntegration(client)
        val context = context(TaskExecutor.RepositoryOperation("open-pull-request"))

        val started = integration.dispatch(context)
        assertEquals("open-pull-request", client.operation)
        assertEquals("repo-1", started.externalRunId)
        assertEquals(context.taskRun.id, started.artifacts.single().taskRunId)

        val runningContext = context.copy(
            taskRun = context.taskRun.copy(status = TaskRunStatus.Running, externalRunId = "repo-1"),
        )
        val completed = integration.reconcile(runningContext)
        assertEquals(TaskRunStatus.Completed, completed.status)
        assertEquals("repo-1", client.runId)
        assertEquals(runningContext.taskRun.id, completed.artifacts.single().taskRunId)
    }

    @Test
    fun repositoryOperationFailureBecomesTaskFailure() = runBlocking {
        val integration = RepositoryOperationExecutorIntegration(FailingRepositoryClient())
        val execution = integration.dispatch(context(TaskExecutor.RepositoryOperation("status")))

        assertEquals(TaskRunStatus.Failed, execution.status)
        assertTrue(execution.progressMessage.orEmpty().contains("credential rejected"))
    }

    @Test
    fun externalServiceCarriesServiceAndOperation() = runBlocking {
        val client = RecordingExternalServiceClient()
        val integration = ExternalServiceExecutorIntegration(client)
        val context = context(TaskExecutor.ExternalService("vercel", "deploy-preview"))

        val started = integration.dispatch(context)

        assertEquals("vercel", client.service)
        assertEquals("deploy-preview", client.operation)
        assertEquals(TaskRunStatus.Running, started.status)
    }

    @Test
    fun nestedWorkflowCarriesDefinitionAndTargetProject() = runBlocking {
        val client = RecordingNestedWorkflowClient()
        val integration = NestedWorkflowExecutorIntegration(client)
        val nestedId = WorkflowDefinitionId("child-workflow")
        val targetProjectId = ProjectId("child-project")
        val context = context(TaskExecutor.NestedWorkflow(nestedId, targetProjectId))

        val started = integration.dispatch(context)

        assertEquals(nestedId, client.workflowDefinitionId)
        assertEquals(targetProjectId, client.targetProjectId)
        assertEquals("nested-1", started.externalRunId)
    }

    private fun context(executor: TaskExecutor): TaskExecutorContext {
        val taskId = TaskDefinitionId("task")
        val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val task = TaskDefinition(taskId, "Task", "Execute", roleId = null, executor = executor)
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
}

private class RecordingRepositoryClient : RepositoryOperationClient {
    var operation: String? = null
    var runId: String? = null

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        this.operation = operation
        return ExternalExecutionRun(
            id = "repo-1",
            status = ExternalExecutionStatus.Running,
            artifacts = listOf(repositoryArtifact()),
        )
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        this.runId = runId
        return ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Completed,
            artifacts = listOf(repositoryArtifact()),
        )
    }

    private fun repositoryArtifact() = ArtifactRef(
        id = ArtifactId("repo-artifact"),
        kind = ArtifactKind.CommandOutput,
        taskRunId = TaskRunId("external-placeholder"),
        label = "Repository operation",
        textContent = "ok",
        createdAtEpochMillis = 1L,
    )
}

private class FailingRepositoryClient : RepositoryOperationClient {
    override suspend fun start(project: Project, operation: String): ExternalExecutionRun =
        error("credential rejected")

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        error("credential rejected")
}

private class RecordingExternalServiceClient : ExternalServiceClient {
    var service: String? = null
    var operation: String? = null
    override suspend fun start(project: Project, service: String, operation: String?): ExternalExecutionRun {
        this.service = service
        this.operation = operation
        return ExternalExecutionRun("service-1", ExternalExecutionStatus.Running)
    }
    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
}

private class RecordingNestedWorkflowClient : NestedWorkflowClient {
    var workflowDefinitionId: WorkflowDefinitionId? = null
    var targetProjectId: ProjectId? = null
    override suspend fun start(
        project: Project,
        workflowDefinitionId: WorkflowDefinitionId,
        targetProjectId: ProjectId?,
    ): ExternalExecutionRun {
        this.workflowDefinitionId = workflowDefinitionId
        this.targetProjectId = targetProjectId
        return ExternalExecutionRun("nested-1", ExternalExecutionStatus.Running)
    }
    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
}
