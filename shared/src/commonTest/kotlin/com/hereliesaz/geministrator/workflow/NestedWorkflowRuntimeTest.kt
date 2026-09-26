package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NestedWorkflowRuntimeTest {
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val parentTask = TaskDefinitionId("nest")
    private val childTask = TaskDefinitionId("deploy")

    @Test
    fun childRunStartsAndParentCompletesWhenChildCompletes() = runBlocking {
        val fixture = Fixture()
        val parent = fixture.parentDefinition(childDefinition())
        fixture.persistence.definitions.put(childDefinition())
        var state = fixture.launch(parent)

        state = fixture.cycle(parent, state)
        val parentTaskRun = state.run.taskRuns.getValue(parentTask)
        assertEquals(TaskRunStatus.Running, parentTaskRun.status)
        val childRunId = assertNotNull(parentTaskRun.externalRunId)
        val childRun = assertNotNull(fixture.persistence.runs.get(WorkflowRunId(childRunId)))
        assertEquals(WorkflowDefinitionId("child"), childRun.workflowDefinitionId)
        assertEquals(project.id, childRun.projectId)

        state = fixture.cycle(parent, state)
        assertEquals(TaskRunStatus.Running, state.run.taskRuns.getValue(parentTask).status)
        assertEquals(1, fixture.deployments.dispatches)

        fixture.deployments.outcome = TaskRunStatus.Completed
        state = fixture.cycle(parent, state)
        assertEquals(WorkflowRunStatus.Completed, fixture.persistence.runs.get(WorkflowRunId(childRunId))?.status)
        assertEquals(TaskRunStatus.Completed, state.run.taskRuns.getValue(parentTask).status)
        assertEquals(WorkflowRunStatus.Completed, state.run.status)
    }

    @Test
    fun childFailureFailsParent() = runBlocking {
        val fixture = Fixture()
        val parent = fixture.parentDefinition(childDefinition())
        fixture.persistence.definitions.put(childDefinition())
        var state = fixture.launch(parent)

        state = fixture.cycle(parent, state)
        state = fixture.cycle(parent, state)
        fixture.deployments.outcome = TaskRunStatus.Failed
        repeat(3) { state = fixture.cycle(parent, state) }

        val childRunId = WorkflowRunId(assertNotNull(state.run.taskRuns.getValue(parentTask).externalRunId))
        assertEquals(WorkflowRunStatus.Failed, fixture.persistence.runs.get(childRunId)?.status)
        assertEquals(TaskRunStatus.Failed, state.run.taskRuns.getValue(parentTask).status)
        assertEquals(WorkflowRunStatus.Failed, state.run.status)
    }

    @Test
    fun restartReconnectsToTheSameChildRun() = runBlocking {
        val fixture = Fixture()
        val parent = fixture.parentDefinition(childDefinition())
        fixture.persistence.definitions.put(childDefinition())
        var state = fixture.launch(parent)
        state = fixture.cycle(parent, state)
        state = fixture.cycle(parent, state)
        val childRunId = state.run.taskRuns.getValue(parentTask).externalRunId

        // A new runtime over the same persistence, as after an application restart.
        val restarted = Fixture(fixture.persistence, fixture.deployments, startClock = 1_000L)
        var resumed = restarted.coordinator.resume(state.run.id)
        resumed = restarted.cycle(parent, resumed)
        assertEquals(childRunId, resumed.run.taskRuns.getValue(parentTask).externalRunId)

        // A dispatch repeated for the same task attempt reattaches instead of starting a duplicate.
        val repeated = restarted.client.start(
            TaskExecutorContext(
                project = project,
                definition = parent,
                run = resumed.run,
                task = parent.tasks.single(),
                taskRun = resumed.run.taskRuns.getValue(parentTask),
                executor = parent.tasks.single().executor!!,
                nowEpochMillis = 0L,
            ),
            WorkflowDefinitionId("child"),
            null,
        )
        assertEquals(childRunId, repeated.id)
        assertEquals(2, fixture.persistence.runs.byProject(project.id).size)
        assertEquals(1, fixture.deployments.dispatches)

        restarted.deployments.outcome = TaskRunStatus.Completed
        repeat(2) { resumed = restarted.cycle(parent, resumed) }
        assertEquals(WorkflowRunStatus.Completed, resumed.run.status)
    }

    @Test
    fun humanApprovalInsideChildStaysPendingUntilApproved() = runBlocking {
        val fixture = Fixture()
        val child = WorkflowDefinition(
            id = WorkflowDefinitionId("child"),
            name = "Gated child",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("approve"),
                    name = "Approve",
                    objective = "Approve the release",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval("Approve release"),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        fixture.persistence.definitions.put(child)
        val parent = fixture.parentDefinition(child)
        var state = fixture.launch(parent)
        repeat(4) { state = fixture.cycle(parent, state) }

        val waiting = state.run.taskRuns.getValue(parentTask)
        assertEquals(TaskRunStatus.Running, waiting.status)
        assertTrue(waiting.awaitsNestedHumanApproval(), waiting.progressMessage)
        val childRunId = WorkflowRunId(assertNotNull(waiting.externalRunId))
        assertEquals(WorkflowRunStatus.AwaitingHuman, fixture.persistence.runs.get(childRunId)?.status)

        assertTrue(fixture.client.approvePendingHumanApproval(childRunId.value))
        repeat(2) { state = fixture.cycle(parent, state) }
        assertEquals(WorkflowRunStatus.Completed, fixture.persistence.runs.get(childRunId)?.status)
        assertEquals(WorkflowRunStatus.Completed, state.run.status)
        assertFalse(fixture.client.approvePendingHumanApproval(childRunId.value))
    }

    @Test
    fun depthLimitRefusesDeeperChild() = runBlocking {
        val fixture = Fixture(maxDepth = 1)
        fixture.persistence.definitions.put(childDefinition())
        val parent = fixture.parentDefinition(childDefinition())
        val context = fixture.context(parent, WorkflowRunId("nested-d1-outer-task-a1"))

        val refused = NestedWorkflowExecutorIntegration(fixture.client).dispatch(context)

        assertEquals(TaskRunStatus.Failed, refused.status)
        assertTrue(refused.progressMessage.orEmpty().contains("depth 2 exceeds the limit of 1"), refused.progressMessage)
        assertEquals(1, WorkflowRunNestedWorkflowClient.nestingDepth(context.run.id))
        assertEquals(0, fixture.persistence.runs.byProject(project.id).size)
    }

    @Test
    fun cycleAcrossDefinitionsIsRefused() = runBlocking {
        val fixture = Fixture()
        val a = WorkflowDefinitionId("a")
        val b = WorkflowDefinitionId("b")
        val definitionA = nestingDefinition(a, b)
        val definitionB = nestingDefinition(b, a)
        fixture.persistence.definitions.put(definitionA)
        fixture.persistence.definitions.put(definitionB)

        val refused = NestedWorkflowExecutorIntegration(fixture.client)
            .dispatch(fixture.context(definitionA, WorkflowRunId("run")))
        assertEquals(TaskRunStatus.Failed, refused.status)
        assertEquals("Nested workflow cycle refused: a -> b -> a", refused.progressMessage)

        val selfNesting = nestingDefinition(a, a)
        fixture.persistence.definitions.put(selfNesting)
        val selfRefused = NestedWorkflowExecutorIntegration(fixture.client)
            .dispatch(fixture.context(selfNesting, WorkflowRunId("run")))
        assertEquals(TaskRunStatus.Failed, selfRefused.status)
        assertEquals(0, fixture.persistence.runs.byProject(project.id).size)
    }

    private fun childDefinition() = WorkflowDefinition(
        id = WorkflowDefinitionId("child"),
        name = "Child",
        tasks = listOf(
            TaskDefinition(
                id = childTask,
                name = "Deploy",
                objective = "Deploy",
                roleId = null,
                executor = TaskExecutor.Deployment("staging"),
                retryPolicy = RetryPolicy(maxAttempts = 1),
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun nestingDefinition(id: WorkflowDefinitionId, nested: WorkflowDefinitionId) = WorkflowDefinition(
        id = id,
        name = id.value,
        tasks = listOf(
            TaskDefinition(parentTask, "Nest", "Run nested", roleId = null, executor = TaskExecutor.NestedWorkflow(nested)),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private inner class Fixture(
        val persistence: InMemoryWorkflowPersistence = InMemoryWorkflowPersistence(),
        val deployments: ControllableDeploymentIntegration = ControllableDeploymentIntegration(),
        maxDepth: Int = WorkflowRunNestedWorkflowClient.DEFAULT_MAX_DEPTH,
        startClock: Long = 10L,
    ) {
        private var clock = startClock
        private val gateway = NestedNoopGateway()
        private val engine = WorkflowEngine(gateway, BuiltInRoles.all)
        private lateinit var integrations: TaskExecutorIntegrationRegistry
        private fun newCoordinator() = WorkflowRuntimeCoordinator(persistence, engine, gateway, integrations)
        val client = WorkflowRunNestedWorkflowClient(persistence, engine, ::newCoordinator, { ++clock }, maxDepth)
        val coordinator: WorkflowRuntimeCoordinator

        init {
            integrations = TaskExecutorIntegrationRegistry(listOf(deployments, NestedWorkflowExecutorIntegration(client)))
            coordinator = newCoordinator()
        }

        fun parentDefinition(child: WorkflowDefinition) = WorkflowDefinition(
            id = WorkflowDefinitionId("parent"),
            name = "Parent",
            tasks = listOf(
                TaskDefinition(
                    id = parentTask,
                    name = "Nest",
                    objective = "Run the child workflow",
                    roleId = null,
                    executor = TaskExecutor.NestedWorkflow(child.id),
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )

        suspend fun launch(definition: WorkflowDefinition): WorkflowRuntimeState {
            val run = WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = WorkflowRunId("run-parent"),
                projectId = project.id,
                objective = "Ship",
                nowEpochMillis = ++clock,
                taskRunIdFactory = { TaskRunId("run-parent-${it.value}") },
            )
            val state = WorkflowRuntimeState(run)
            coordinator.persist(project, definition, state)
            return state
        }

        suspend fun cycle(definition: WorkflowDefinition, state: WorkflowRuntimeState): WorkflowRuntimeState =
            coordinator.cycle(project, definition, state, ++clock, ::artifactId)

        fun context(definition: WorkflowDefinition, runId: WorkflowRunId): TaskExecutorContext {
            val run = WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = runId,
                projectId = project.id,
                objective = "Ship",
                nowEpochMillis = 1L,
                taskRunIdFactory = { TaskRunId("${runId.value}-${it.value}") },
            )
            val task = definition.tasks.single()
            return TaskExecutorContext(project, definition, run, task, run.taskRuns.getValue(task.id), task.executor!!, 2L)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) =
        ArtifactId("${taskRun.id.value}:$index")
}

private class ControllableDeploymentIntegration : TaskExecutorIntegration {
    var dispatches = 0
    var outcome: TaskRunStatus = TaskRunStatus.Running

    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.Deployment

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        dispatches += 1
        return TaskExecutorExecution(TaskRunStatus.Running, externalRunId = "deploy-$dispatches")
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        TaskExecutorExecution(outcome, externalRunId = context.taskRun.externalRunId)
}

private class NestedNoopGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest) = ManagedSessionHandle(
        request.taskRequest.taskRunId,
        AgentProviderId("provider"),
        ProviderRunId("provider-run"),
    )

    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown

    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
