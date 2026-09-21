package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class GitHubActionsExecutorIntegrationTest {
    @Test
    fun dispatchPassesRepositoryWorkflowAndResolvedRef() = runBlocking {
        val client = FakeGitHubActionsClient(
            dispatchedRun = GitHubWorkflowRun(
                id = "run-42",
                status = GitHubWorkflowRunStatus.Queued,
                progressMessage = "queued",
            ),
        )
        val integration = GitHubActionsExecutorIntegration(client)
        val context = context(TaskExecutor.GitHubAction(workflow = "ci.yml"))

        val execution = integration.dispatch(context)

        val dispatch = assertNotNull(client.lastDispatch)
        assertEquals(context.project.repository, dispatch.repository)
        assertEquals("ci.yml", dispatch.workflow)
        assertEquals("main", dispatch.ref)
        val envelope = Json.decodeFromString<AiveTaskEnvelope>(
            assertNotNull(dispatch.inputs["aive_context"]),
        )
        assertEquals("Run CI", envelope.taskObjective)
        assertEquals("Ship", envelope.workflowObjective)
        assertEquals("Project", envelope.projectName)
        assertEquals(TaskRunStatus.Running, execution.status)
        assertEquals("run-42", execution.externalRunId)
        assertEquals("queued", execution.progressMessage)
    }

    @Test
    fun pythonScriptRunnerPassesContextScriptAndLanguageInputs() = runBlocking {
        val client = FakeGitHubActionsClient()
        val integration = GitHubActionsExecutorIntegration(client)
        val executor = TaskExecutor.Script(
            language = ScriptLanguage.Python,
            source = "result = {'status': 'completed', 'output': aive['taskObjective']}",
            runner = ScriptRunner.GitHubActions(workflow = "aive-script.yml"),
        )
        val context = context(executor)

        integration.dispatch(context)

        val inputs = assertNotNull(client.lastDispatch).inputs
        assertEquals("python", inputs["aive_language"])
        assertEquals(executor.source, inputs["aive_script"])
        val envelope = Json.decodeFromString<AiveTaskEnvelope>(
            assertNotNull(inputs["aive_context"]),
        )
        assertEquals("Run CI", envelope.taskObjective)
    }

    @Test
    fun executorEnvelopeHonorsWorkflowRedactionPolicy() = runBlocking {
        val client = FakeGitHubActionsClient()
        val integration = GitHubActionsExecutorIntegration(client)
        val executor = TaskExecutor.GitHubAction("ci.yml")
        val base = context(executor)
        val role = RoleDefinition(
            id = RoleDefinitionId("scripted-role"),
            name = "Scripted role",
            description = "Runs external automation",
            instructions = "SECRET ROLE INSTRUCTIONS",
        )
        val redacted = base.copy(
            role = role,
            definition = base.definition.copy(
                payloadRedactionPolicy = com.hereliesaz.geministrator.domain.PayloadRedactionPolicy(
                    redactObjective = true,
                    redactRoleInstructions = true,
                ),
            ),
        )

        integration.dispatch(redacted)

        val envelope = Json.decodeFromString<AiveTaskEnvelope>(
            assertNotNull(assertNotNull(client.lastDispatch).inputs["aive_context"]),
        )
        assertEquals("[REDACTED]", envelope.taskObjective)
        assertEquals("[REDACTED]", assertNotNull(envelope.role).instructions)
        assertTrue(envelope.acceptanceCriteria.isEmpty())
    }

    @Test
    fun explicitExecutorRefOverridesRepositoryDefault() = runBlocking {
        val client = FakeGitHubActionsClient()
        val integration = GitHubActionsExecutorIntegration(client)
        val context = context(TaskExecutor.GitHubAction(workflow = "release.yml", ref = "release/v2"))

        integration.dispatch(context)

        assertEquals("release/v2", client.lastDispatch?.ref)
    }

    @Test
    fun gitLabLinkIsRejectedBeforeCallingGitHub() = runBlocking {
        val client = FakeGitHubActionsClient()
        val integration = GitHubActionsExecutorIntegration(client)
        val base = context(TaskExecutor.GitHubAction(workflow = "ci.yml"))
        val foreignProject = base.project.copy(
            repository = base.project.repository!!.copy(
                source = RepositorySource.GitLab,
                remoteUrl = "https://gitlab.com/team/haive",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            integration.dispatch(base.copy(project = foreignProject))
        }
        assertEquals(null, client.lastDispatch)
    }

    @Test
    fun reconcileMapsCompletedRunAndArtifacts() = runBlocking {
        val base = context(TaskExecutor.GitHubAction("ci.yml"))
        val client = FakeGitHubActionsClient(
            reconciledRun = GitHubWorkflowRun(
                id = "run-42",
                status = GitHubWorkflowRunStatus.Completed,
                artifacts = listOf(
                    GitHubWorkflowArtifact(
                        id = "artifact-7",
                        name = "test-results",
                        archiveDownloadUrl = "https://example.invalid/artifact",
                    ),
                ),
                progressMessage = "complete",
            ),
        )
        val integration = GitHubActionsExecutorIntegration(client)
        val context = base.copy(taskRun = base.taskRun.copy(status = TaskRunStatus.Running, externalRunId = "run-42"))

        val execution = integration.reconcile(context)

        assertEquals(context.project.repository, client.lastRepository)
        assertEquals("run-42", client.lastRunId)
        assertEquals(TaskRunStatus.Completed, execution.status)
        assertEquals(1f, execution.progress)
        val artifact = execution.artifacts.single()
        assertEquals(ArtifactId("task-run:github-action:1:artifact-7"), artifact.id)
        assertEquals(ArtifactKind.CommandOutput, artifact.kind)
        assertEquals("test-results", artifact.label)
        assertEquals("https://example.invalid/artifact", artifact.uri)
    }

    @Test
    fun jobStepProgressProjectsIntoTaskProgress() = runBlocking {
        val base = context(TaskExecutor.GitHubAction("ci.yml"))
        val client = FakeGitHubActionsClient(
            reconciledRun = GitHubWorkflowRun(
                id = "run-42",
                status = GitHubWorkflowRunStatus.Running,
                jobs = listOf(
                    GitHubWorkflowJob(
                        id = "job-1",
                        name = "Build",
                        status = GitHubWorkflowRunStatus.Running,
                        currentStep = "Compile",
                        completedSteps = 2,
                        totalSteps = 5,
                    ),
                ),
            ),
        )
        val integration = GitHubActionsExecutorIntegration(client)
        val context = base.copy(taskRun = base.taskRun.copy(status = TaskRunStatus.Running, externalRunId = "run-42"))

        val execution = integration.reconcile(context)

        assertEquals(TaskRunStatus.Running, execution.status)
        assertEquals(2f / 5f, execution.progress)
        assertEquals("Build: Compile (3/5)", execution.progressMessage)
    }

    @Test
    fun multipleJobStepsAggregateAcrossJobs() = runBlocking {
        val base = context(TaskExecutor.GitHubAction("ci.yml"))
        val client = FakeGitHubActionsClient(
            reconciledRun = GitHubWorkflowRun(
                id = "run-42",
                status = GitHubWorkflowRunStatus.Running,
                jobs = listOf(
                    GitHubWorkflowJob(
                        id = "job-1",
                        name = "Build",
                        status = GitHubWorkflowRunStatus.Completed,
                        completedSteps = 4,
                        totalSteps = 4,
                    ),
                    GitHubWorkflowJob(
                        id = "job-2",
                        name = "Test",
                        status = GitHubWorkflowRunStatus.Running,
                        currentStep = "Unit tests",
                        completedSteps = 1,
                        totalSteps = 3,
                    ),
                ),
            ),
        )
        val integration = GitHubActionsExecutorIntegration(client)
        val context = base.copy(taskRun = base.taskRun.copy(status = TaskRunStatus.Running, externalRunId = "run-42"))

        val execution = integration.reconcile(context)

        assertEquals(5f / 7f, execution.progress)
        assertEquals("Test: Unit tests (2/3)", execution.progressMessage)
    }

    private fun context(executor: TaskExecutor): TaskExecutorContext {
        val taskId = TaskDefinitionId("ci")
        val repository = RepositoryRef("HereLiesAz", "haive", defaultBranch = "main")
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            repository = repository,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val task = TaskDefinition(
            id = taskId,
            name = "CI",
            objective = "Run CI",
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
        return TaskExecutorContext(project, definition, run, task, taskRun, executor, 10L)
    }
}

private class FakeGitHubActionsClient(
    private val dispatchedRun: GitHubWorkflowRun = GitHubWorkflowRun("run", GitHubWorkflowRunStatus.Queued),
    private val reconciledRun: GitHubWorkflowRun = GitHubWorkflowRun("run", GitHubWorkflowRunStatus.Running),
) : GitHubActionsClient {
    var lastDispatch: GitHubWorkflowDispatchRequest? = null
    var lastRepository: RepositoryRef? = null
    var lastRunId: String? = null

    override suspend fun dispatch(request: GitHubWorkflowDispatchRequest): GitHubWorkflowRun {
        lastDispatch = request
        return dispatchedRun
    }

    override suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun {
        lastRepository = repository
        lastRunId = runId
        return reconciledRun
    }
}
