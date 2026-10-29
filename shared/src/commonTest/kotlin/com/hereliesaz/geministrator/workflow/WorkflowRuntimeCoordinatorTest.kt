package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowRuntimeCoordinatorTest {
    @Test
    fun awaitingHumanWithoutProviderHandlesIsPreserved() = runBlocking {
        val taskId = TaskDefinitionId("approval")
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "Approve",
                objective = "Approve release",
                roleId = null,
                executor = TaskExecutor.HumanApproval(),
            ),
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.AwaitingHuman,
            taskRuns = mapOf(
                taskId to taskRun(
                    taskId,
                    TaskRunStatus.AwaitingApproval,
                    TaskExecutor.HumanApproval(),
                ),
            ),
        )
        val fixture = fixture()

        val result = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run),
            nowEpochMillis = 20L,
            artifactIdFactory = ::artifactId,
        )

        assertEquals(run, result.run)
        assertEquals(WorkflowRunStatus.AwaitingHuman, result.run.status)
        assertEquals(TaskRunStatus.AwaitingApproval, result.run.taskRuns.getValue(taskId).status)
    }

    @Test
    fun providerFailureUsesFailurePolicyAndRedispatchesRetry() = runBlocking {
        val taskId = TaskDefinitionId("agent")
        val executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id)
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "Agent",
                objective = "Do work",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = executor,
            ),
        )
        val handle = handle(taskId)
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                taskId to taskRun(
                    taskId,
                    TaskRunStatus.Running,
                    executor,
                    provider = true,
                ),
            ),
        )
        val gateway = FakeManagedSessionGateway(status = ManagedSessionStatus.Failed)
        val fixture = fixture(gateway)

        val result = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run, mapOf(taskId to handle)),
            nowEpochMillis = 20L,
            artifactIdFactory = ::artifactId,
        )

        val retried = result.run.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Running, retried.status)
        assertEquals(2, retried.attempt)
        assertEquals(AgentProviderId("provider"), retried.assignedProviderId)
        assertEquals(ProviderRunId("provider-run-new"), retried.providerRunId)
    }

    @Test
    fun repeatedArtifactPollingPreservesOriginalCreationTimestamp() = runBlocking {
        val taskId = TaskDefinitionId("agent")
        val artifact = ArtifactRef(
            id = ArtifactId("artifact"),
            kind = ArtifactKind.CommandOutput,
            taskRunId = TaskRunId("task-run-agent"),
            label = "result",
            uri = "file://result",
            createdAtEpochMillis = 5L,
        )
        val executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id)
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "Agent",
                objective = "Do work",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = executor,
            ),
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                taskId to taskRun(
                    taskId,
                    TaskRunStatus.Running,
                    executor,
                    provider = true,
                ).copy(artifacts = listOf(artifact)),
            ),
        )
        val gateway = FakeManagedSessionGateway(
            status = ManagedSessionStatus.Running,
            artifacts = listOf(
                ProviderArtifact(
                    ArtifactKind.CommandOutput,
                    "result",
                    "file://result",
                ),
            ),
        )
        val fixture = fixture(gateway)

        val result = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run, mapOf(taskId to handle(taskId))),
            nowEpochMillis = 99L,
            artifactIdFactory = ::artifactId,
        )

        assertEquals(
            5L,
            result.run.taskRuns.getValue(taskId).artifacts.single().createdAtEpochMillis,
        )
        assertEquals(
            5L,
            fixture.persistence.artifacts.get(ArtifactId("artifact"))?.createdAtEpochMillis,
        )
    }

    @Test
    fun unsupportedSystemExecutorIsExplicitlyBlockedAndPersisted() = runBlocking {
        val taskId = TaskDefinitionId("ci")
        val executor = TaskExecutor.GitHubAction("ci.yml")
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "CI",
                objective = "Run CI",
                roleId = null,
                executor = executor,
            ),
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun(taskId, TaskRunStatus.Ready, executor)),
        )
        val fixture = fixture()

        val result = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run),
            nowEpochMillis = 20L,
            artifactIdFactory = ::artifactId,
        )

        val blocked = result.run.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Blocked, blocked.status)
        assertEquals(
            WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE,
            blocked.blockingReason?.code,
        )
        assertEquals(
            TaskRunStatus.Blocked,
            fixture.persistence.runs.get(run.id)?.taskRuns?.get(taskId)?.status,
        )
        assertEquals(20L, fixture.persistence.runs.get(run.id)?.updatedAtEpochMillis)
    }

    @Test
    fun configuredSystemExecutorDispatchesThenReconcilesToCompletion() = runBlocking {
        val taskId = TaskDefinitionId("ci")
        val executor = TaskExecutor.GitHubAction("ci.yml")
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "CI",
                objective = "Run CI",
                roleId = null,
                executor = executor,
            ),
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun(taskId, TaskRunStatus.Ready, executor)),
        )
        val integration = FakeSystemExecutorIntegration()
        val fixture = fixture(
            integrations = TaskExecutorIntegrationRegistry(listOf(integration)),
        )

        val dispatched = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run),
            nowEpochMillis = 20L,
            artifactIdFactory = ::artifactId,
        )
        val running = dispatched.run.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Running, running.status)
        assertEquals("external-ci-1", running.externalRunId)
        assertEquals(1, integration.dispatchCount)

        val completed = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = dispatched,
            nowEpochMillis = 30L,
            artifactIdFactory = ::artifactId,
        )
        val finished = completed.run.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Completed, finished.status)
        assertEquals(1f, finished.progress)
        assertEquals(1, integration.reconcileCount)
    }

    @Test
    fun concurrentCyclesDispatchSystemExecutorOnlyOnce() = runBlocking {
        val taskId = TaskDefinitionId("concurrent-ci")
        val executor = TaskExecutor.GitHubAction("ci.yml")
        val definition = definition(
            TaskDefinition(
                id = taskId,
                name = "Concurrent CI",
                objective = "Run CI once",
                roleId = null,
                executor = executor,
            ),
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun(taskId, TaskRunStatus.Ready, executor)),
        )
        val integration = FakeSystemExecutorIntegration(dispatchDelayMillis = 50L)
        val fixture = fixture(
            integrations = TaskExecutorIntegrationRegistry(listOf(integration)),
        )

        coroutineScope {
            List(8) {
                async {
                    fixture.coordinator.cycle(
                        project = project(),
                        definition = definition,
                        state = WorkflowRuntimeState(run),
                        nowEpochMillis = 20L,
                        artifactIdFactory = ::artifactId,
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, integration.dispatchCount)
        assertEquals(1, integration.reconcileCount)
        assertEquals(
            TaskRunStatus.Completed,
            fixture.persistence.runs.get(run.id)?.taskRuns?.get(taskId)?.status,
        )
    }

    @Test
    fun throwingDispatchFailsOnlyItsTaskAndKeepsEarlierDispatches() = runBlocking {
        val (definition, run) = twoGitHubActionRun()
        val integration = FakeSystemExecutorIntegration(throwOn = mapOf("boom.yml" to IllegalStateException("GitHub said no")))
        val fixture = fixture(integrations = TaskExecutorIntegrationRegistry(listOf(integration)))

        val result = fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run),
            nowEpochMillis = 20L,
            artifactIdFactory = ::artifactId,
        )

        val first = result.run.taskRuns.getValue(TaskDefinitionId("first"))
        assertEquals(TaskRunStatus.Running, first.status)
        assertEquals("external-ci-1", first.externalRunId)
        val second = result.run.taskRuns.getValue(TaskDefinitionId("second"))
        // The failure went through the retry policy instead of leaving the task Ready.
        assertEquals(TaskRunStatus.Retrying, second.status)
        assertEquals(2, second.attempt)
    }

    @Test
    fun dispatchesAlreadyStartedSurviveACycleThatEndsEarly() = runBlocking {
        val (definition, run) = twoGitHubActionRun()
        val integration = FakeSystemExecutorIntegration(
            throwOn = mapOf("boom.yml" to kotlinx.coroutines.CancellationException("stopped")),
        )
        val fixture = fixture(integrations = TaskExecutorIntegrationRegistry(listOf(integration)))

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            fixture.coordinator.cycle(
                project = project(),
                definition = definition,
                state = WorkflowRuntimeState(run),
                nowEpochMillis = 20L,
                artifactIdFactory = ::artifactId,
            )
        }
        assertEquals(
            "external-ci-1",
            fixture.persistence.runs.get(run.id)?.taskRuns?.get(TaskDefinitionId("first"))?.externalRunId,
        )

        integration.throwOn = emptyMap()
        fixture.coordinator.cycle(
            project = project(),
            definition = definition,
            state = WorkflowRuntimeState(run),
            nowEpochMillis = 30L,
            artifactIdFactory = ::artifactId,
        )
        assertEquals(1, integration.dispatchesOf("ok.yml"))
    }

    private fun twoGitHubActionRun(): Pair<WorkflowDefinition, WorkflowRun> {
        val tasks = listOf("first" to "ok.yml", "second" to "boom.yml").map { (id, workflow) ->
            TaskDefinition(
                id = TaskDefinitionId(id),
                name = id,
                objective = "Run $workflow",
                roleId = null,
                executor = TaskExecutor.GitHubAction(workflow),
            )
        }
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition-two-ci"),
            name = "Workflow",
            tasks = tasks,
            testDesignPolicy = TestDesignPolicy.None,
        )
        val run = run(
            definition = definition,
            status = WorkflowRunStatus.Running,
            taskRuns = tasks.associate { it.id to taskRun(it.id, TaskRunStatus.Ready, it.executor!!) },
        )
        return definition to run
    }

    private fun fixture(
        gateway: FakeManagedSessionGateway = FakeManagedSessionGateway(),
        integrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
    ): Fixture {
        val persistence = InMemoryWorkflowPersistence()
        val engine = WorkflowEngine(
            sessionGateway = gateway,
            roles = BuiltInRoles.all,
        )
        return Fixture(
            persistence,
            WorkflowRuntimeCoordinator(persistence, engine, gateway, integrations),
        )
    }

    private fun project() = Project(
        id = ProjectId("project"),
        name = "Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun definition(task: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("definition-${task.id.value}"),
        name = "Workflow",
        tasks = listOf(task),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun run(
        definition: WorkflowDefinition,
        status: WorkflowRunStatus,
        taskRuns: Map<TaskDefinitionId, TaskRun>,
    ) = WorkflowRun(
        id = WorkflowRunId("run-${definition.id.value}"),
        projectId = ProjectId("project"),
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = status,
        taskRuns = taskRuns,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun taskRun(
        taskId: TaskDefinitionId,
        status: TaskRunStatus,
        executor: TaskExecutor,
        provider: Boolean = false,
    ) = TaskRun(
        id = TaskRunId("task-run-${taskId.value}"),
        taskDefinitionId = taskId,
        status = status,
        assignedRoleId = (executor as? TaskExecutor.RoleAgent)?.roleId,
        assignedProviderId = if (provider) AgentProviderId("provider") else null,
        providerRunId = if (provider) ProviderRunId("provider-run") else null,
        executor = executor,
    )

    private fun handle(taskId: TaskDefinitionId) = ManagedSessionHandle(
        taskRunId = TaskRunId("task-run-${taskId.value}"),
        providerId = AgentProviderId("provider"),
        providerRunId = ProviderRunId("provider-run"),
    )

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(
        taskRun: TaskRun,
        artifact: ProviderArtifact,
        index: Int,
    ): ArtifactId = ArtifactId("artifact")

    private data class Fixture(
        val persistence: InMemoryWorkflowPersistence,
        val coordinator: WorkflowRuntimeCoordinator,
    )
}

private class FakeManagedSessionGateway(
    private val status: ManagedSessionStatus = ManagedSessionStatus.Unknown,
    private val artifacts: List<ProviderArtifact> = emptyList(),
) : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) =
        AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest) = ManagedSessionHandle(
        taskRunId = request.taskRequest.taskRunId,
        providerId = AgentProviderId("provider"),
        providerRunId = ProviderRunId("provider-run-new"),
    )

    override suspend fun status(handle: ManagedSessionHandle) = status

    override suspend fun message(
        handle: ManagedSessionHandle,
        message: String,
    ) = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle) = artifacts
}

private class FakeSystemExecutorIntegration(
    private val dispatchDelayMillis: Long = 0L,
    /** Workflow file -> what dispatching it throws. */
    var throwOn: Map<String, Throwable> = emptyMap(),
) : TaskExecutorIntegration {
    var dispatchCount = 0
    var reconcileCount = 0
    private val dispatchedWorkflows = mutableListOf<String>()

    fun dispatchesOf(workflow: String): Int = dispatchedWorkflows.count { it == workflow }

    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.GitHubAction

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        dispatchCount += 1
        val workflow = (context.executor as TaskExecutor.GitHubAction).workflow
        dispatchedWorkflows += workflow
        throwOn[workflow]?.let { throw it }
        if (dispatchDelayMillis > 0L) delay(dispatchDelayMillis)
        return TaskExecutorExecution(
            status = TaskRunStatus.Running,
            externalRunId = "external-ci-1",
            progress = 0.25f,
            progressMessage = "queued",
        )
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        reconcileCount += 1
        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            externalRunId = context.taskRun.externalRunId,
            progress = 1f,
            progressMessage = "complete",
        )
    }
}
