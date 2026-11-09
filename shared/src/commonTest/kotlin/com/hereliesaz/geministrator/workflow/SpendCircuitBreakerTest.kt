package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.SpendCapKind
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendCircuitBreakerTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val executor = TaskExecutor.RoleAgent(engineer.id)
    private val working = TaskDefinitionId("working")
    private val waiting = TaskDefinitionId("waiting")

    // ---- Role token budget ----

    @Test
    fun aRoleThatSpendsItsBudgetStopsItsRunningTaskAndStartsNoMore() = runTest {
        val gateway = UsageGateway()
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 80, outputTokens = 40)
        val persistence = InMemoryWorkflowPersistence()
        val coordinator = coordinator(persistence, gateway, roles(budget = 100))
        val definition = definition(task(working), task(waiting))
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"),
            taskRun(waiting, TaskRunStatus.Ready),
        )

        val result = coordinator.cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)

        val stopped = result.run.taskRuns.getValue(working)
        assertEquals(TaskRunStatus.Blocked, stopped.status)
        assertEquals(ROLE_BUDGET_SPENT, stopped.blockingReason?.code)
        assertEquals(2, stopped.attempt, "the stopped session's work resumes as a fresh attempt")
        assertNull(stopped.providerRunId)
        assertEquals(120L, stopped.usage?.totalTokens, "what it spent stays on the record")
        assertEquals(listOf(ProviderRunId("run-working")), gateway.cancelled.map { it.providerRunId })

        val held = result.run.taskRuns.getValue(waiting)
        assertEquals(TaskRunStatus.Blocked, held.status)
        assertEquals(ROLE_BUDGET_SPENT, held.blockingReason?.code)
        assertEquals(0, gateway.created, "a role with no budget left starts nothing")
        assertTrue(result.handles.isEmpty())
    }

    @Test
    fun beingParkedForASpendLimitDoesNotUseUpATasksRetryBudget() = runTest {
        val retryPolicy = RetryPolicy(maxAttempts = 2)
        fun decisionAfterAFailure(taskRun: TaskRun) = FailurePolicyEvaluator.decide(
            taskRun = taskRun.copy(status = TaskRunStatus.Failed),
            retryPolicy = retryPolicy,
            escalationPolicy = EscalationPolicy.FailWorkflow,
            reason = RetryReason.ProviderFailure,
        )

        // Stopped mid-run because its role spent its budget.
        val gateway = UsageGateway()
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 80, outputTokens = 40)
        val definition = definition(task(working), task(waiting))
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"),
            taskRun(waiting, TaskRunStatus.Ready),
        )
        val parkedForBudget = coordinator(InMemoryWorkflowPersistence(), gateway, roles(budget = 100))
            .cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)
            .run.taskRuns.getValue(working)
        assertEquals(2, parkedForBudget.attempt, "a parked task still starts a fresh attempt (its approval gates are keyed by it)")
        // The first real failure after the hold is its first failure, so it is retried.
        assertEquals(FailureDecision.Retry(3), decisionAfterAFailure(parkedForBudget))

        // Stopped by a spend cap and lifted.
        val paused = pauseRunForSpendCap(run, SpendCapTrip(SpendCapKind.RunCost, 1.0, 2.0), setOf(working), 15L)
        val resumed = liftSpendPause(paused, 40L).taskRuns.getValue(working)
        assertEquals(2, resumed.attempt)
        assertEquals(FailureDecision.Retry(3), decisionAfterAFailure(resumed))

        // A task that really failed once and is then retried has used that retry.
        assertEquals(FailureDecision.FailWorkflow, decisionAfterAFailure(taskRun(working, TaskRunStatus.Running).copy(attempt = 2)))
    }

    @Test
    fun raisingTheBudgetReleasesTheRolesTasks() = runTest {
        val gateway = UsageGateway()
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 80, outputTokens = 40)
        val persistence = InMemoryWorkflowPersistence()
        val definition = definition(task(working), task(waiting))
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"),
            taskRun(waiting, TaskRunStatus.Ready),
        )
        val stopped = coordinator(persistence, gateway, roles(budget = 100))
            .cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)
        // A budget-held task is not released by readiness alone.
        val idle = coordinator(persistence, gateway, roles(budget = 100))
            .cycle(project(), definition, stopped, 30L, ::artifactId)
        assertEquals(TaskRunStatus.Blocked, idle.run.taskRuns.getValue(waiting).status)

        // The keeper raises the budget: the runtime is rebuilt with the edited role.
        val resumed = coordinator(persistence, gateway, roles(budget = 1_000))
            .cycle(project(), definition, idle, 40L, ::artifactId)

        assertEquals(TaskRunStatus.Running, resumed.run.taskRuns.getValue(working).status)
        assertEquals(TaskRunStatus.Running, resumed.run.taskRuns.getValue(waiting).status)
        assertEquals(2, gateway.created)
    }

    @Test
    fun aRoleWithoutABudgetIsNeverStopped() = runTest {
        val gateway = UsageGateway()
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val definition = definition(task(working))
        val run = run(definition, taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"))

        val result = coordinator(InMemoryWorkflowPersistence(), gateway, roles(budget = null))
            .cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)

        assertEquals(TaskRunStatus.Running, result.run.taskRuns.getValue(working).status)
        assertTrue(gateway.cancelled.isEmpty())
    }

    // ---- The keeper's pause ----

    @Test
    fun aKeeperPauseStartsNothingNewAndCancelsNothingUntilItIsCleared() = runTest {
        val gateway = UsageGateway()
        val definition = definition(task(working), task(waiting))
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"),
            taskRun(waiting, TaskRunStatus.Ready),
        ).copy(keeperPause = com.hereliesaz.geministrator.domain.WorkflowKeeperPause(pausedAtEpochMillis = 10L))
        val coordinator = coordinator(InMemoryWorkflowPersistence(), gateway, roles(budget = null))

        // The control: the same hive without the pause starts the waiting creature, so the pause is what held it.
        val controlGateway = UsageGateway()
        coordinator(InMemoryWorkflowPersistence(), controlGateway, roles(budget = null)).cycle(
            project(), definition,
            WorkflowRuntimeState(run.copy(keeperPause = null), mapOf(working to handle(working, "run-working"))), 20L, ::artifactId,
        )
        assertEquals(1, controlGateway.created, "without a pause the Ready creature starts")

        val paused = coordinator.cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)

        assertEquals(TaskRunStatus.Running, paused.run.taskRuns.getValue(working).status, "what runs keeps running")
        assertEquals(TaskRunStatus.Ready, paused.run.taskRuns.getValue(waiting).status, "nothing new starts")
        assertEquals(0, gateway.created)
        assertTrue(gateway.cancelled.isEmpty(), "nothing is cancelled")
        assertNotNull(paused.run.keeperPause)

        val resumed = coordinator.cycle(
            project(), definition,
            // As the runtime does it: the cleared pause is a newer run, so it wins over the persisted one.
            WorkflowRuntimeState(paused.run.copy(keeperPause = null, updatedAtEpochMillis = 25L), paused.handles), 30L, ::artifactId,
        )
        assertEquals(1, gateway.created, "resuming starts what waited")
        assertTrue(resumed.run.taskRuns.getValue(waiting).status != TaskRunStatus.Ready, "it is no longer waiting to start")
    }

    @Test
    fun rewindingACreatureRestartsItAndWhatFollowsWhileAnUnrelatedOneKeepsRunning() = runTest {
        val gateway = UsageGateway()
        val other = TaskDefinitionId("other")
        val definition = definition(task(working), task(waiting, dependsOn = working), task(other))
        val spent = TaskUsage(inputTokens = 5, outputTokens = 5)
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working", usage = spent),
            taskRun(waiting, TaskRunStatus.Blocked),
            taskRun(other, TaskRunStatus.Running, providerRunId = "run-other"),
        )
        val state = WorkflowRuntimeState(
            run,
            mapOf(working to handle(working, "run-working"), other to handle(other, "run-other")),
        )

        val result = coordinator(InMemoryWorkflowPersistence(), gateway, roles(budget = null))
            .rewind(project(), definition, state, working, 20L)

        val again = result.run.taskRuns.getValue(working)
        assertEquals(2, again.attempt, "a new attempt")
        assertEquals(TaskRunStatus.Ready, again.status)
        assertNull(again.providerRunId)
        assertEquals(spent, again.usage, "what it spent stays on the record")
        assertTrue(again.id != run.taskRuns.getValue(working).id, "its old attempt stays as history")
        assertEquals(2, result.run.taskRuns.getValue(waiting).attempt, "what follows it starts again too")
        assertEquals(TaskRunStatus.Running, result.run.taskRuns.getValue(other).status, "the unrelated creature keeps running")
        assertEquals(listOf(ProviderRunId("run-working")), gateway.cancelled.map { it.providerRunId }, "only the rewound session is stopped")
        assertEquals(setOf(other), result.handles.keys)
    }

    // ---- Spend caps ----

    @Test
    fun reachingTheRunCostCapStopsTheRunUntilTheKeeperLiftsIt() = runTest {
        val gateway = UsageGateway()
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 10, outputTokens = 10, costUsd = 2.0)
        val persistence = InMemoryWorkflowPersistence()
        val definition = definition(task(working), task(waiting, dependsOn = working))
        val run = run(
            definition,
            taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"),
            taskRun(waiting, TaskRunStatus.Blocked),
        )
        val coordinator = coordinator(persistence, gateway, roles(budget = null), SpendCaps(runCostCapUsd = 1.0))

        val result = coordinator.cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)

        val pause = assertNotNull(result.run.spendPause)
        assertEquals(SpendCapKind.RunCost, pause.kind)
        assertEquals(1.0, pause.capUsd)
        assertEquals(2.0, pause.spentUsd)
        assertEquals(WorkflowRunStatus.AwaitingHuman, result.run.status)
        assertTrue(result.run.taskRuns.values.all { it.blockingReason?.code == SPEND_CAP_STOP })
        assertEquals(listOf(ProviderRunId("run-working")), gateway.cancelled.map { it.providerRunId })
        assertTrue(result.handles.isEmpty())
        assertEquals(result.run, persistence.runs.get(run.id), "the stop is durable")

        // While stopped, nothing starts.
        val stillStopped = coordinator.cycle(project(), definition, result, 30L, ::artifactId)
        assertEquals(result.run, stillStopped.run)
        assertEquals(0, gateway.created)

        val lifted = liftSpendPause(result.run, 40L)
        assertNull(lifted.spendPause)
        assertEquals(WorkflowRunStatus.Running, lifted.status)
        assertEquals(TaskRunStatus.Retrying, lifted.taskRuns.getValue(working).status)
        assertEquals(2, lifted.taskRuns.getValue(working).attempt)
        assertEquals(TaskRunStatus.Blocked, lifted.taskRuns.getValue(waiting).status)
        assertEquals("WAITING", lifted.taskRuns.getValue(waiting).blockingReason?.code)
    }

    @Test
    fun usageWithoutACostNeverTripsACap() = runTest {
        val gateway = UsageGateway()
        // A keyless or token-only provider: plenty of tokens, no cost.
        gateway.pending[ProviderRunId("run-working")] = ManagedSessionUsage(inputTokens = 500_000, outputTokens = 500_000)
        val persistence = InMemoryWorkflowPersistence()
        persistence.projects.put(project())
        val definition = definition(task(working))
        val run = run(definition, taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"))
        val caps = SpendCaps(runCostCapUsd = 0.01, hourlySpendCapUsd = 0.01)

        val result = coordinator(persistence, gateway, roles(budget = null), caps)
            .cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), 20L, ::artifactId)

        assertNull(result.run.spendPause)
        assertEquals(TaskRunStatus.Running, result.run.taskRuns.getValue(working).status)
        assertTrue(gateway.cancelled.isEmpty())
        assertNull(runCostTrip(run.copy(taskRuns = mapOf(working to taskRun(working, TaskRunStatus.Running, usage = TaskUsage(10, 10, null)))), caps))
    }

    @Test
    fun hourlySpendAcrossRunsStopsTheRun() = runTest {
        val now = 10 * ONE_HOUR_MILLIS
        val persistence = InMemoryWorkflowPersistence()
        persistence.projects.put(project())
        // Another run on this device spent $3 ten minutes ago, and $100 two hours ago.
        val other = WorkflowRun(
            id = WorkflowRunId("other"),
            projectId = ProjectId("project"),
            workflowDefinitionId = WorkflowDefinitionId("other"),
            objective = "Other",
            status = WorkflowRunStatus.Completed,
            taskRuns = emptyMap(),
            createdAtEpochMillis = now - 2 * ONE_HOUR_MILLIS,
            updatedAtEpochMillis = now - 600_000L,
        )
        persistence.runs.put(other)
        persistence.events.append(usageEvent(other.id, costUsd = 3.0, at = now - 600_000L))
        persistence.events.append(usageEvent(other.id, costUsd = 100.0, at = now - 2 * ONE_HOUR_MILLIS))
        persistence.events.append(usageEvent(other.id, costUsd = null, at = now - 60_000L))
        assertEquals(3.0, hourlySpendUsd(persistence, now))

        val gateway = UsageGateway()
        val definition = definition(task(working))
        val run = run(definition, taskRun(working, TaskRunStatus.Running, providerRunId = "run-working"))
        val result = coordinator(persistence, gateway, roles(budget = null), SpendCaps(hourlySpendCapUsd = 2.5))
            .cycle(project(), definition, WorkflowRuntimeState(run, mapOf(working to handle(working, "run-working"))), now, ::artifactId)

        val pause = assertNotNull(result.run.spendPause)
        assertEquals(SpendCapKind.HourlySpend, pause.kind)
        assertEquals(3.0, pause.spentUsd)
    }

    @Test
    fun usdFormatsToTheCent() {
        assertEquals("\$2.50", formatUsd(2.5))
        assertEquals("\$0.07", formatUsd(0.0701))
        assertEquals("\$12.00", formatUsd(12.0))
    }

    // ---- Fixtures ----

    private fun roles(budget: Long?): List<RoleDefinition> =
        BuiltInRoles.all.map { if (it.id == engineer.id) it.copy(tokenBudget = budget) else it }

    private fun coordinator(
        persistence: InMemoryWorkflowPersistence,
        gateway: UsageGateway,
        roles: List<RoleDefinition>,
        caps: SpendCaps = SpendCaps(),
    ) = WorkflowRuntimeCoordinator(
        persistence = persistence,
        engine = WorkflowEngine(sessionGateway = gateway, roles = roles),
        sessionGateway = gateway,
        spendCaps = { caps },
    )

    private fun task(id: TaskDefinitionId, dependsOn: TaskDefinitionId? = null) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = engineer.id,
        dependsOn = setOfNotNull(dependsOn),
        executor = executor,
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("spend"),
        name = "Spend",
        tasks = tasks.toList(),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun run(definition: WorkflowDefinition, vararg taskRuns: TaskRun) = WorkflowRun(
        id = WorkflowRunId("run-spend"),
        projectId = ProjectId("project"),
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = taskRuns.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun taskRun(
        id: TaskDefinitionId,
        status: TaskRunStatus,
        providerRunId: String? = null,
        usage: TaskUsage? = null,
    ) = TaskRun(
        id = TaskRunId("task-run-${id.value}"),
        taskDefinitionId = id,
        status = status,
        assignedRoleId = engineer.id,
        assignedProviderId = providerRunId?.let { AgentProviderId("provider") },
        providerRunId = providerRunId?.let(::ProviderRunId),
        blockingReason = if (status == TaskRunStatus.Blocked) {
            com.hereliesaz.geministrator.domain.BlockingReason("WAITING", "Waiting for task dependencies.")
        } else {
            null
        },
        executor = executor,
        usage = usage,
    )

    private fun handle(id: TaskDefinitionId, providerRunId: String) = ManagedSessionHandle(
        taskRunId = TaskRunId("task-run-${id.value}"),
        providerId = AgentProviderId("provider"),
        providerRunId = ProviderRunId(providerRunId),
    )

    private fun usageEvent(runId: WorkflowRunId, costUsd: Double?, at: Long) = ProviderUsageRecorded(
        workflowRunId = runId,
        taskDefinitionId = working,
        providerId = AgentProviderId("provider"),
        providerRunId = ProviderRunId("elsewhere"),
        inputTokens = 10,
        outputTokens = 10,
        costUsd = costUsd,
        occurredAtEpochMillis = at,
    )

    private fun project() = Project(
        id = ProjectId("project"),
        name = "Project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) = ArtifactId("artifact")
}

/** Sessions that stay Running and report each queued usage once; records creates and cancels. */
private class UsageGateway : ManagedSessionGateway {
    val pending = mutableMapOf<ProviderRunId, ManagedSessionUsage>()
    val cancelled = mutableListOf<ManagedSessionHandle>()
    var created = 0

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        created += 1
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("provider"),
            providerRunId = ProviderRunId("created-$created"),
        )
    }

    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelled += handle
        return ProviderActionResult.Accepted
    }

    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()

    override suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? =
        pending.remove(handle.providerRunId)
}
