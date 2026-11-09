package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hive that nests itself is refused at launch, and a child run's spend counts toward the run that nests it. */
class NestedWorkflowLaunchTest {
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun hive(id: String, nests: String? = null) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = id,
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("step"),
                name = "step",
                objective = "Step",
                roleId = null,
                executor = nests?.let { TaskExecutor.NestedWorkflow(WorkflowDefinitionId(it)) } ?: TaskExecutor.Deployment("production"),
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun service(persistence: InMemoryWorkflowPersistence) = WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(AgentProviderRegistry(emptyList(), inferenceSettings = InMemorySettings()), BuiltInRoles.all),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = BuiltInRoles.all,
    )

    private suspend fun launch(persistence: InMemoryWorkflowPersistence, definition: WorkflowDefinition) =
        service(persistence).launch(
            project = project,
            definition = definition,
            workflowRunId = WorkflowRunId("run-${definition.id.value}"),
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("tr-${it.value}") },
        )

    @Test
    fun aHiveThatNestsItselfIsRefusedBeforeAnythingIsStored() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()

        val failure = assertFailsWith<IllegalArgumentException> { launch(persistence, hive("loop", nests = "loop")) }

        assertTrue("cycle" in failure.message.orEmpty(), failure.message)
        assertNull(persistence.runs.get(WorkflowRunId("run-loop")), "nothing was started")
        assertTrue(persistence.projects.all().isEmpty())
    }

    @Test
    fun aCycleThroughAnotherHiveIsRefusedToo() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        // b nests a; launching a, which nests b, closes the loop.
        persistence.definitions.put(hive("b", nests = "a"))

        assertFailsWith<IllegalArgumentException> { launch(persistence, hive("a", nests = "b")) }
    }

    @Test
    fun aHiveThatNestsAnotherWithoutALoopLaunchesAsBefore() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        persistence.definitions.put(hive("leaf"))

        launch(persistence, hive("root", nests = "leaf"))

        assertEquals(WorkflowRunStatus.Created, persistence.runs.get(WorkflowRunId("run-root"))?.status)
    }

    @Test
    fun aChildRunsSpendIsReportedToTheNodeThatNestsIt() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = NestedNoSessionGateway()
        val engine = WorkflowEngine(gateway, BuiltInRoles.all)
        val client = WorkflowRunNestedWorkflowClient(
            persistence = persistence,
            engine = engine,
            coordinatorFactory = { WorkflowRuntimeCoordinator(persistence, engine, gateway) },
            nowEpochMillis = { 10L },
        )
        val child = WorkflowRun(
            id = WorkflowRunId("child-run"),
            projectId = project.id,
            workflowDefinitionId = WorkflowDefinitionId("leaf"),
            objective = "Child",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(
                TaskDefinitionId("a") to usedTask("a", TaskUsage(100, 10, 1.25)),
                TaskDefinitionId("b") to usedTask("b", TaskUsage(40, 5, 0.5, costUnknown = true)),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        persistence.runs.put(child)

        val reported = client.getRun(project, "child-run")

        // Before: the child's cost lived on the child run alone, so the parent's run cost cap never saw it.
        assertEquals(TaskUsage(inputTokens = 140, outputTokens = 15, costUsd = 1.75, costUnknown = true), reported.usage)
    }

    private fun usedTask(id: String, usage: TaskUsage) = TaskRun(
        id = TaskRunId("tr-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = TaskRunStatus.Completed,
        assignedRoleId = null,
        usage = usage,
    )
}

private class NestedNoSessionGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = com.hereliesaz.geministrator.domain.AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("unused")
    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = com.hereliesaz.geministrator.providers.ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = com.hereliesaz.geministrator.providers.ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}
