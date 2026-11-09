package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
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
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the runtime does with provider sessions at the edges: a cycle cancelled or failed right after it
 * started sessions, a provider that will not stop a session, and a task parked for its role's budget.
 */
class SessionLifecycleTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val executor = TaskExecutor.RoleAgent(engineer.id)
    private val job = TaskDefinitionId("job")
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun task(id: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = engineer.id,
        executor = executor,
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("sessions"),
        name = "Sessions",
        tasks = tasks.toList(),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun taskRun(id: TaskDefinitionId, status: TaskRunStatus, providerRunId: String? = null) = TaskRun(
        id = TaskRunId("task-run-${id.value}"),
        taskDefinitionId = id,
        status = status,
        assignedRoleId = engineer.id,
        assignedProviderId = providerRunId?.let { AgentProviderId("provider") },
        providerRunId = providerRunId?.let(::ProviderRunId),
        executor = executor,
        blockingReason = if (status == TaskRunStatus.Blocked) BlockingReason("WAITING", "Waiting.") else null,
    )

    private fun run(
        definition: WorkflowDefinition,
        status: WorkflowRunStatus = WorkflowRunStatus.Running,
        vararg taskRuns: TaskRun,
    ) = WorkflowRun(
        id = WorkflowRunId("run-sessions"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = status,
        taskRuns = taskRuns.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun handle(id: TaskDefinitionId, providerRunId: String) = ManagedSessionHandle(
        taskRunId = TaskRunId("task-run-${id.value}"),
        providerId = AgentProviderId("provider"),
        providerRunId = ProviderRunId(providerRunId),
    )

    private fun coordinator(
        persistence: WorkflowPersistence,
        gateway: LifecycleGateway,
        roles: List<RoleDefinition> = BuiltInRoles.all,
    ) = WorkflowRuntimeCoordinator(
        persistence = persistence,
        engine = WorkflowEngine(sessionGateway = gateway, roles = roles),
        sessionGateway = gateway,
    )

    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")

    // ---- A cycle that is cancelled or fails right after it started sessions ----

    @Test
    fun aCycleCancelledWhileSavingWhatItStartedStillRecordsTheNewSessions() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        persistence.beforeRunPut = { run ->
            if (run.taskRuns[job]?.providerRunId != null) {
                saving.complete(Unit)
                saved.await()
            }
        }
        val start = run(definition, WorkflowRunStatus.Created, taskRun(job, TaskRunStatus.Ready))

        val cycle = launch {
            coordinator(persistence, gateway).cycle(project, definition, WorkflowRuntimeState(start), 20L, ::artifactId)
        }
        saving.await()
        // The keeper closes the app (the runtime's scope is cancelled) while the run that holds the new session is being saved.
        cycle.cancel()
        saved.complete(Unit)
        cycle.join()

        assertEquals(1, gateway.created)
        val recorded = persistence.inner.runs.get(start.id)?.taskRuns?.get(job)?.providerRunId
        // Before: neither was true, so a reopened runtime saw a Ready task and started it a second time.
        assertTrue(
            recorded == ProviderRunId("created-1") || gateway.cancelled.any { it.providerRunId == ProviderRunId("created-1") },
            "the session the cycle started is recorded on the run or stopped",
        )
    }

    @Test
    fun aRunThatCannotBeSavedAfterStartingSessionsStopsThem() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        persistence.beforeRunPut = { run ->
            if (run.taskRuns[job]?.providerRunId != null) error("the store is full")
        }
        val start = run(definition, WorkflowRunStatus.Created, taskRun(job, TaskRunStatus.Ready))

        assertFailsWith<IllegalStateException> {
            coordinator(persistence, gateway).cycle(project, definition, WorkflowRuntimeState(start), 20L, ::artifactId)
        }

        // The next cycle finds the task Ready and starts it again, so the first session must not be left running.
        assertEquals(listOf(ProviderRunId("created-1")), gateway.cancelled.map { it.providerRunId })
    }

    // ---- A provider that will not stop a session ----

    @Test
    fun aSessionTheProviderRejectsStoppingIsRecordedAndAskedAgain() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        gateway.cancelResult = { ProviderActionResult.Rejected("session is busy") }
        val finished = run(definition, WorkflowRunStatus.Cancelled, taskRun(job, TaskRunStatus.Cancelled, "session-1"))
        val coordinator = coordinator(persistence, gateway)

        val first = coordinator.cycle(
            project, definition, WorkflowRuntimeState(finished, mapOf(job to handle(job, "session-1"))), 20L, ::artifactId,
        )

        // Before: the rejection was ignored and the handle dropped, so the session billed on with nothing to say so.
        assertEquals(setOf(job), first.handles.keys, "the session stays on the run to be stopped again")
        val failure = persistence.inner.events.forRun(finished.id).filterIsInstance<ExternalRunCancelFailed>().single()
        assertEquals("session-1", failure.externalRunId)
        assertTrue("session is busy" in failure.reason)

        gateway.cancelResult = { ProviderActionResult.Accepted }
        val second = coordinator.cycle(project, definition, first, 30L, ::artifactId)

        assertTrue(second.handles.isEmpty(), "once it stops, it is let go")
        assertEquals(2, gateway.cancelAttempts)
    }

    @Test
    fun aCancelThatThrowsIsRecordedAndTheHandleKept() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        gateway.cancelResult = { error("provider unreachable") }
        val spendStopped = run(definition, WorkflowRunStatus.Running, taskRun(job, TaskRunStatus.Running, "session-1"))
        val coordinator = coordinator(persistence, gateway)

        val result = coordinator.cancel(project, definition, WorkflowRuntimeState(spendStopped, mapOf(job to handle(job, "session-1"))), 20L)

        assertEquals(WorkflowRunStatus.Cancelled, result.run.status)
        assertEquals(setOf(job), result.handles.keys)
        assertTrue(
            persistence.inner.events.forRun(spendStopped.id).filterIsInstance<ExternalRunCancelFailed>().single().reason
                .contains("provider unreachable"),
        )
    }

    @Test
    fun aSessionThatNeverStopsIsGivenUpOnAfterAFewTries() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        gateway.cancelResult = { ProviderActionResult.Rejected("not supported") }
        val finished = run(definition, WorkflowRunStatus.Cancelled, taskRun(job, TaskRunStatus.Cancelled, "session-1"))
        val coordinator = coordinator(persistence, gateway)

        var state = WorkflowRuntimeState(finished, mapOf(job to handle(job, "session-1")))
        var cycles = 0
        while (state.handles.isNotEmpty() && cycles < 50) {
            state = coordinator.cycle(project, definition, state, 20L + cycles, ::artifactId)
            cycles += 1
        }

        assertTrue(state.handles.isEmpty(), "a provider that cannot cancel does not keep the run cycling for ever")
        assertEquals(5, cycles)
        assertEquals(5, persistence.inner.events.forRun(finished.id).filterIsInstance<ExternalRunCancelFailed>().size)
    }

    // ---- A task parked for its role's budget ----

    @Test
    fun aParkedTaskIsNotFailedByTheSessionItWasParkedFrom() = runBlocking<Unit> {
        val definition = definition(task(job))
        val persistence = FaultInjectingPersistence()
        val gateway = LifecycleGateway()
        gateway.usage[ProviderRunId("session-1")] = ManagedSessionUsage(inputTokens = 80, outputTokens = 40)
        val budgeted = BuiltInRoles.all.map { if (it.id == engineer.id) it.copy(tokenBudget = 100L) else it }
        val coordinator = coordinator(persistence, gateway, budgeted)
        // The keeper has paused the hive: nothing new starts, so the dispatch step that used to drop the handle does not run.
        val paused = run(definition, WorkflowRunStatus.Running, taskRun(job, TaskRunStatus.Running, "session-1"))
            .copy(keeperPause = com.hereliesaz.geministrator.domain.WorkflowKeeperPause(pausedAtEpochMillis = 5L))

        val parked = coordinator.cycle(
            project, definition, WorkflowRuntimeState(paused, mapOf(job to handle(job, "session-1"))), 20L, ::artifactId,
        )

        assertEquals(TaskRunStatus.Blocked, parked.run.taskRuns.getValue(job).status)
        assertEquals(ROLE_BUDGET_SPENT, parked.run.taskRuns.getValue(job).blockingReason?.code)
        assertTrue(parked.handles.isEmpty(), "the cancelled session is no longer the task's")

        // The cancelled session now reports as failed; the parked task must stay parked.
        gateway.sessionStatus = ManagedSessionStatus.Failed
        val next = coordinator.cycle(project, definition, parked, 30L, ::artifactId)

        assertEquals(TaskRunStatus.Blocked, next.run.taskRuns.getValue(job).status)
        assertNotNull(next.run.taskRuns.getValue(job).blockingReason)
    }
}

/** Sessions with a configurable status and cancel; records every create and cancel. */
private class LifecycleGateway : ManagedSessionGateway {
    var created = 0
    var cancelAttempts = 0
    val cancelled = mutableListOf<ManagedSessionHandle>()
    val usage = mutableMapOf<ProviderRunId, ManagedSessionUsage>()
    var sessionStatus: ManagedSessionStatus = ManagedSessionStatus.Running
    var cancelResult: () -> ProviderActionResult = { ProviderActionResult.Accepted }

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        created += 1
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("provider"),
            providerRunId = ProviderRunId("created-$created"),
        )
    }

    override suspend fun status(handle: ManagedSessionHandle) = sessionStatus

    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelAttempts += 1
        val result = cancelResult()
        if (result == ProviderActionResult.Accepted) cancelled += handle
        return result
    }

    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()

    override suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? = usage.remove(handle.providerRunId)
}
