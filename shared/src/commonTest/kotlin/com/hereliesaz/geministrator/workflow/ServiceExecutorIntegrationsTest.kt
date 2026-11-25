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
    fun hostCommitRefusesUnapprovedOrUnrelatedProposalBeforeCallingClient() = runBlocking {
        val client = RecordingRepositoryClient()
        val integration = RepositoryOperationExecutorIntegration(client)
        val attempt = context(TaskExecutor.RepositoryOperation("commit-files:proposal:aive/change"))
        val failed = integration.dispatch(attempt)
        assertEquals(TaskRunStatus.Failed, failed.status)
        assertTrue(failed.progressMessage.orEmpty().contains("Unknown proposal task"))
        assertEquals(null, client.fileProposal)
    }

    @Test
    fun hostCommitConsumesOnlyTheApprovedDirectProposal() = runBlocking {
        val client = RecordingRepositoryClient()
        val integration = RepositoryOperationExecutorIntegration(client)
        val initial = context(TaskExecutor.RepositoryOperation("commit-files:proposal:aive/change"))
        val producer = TaskDefinitionId("proposal")
        val approval = TaskDefinitionId("approval")
        val proposalTask = TaskDefinition(producer, "Proposal", "Produce files", null,
            executor = TaskExecutor.RoleAgent(com.hereliesaz.geministrator.domain.RoleDefinitionId("worker")))
        val gate = TaskDefinition(approval, "Review proposal", "Approve these files", null,
            dependsOn = setOf(producer), executor = TaskExecutor.HumanApproval())
        val commit = initial.task.copy(dependsOn = setOf(producer, approval))
        val proposalRun = initial.taskRun.copy(id = TaskRunId("proposal-run"), taskDefinitionId = producer,
            status = TaskRunStatus.Completed, executor = proposalTask.executor,
            artifacts = listOf(ArtifactRef(ArtifactId("proposal-output"), ArtifactKind.CodeChange,
                TaskRunId("proposal-run"), "File proposal", textContent = """{"baseCommit":"${"a".repeat(40)}","files":[{"path":"README.md","expectedBlob":null,"content":"new"}]}""",
                createdAtEpochMillis = 1L)))
        val approvalRun = initial.taskRun.copy(id = TaskRunId("approval-run"), taskDefinitionId = approval,
            status = TaskRunStatus.Completed, executor = gate.executor)
        val approved = initial.copy(task = commit,
            definition = initial.definition.copy(tasks = listOf(proposalTask, gate, commit)),
            run = initial.run.copy(taskRuns = initial.run.taskRuns + mapOf(producer to proposalRun, approval to approvalRun)))
        val denied = approved.copy(run = approved.run.copy(taskRuns = approved.run.taskRuns +
            (approval to approvalRun.copy(status = TaskRunStatus.Cancelled))))
        assertEquals(TaskRunStatus.Failed, integration.dispatch(denied).status)
        assertEquals(null, client.fileProposal)
        val result = integration.dispatch(approved)
        assertEquals(TaskRunStatus.Completed, result.status)
        assertEquals("aive/change", client.branch)
        assertEquals(RepositoryProposedFile("README.md", null, "new"), client.fileProposal?.files?.single())

        // A completed approval cannot authorize paths outside the original host snapshot.
        val snapshotId = TaskDefinitionId("snapshot")
        val snapshotTask = TaskDefinition(snapshotId, "Snapshot", "Read files", null,
            executor = TaskExecutor.RepositoryOperation("read-file-proposal-context"))
        val snapshotRun = proposalRun.copy(id = TaskRunId("snapshot-run"), taskDefinitionId = snapshotId,
            executor = snapshotTask.executor,
            artifacts = listOf(proposalRun.artifacts.single().copy(
                id = ArtifactId("snapshot-output"), kind = ArtifactKind.CommandOutput,
                taskRunId = TaskRunId("snapshot-run"),
                textContent = """{"baseCommit":"${"a".repeat(40)}","files":[{"path":"OTHER.md","expectedBlob":null,"content":""}]}""")))
        val scoped = approved.copy(
            definition = approved.definition.copy(tasks = listOf(snapshotTask,
                proposalTask.copy(hostFileProposal = true, dependsOn = setOf(snapshotId)), gate, commit)),
            run = approved.run.copy(taskRuns = approved.run.taskRuns + (snapshotId to snapshotRun)))
        val scopedClient = RecordingRepositoryClient()
        val scopedIntegration = RepositoryOperationExecutorIntegration(scopedClient)
        val rejected = scopedIntegration.dispatch(scoped)
        assertEquals(TaskRunStatus.Failed, rejected.status)
        assertTrue(rejected.progressMessage.orEmpty().contains("outside its host snapshot"))
        assertEquals(null, scopedClient.fileProposal)
        val validSnapshot = snapshotRun.copy(artifacts = listOf(snapshotRun.artifacts.single().copy(
            textContent = """{"baseCommit":"${"a".repeat(40)}","files":[{"path":"README.md","expectedBlob":null,"content":""}]}""")))
        val valid = scoped.copy(run = scoped.run.copy(taskRuns = scoped.run.taskRuns + (snapshotId to validSnapshot)))
        assertEquals(TaskRunStatus.Completed, scopedIntegration.dispatch(valid).status)
        assertEquals(RepositoryProposedFile("README.md", null, "new"), scopedClient.fileProposal?.files?.single())

    }

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
    fun repositoryOperationRetryUsesANewIdentityPerAttempt() = runBlocking {
        val client = RecordingRepositoryClient()
        val integration = RepositoryOperationExecutorIntegration(client)
        val first = context(TaskExecutor.RepositoryOperation("push"))

        integration.dispatch(first)
        integration.dispatch(first.copy(taskRun = first.taskRun.copy(status = TaskRunStatus.Retrying, attempt = 2)))

        assertEquals(listOf("task-run", "task-run#attempt-2"), client.identities)
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
    val identities = mutableListOf<String>()
    var fileProposal: RepositoryFileProposal? = null
    var branch: String? = null

    override fun supportsFileProposals(project: Project) = true

    override suspend fun commitFileProposal(project: Project, branch: String, proposal: RepositoryFileProposal): ExternalExecutionRun {
        this.branch = branch
        fileProposal = proposal
        return ExternalExecutionRun("file-commit", ExternalExecutionStatus.Completed)
    }

    override suspend fun start(project: Project, operation: String, operationIdentity: String): ExternalExecutionRun {
        identities += operationIdentity
        return start(project, operation)
    }

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
