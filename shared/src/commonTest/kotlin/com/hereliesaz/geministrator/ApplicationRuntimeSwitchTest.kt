package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
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
import com.hereliesaz.geministrator.domain.WorkflowKeeperPause
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.FaultInjectingPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Loading and switching: a cancelled caller is not a failed load, a run left behind stops spending, and a
 * decision is built on the newest copy of the run and settles the gate it belongs to.
 */
class ApplicationRuntimeSwitchTest {
    private val gate = TaskDefinitionId("gate")
    private val work = TaskDefinitionId("work")
    private val approvalHive = WorkflowDefinition(
        id = WorkflowDefinitionId("approval-hive"),
        name = "Approval",
        tasks = listOf(
            TaskDefinition(
                id = gate,
                name = "Gate",
                objective = "Approve the start",
                roleId = null,
                executor = TaskExecutor.HumanApproval("Approve the start"),
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )
    private val workHive = WorkflowDefinition(
        id = WorkflowDefinitionId("work-hive"),
        name = "Work",
        tasks = listOf(
            TaskDefinition(
                id = work,
                name = "Work",
                objective = "Do the work",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun project(id: String) = Project(ProjectId(id), id, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    /** A run waiting on the keeper at its approval step. */
    private fun waitingRun(project: Project, updatedAt: Long = 1L) = WorkflowRun(
        id = WorkflowRunId("run-${project.id.value}"),
        projectId = project.id,
        workflowDefinitionId = approvalHive.id,
        objective = "Ship",
        status = WorkflowRunStatus.AwaitingHuman,
        taskRuns = mapOf(
            gate to TaskRun(
                id = TaskRunId("run-${project.id.value}-gate"),
                taskDefinitionId = gate,
                status = TaskRunStatus.AwaitingApproval,
                assignedRoleId = null,
                executor = TaskExecutor.HumanApproval("Approve the start"),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
        definitionSnapshot = approvalHive,
    )

    /** A run with a live provider session. */
    private fun sessionRun(project: Project, updatedAt: Long) = WorkflowRun(
        id = WorkflowRunId("run-${project.id.value}"),
        projectId = project.id,
        workflowDefinitionId = workHive.id,
        objective = "Work",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            work to TaskRun(
                id = TaskRunId("run-${project.id.value}-work"),
                taskDefinitionId = work,
                status = TaskRunStatus.Running,
                assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                assignedProviderId = SessionProvider.ID,
                providerRunId = ProviderRunId("session-${project.id.value}"),
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
        definitionSnapshot = workHive,
    )

    private inner class Fixture(val persistence: FaultInjectingPersistence = FaultInjectingPersistence()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = SessionProvider()
        val projectA = project("a")
        val projectB = project("b")
        lateinit var runtime: ApplicationRuntime

        suspend fun seed(): Fixture {
            val inner = persistence.inner
            listOf(projectA, projectB).forEach { inner.projects.put(it) }
            inner.definitions.put(approvalHive)
            inner.definitions.put(workHive)
            // A is the newest run, so it is the one loaded at start.
            inner.runs.put(sessionRun(projectA, updatedAt = 100L))
            inner.runs.put(waitingRun(projectB, updatedAt = 50L))
            runtime = ApplicationRuntime.create(providers = listOf(provider), scope = scope, persistence = persistence)
            return this
        }

        fun liveRun() = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation.run
    }

    // ---- A cancelled caller is not a failed load ----

    @Test
    fun aCancelledLoadLeavesTheLiveRunShowingInsteadOfWipingIt() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            val before = fixture.liveRun()
            fixture.persistence.beforeProjectRead = { throw CancellationException("the caller went away") }

            assertFailsWith<CancellationException> { fixture.runtime.loadLatest() }
            assertFailsWith<CancellationException> { fixture.runtime.openProject(fixture.projectB.id) }
            assertFailsWith<CancellationException> { fixture.runtime.switchToRun(WorkflowRunId("run-b")) }

            // Before: each swallowed it, wiped the live run and published a resume failure.
            assertEquals(before.id, fixture.liveRun().id)
            assertNull(
                fixture.persistence.inner.runs.get(before.id)?.takeIf { it.status == WorkflowRunStatus.Cancelled },
                "a cancelled caller does not cancel the run it was looking at",
            )
        } finally {
            fixture.scope.cancel()
        }
    }

    // ---- A run left behind stops spending ----

    @Test
    fun openingAnotherProjectStopsTheRunLeftBehind() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            assertEquals(WorkflowRunId("run-a"), fixture.liveRun().id)

            fixture.runtime.openProject(fixture.projectB.id)

            assertEquals(WorkflowRunId("run-b"), fixture.liveRun().id)
            // Before: run A stayed Running with its session billing, cycled and capped by nobody.
            assertEquals(WorkflowRunStatus.Cancelled, fixture.persistence.inner.runs.get(WorkflowRunId("run-a"))?.status)
            assertEquals(listOf(ProviderRunId("session-a")), fixture.provider.cancelled)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun openingAProjectWithNoRunsAlsoStopsTheRunLeftBehind() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            val empty = project("c")
            fixture.persistence.inner.projects.put(empty)

            fixture.runtime.openProject(empty.id)

            assertIs<ApplicationRuntimeState.NoRun>(fixture.runtime.state.value)
            assertEquals(WorkflowRunStatus.Cancelled, fixture.persistence.inner.runs.get(WorkflowRunId("run-a"))?.status)
            assertEquals(listOf(ProviderRunId("session-a")), fixture.provider.cancelled)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun reloadingTheRunThatIsAlreadyLiveStopsNothing() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            fixture.runtime.loadLatest()
            fixture.runtime.openProject(fixture.projectA.id)
            fixture.runtime.refresh()

            assertEquals(WorkflowRunId("run-a"), fixture.liveRun().id)
            assertEquals(WorkflowRunStatus.Running, fixture.persistence.inner.runs.get(WorkflowRunId("run-a"))?.status)
            assertEquals(emptyList(), fixture.provider.cancelled)
        } finally {
            fixture.scope.cancel()
        }
    }

    // ---- A decision is built on the newest copy and settles its gate ----

    @Test
    fun approvingAPersonsStepKeepsWhatSomeoneElseSavedSinceTheLastCycle() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            val waiting = waitingRun(fixture.projectB)
            fixture.persistence.inner.runs.put(waiting)
            fixture.runtime.openProject(fixture.projectB.id)
            // Someone else pauses the hive in the same millisecond the live copy carries.
            fixture.persistence.inner.runs.put(waiting.copy(keeperPause = WorkflowKeeperPause(pausedAtEpochMillis = 1L)))

            fixture.runtime.approveTask(gate)

            val saved = assertNotNull(fixture.persistence.inner.runs.get(waiting.id))
            assertEquals(TaskRunStatus.Completed, saved.taskRuns.getValue(gate).status)
            // Before: the decision was built on the live copy and wrote over the pause.
            assertNotNull(saved.keeperPause)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun approvingAPersonsStepDecidesItsPlanGateToo() = runBlocking<Unit> {
        val fixture = Fixture().seed()
        try {
            val waiting = waitingRun(fixture.projectB)
            fixture.runtime.openProject(fixture.projectB.id)
            val gateId = ApprovalGateId("plan:${waiting.id.value}:${gate.value}:1")
            // The gate the cycle opens for a task that is waiting for approval.
            ApprovalGateCoordinator(
                fixture.persistence.approvalGates,
                RepositoryWorkflowEventSink(fixture.persistence.events),
            ).open(
                id = gateId,
                workflowRunId = waiting.id,
                taskDefinitionId = gate,
                kind = ApprovalGateKind.PlanApproval,
                reason = "Plan approval required for 'Gate'",
                requiresHuman = true,
                nowEpochMillis = 5L,
            )

            fixture.runtime.approveTask(gate)

            assertEquals(TaskRunStatus.Completed, fixture.persistence.inner.runs.get(waiting.id)?.taskRuns?.getValue(gate)?.status)
            // Before: it stayed Pending for ever, a second approval with nothing left to resume.
            assertEquals(ApprovalGateStatus.Approved, fixture.persistence.approvalGates.get(gateId)?.status)
        } finally {
            fixture.scope.cancel()
        }
    }
}

/** A provider whose sessions stay open until cancelled; records the cancels. */
private class SessionProvider : AgentProvider {
    override val id = ID
    val cancelled = mutableListOf<ProviderRunId>()

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle = AgentRunHandle(ProviderRunId("started"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow { awaitCancellation() }

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        cancelled += runId
        return ProviderActionResult.Accepted
    }

    companion object {
        val ID = AgentProviderId("session-provider")
    }
}
