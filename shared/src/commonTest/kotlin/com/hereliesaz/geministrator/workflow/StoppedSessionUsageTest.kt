package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
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
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spend that arrives between the last poll and a stop still counts, and a session nobody follows is let go. */
class StoppedSessionUsageTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val executor = TaskExecutor.RoleAgent(engineer.id)
    private val job = TaskDefinitionId("job")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("stopped-usage"),
        name = "Stopped usage",
        tasks = listOf(TaskDefinition(id = job, name = "job", objective = "Do it", roleId = engineer.id, executor = executor)),
        testDesignPolicy = TestDesignPolicy.None,
    )
    private val handle = ManagedSessionHandle(TaskRunId("task-run-job"), AgentProviderId("provider"), ProviderRunId("session-1"))

    private fun running(usage: TaskUsage? = null) = WorkflowRun(
        id = WorkflowRunId("run-stopped"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            job to TaskRun(
                id = TaskRunId("task-run-job"),
                taskDefinitionId = job,
                status = TaskRunStatus.Running,
                assignedRoleId = engineer.id,
                assignedProviderId = handle.providerId,
                providerRunId = handle.providerRunId,
                executor = executor,
                usage = usage,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun coordinator(persistence: InMemoryWorkflowPersistence, gateway: StopUsageGateway, budget: Long? = null): WorkflowRuntimeCoordinator {
        val roles = BuiltInRoles.all.map { if (it.id == engineer.id) it.copy(tokenBudget = budget) else it }
        return WorkflowRuntimeCoordinator(
            persistence = persistence,
            engine = WorkflowEngine(sessionGateway = gateway, roles = roles),
            sessionGateway = gateway,
        )
    }

    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")

    @Test
    fun cancellingARunCountsWhatItsSessionReportedAfterTheLastPoll() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = StopUsageGateway()
        gateway.usageOnCancel = ManagedSessionUsage(inputTokens = 90, outputTokens = 10, costUsd = 0.2)
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        val live = running()
        persistence.runs.put(live)

        val result = coordinator(persistence, gateway).cancel(project, definition, WorkflowRuntimeState(live, mapOf(job to handle)), 20L)

        assertEquals(WorkflowRunStatus.Cancelled, result.run.status)
        // Before: the pending report was dropped with the handle, so the stopped session's spend was never counted.
        assertEquals(100L, result.run.taskRuns.getValue(job).usage?.totalTokens)
        assertEquals(0.2, result.run.taskRuns.getValue(job).usage?.costUsd)
        assertEquals(100L, persistence.runs.get(live.id)?.taskRuns?.getValue(job)?.usage?.totalTokens, "and it is saved")
        val recorded = persistence.events.forRun(live.id).filterIsInstance<ProviderUsageRecorded>().single()
        assertEquals(90L, recorded.inputTokens, "the hourly cap sees it as well")
        assertTrue(result.handles.isEmpty())
        assertEquals(listOf(handle), gateway.released, "the gateway forgets a session that was stopped")
    }

    @Test
    fun aTaskParkedForItsRolesBudgetKeepsWhatItSpentWhileBeingStopped() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = StopUsageGateway()
        // The budget is spent by the poll; the stop then finds 300 more tokens the session reported.
        gateway.pollUsage = ManagedSessionUsage(inputTokens = 100, outputTokens = 0)
        gateway.usageOnCancel = ManagedSessionUsage(inputTokens = 300, outputTokens = 0)
        val live = running()
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(live)

        val next = coordinator(persistence, gateway, budget = 50L)
            .cycle(project, definition, WorkflowRuntimeState(live, mapOf(job to handle)), 20L, ::artifactId)

        assertEquals(TaskRunStatus.Blocked, next.run.taskRuns.getValue(job).status)
        assertEquals(400L, next.run.taskRuns.getValue(job).usage?.totalTokens, "the role's budget sees what the stopped session spent")
    }

    @Test
    fun aFinishedTasksSessionIsReleasedOnceTheRunStopsFollowingIt() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val gateway = StopUsageGateway()
        gateway.sessionStatus = ManagedSessionStatus.Completed
        val live = running()
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(live)

        val next = coordinator(persistence, gateway)
            .cycle(project, definition, WorkflowRuntimeState(live, mapOf(job to handle)), 20L, ::artifactId)

        assertEquals(TaskRunStatus.Completed, next.run.taskRuns.getValue(job).status)
        assertTrue(next.handles.isEmpty())
        assertEquals(listOf(handle), gateway.released, "the gateway is told it can forget the finished session")
    }
}

private class StopUsageGateway : ManagedSessionGateway {
    var sessionStatus: ManagedSessionStatus = ManagedSessionStatus.Running

    /** Handed out by the first usage read (the cycle's poll). */
    var pollUsage: ManagedSessionUsage? = null

    /** Appears when the session is cancelled: spend between the last poll and the stop. */
    var usageOnCancel: ManagedSessionUsage? = null
    private var pending: ManagedSessionUsage? = null
    val released = mutableListOf<ManagedSessionHandle>()
    private var polled = false

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("not used")
    override suspend fun status(handle: ManagedSessionHandle) = sessionStatus
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        pending = usageOnCancel
        return ProviderActionResult.Accepted
    }

    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
    override suspend fun release(handle: ManagedSessionHandle) {
        released += handle
    }

    override suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? {
        if (!polled && pollUsage != null) {
            polled = true
            return pollUsage
        }
        return pending.also { pending = null }
    }
}
