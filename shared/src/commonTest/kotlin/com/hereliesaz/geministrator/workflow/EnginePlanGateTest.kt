package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnginePlanGateTest {
    private val implementation = TaskDefinitionId("implementation")
    private val project = Project(
        id = ProjectId("project"),
        name = "The Aive",
        repository = RepositoryRef("HereLiesAz", "aive", "main"),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
    private val definition = StarterWorkflowFactory.create(
        id = WorkflowDefinitionId("starter"),
        objective = "Ship the objective",
    ).copy(testDesignPolicy = TestDesignPolicy.None)
    private val run = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("run"),
        projectId = project.id,
        objective = "Ship the objective",
        nowEpochMillis = 1L,
        taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
    )

    @Test
    fun draftedPlanHoldsTheTaskWithoutStartingARun(): Unit = runBlocking {
        val gateway = DraftingGateway(plan = "1. Edit App.kt\n2. Run the tests")
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)

        val held = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L).run
        val taskRun = held.taskRuns.getValue(implementation)

        assertEquals(TaskRunStatus.AwaitingApproval, taskRun.status)
        assertTrue(taskRun.isHeldAtEnginePlanGate())
        assertEquals("1. Edit App.kt\n2. Run the tests", taskRun.providerPlan)
        assertNull(taskRun.providerRunId)
        assertTrue(gateway.sessions.isEmpty(), "no provider run may start before approval")
    }

    @Test
    fun approvalDispatchesTheApprovedPlanWithoutAnotherGate(): Unit = runBlocking {
        val gateway = DraftingGateway(plan = "1. Edit App.kt")
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        val held = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L).run

        val approved = engine.approvePlanGate(held, implementation, nowEpochMillis = 3L)
        assertEquals(TaskRunStatus.Ready, approved.taskRuns.getValue(implementation).status)

        val dispatched = engine.dispatchReadyTasks(project, definition, approved, nowEpochMillis = 4L)
        val request = assertNotNull(gateway.sessions.singleOrNull()).taskRequest
        assertFalse(request.requirePlanApproval)
        assertTrue(request.promptContext.dynamicContext.any { it.label == "Approved plan" && it.content == "1. Edit App.kt" })
        assertEquals(1, gateway.drafts, "an approved plan is not drafted again")
        val taskRun = dispatched.run.taskRuns.getValue(implementation)
        assertEquals("1. Edit App.kt", taskRun.providerPlan)
        assertNotNull(taskRun.providerRunId)
    }

    @Test
    fun aProviderThatPlansInsideItsRunStillStartsImmediately(): Unit = runBlocking {
        val gateway = DraftingGateway(plan = null)
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)

        val dispatched = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L).run

        assertTrue(assertNotNull(gateway.sessions.singleOrNull()).taskRequest.requirePlanApproval)
        assertEquals(TaskRunStatus.Planning, dispatched.taskRuns.getValue(implementation).status)
    }

    @Test
    fun rejectionClearsTheHeldPlanSoTheRetryDraftsAgain(): Unit = runBlocking {
        val gateway = DraftingGateway(plan = "1. Edit App.kt")
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all)
        val held = engine.dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L).run

        val rejected = engine.handleFailure(
            definition = definition,
            run = held,
            taskDefinitionId = implementation,
            retryReason = RetryReason.PlanRejected,
            reason = "Plan rejected",
            nowEpochMillis = 3L,
        )

        val taskRun = rejected.taskRuns.getValue(implementation)
        assertNull(taskRun.providerPlan)
        assertFalse(taskRun.isHeldAtEnginePlanGate())
        assertTrue(gateway.sessions.isEmpty())
    }
}

private class DraftingGateway(private val plan: String?) : ManagedSessionGateway {
    private val providerId = AgentProviderId("drafting-provider")
    val sessions = mutableListOf<ManagedSessionRequest>()
    var drafts = 0

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun draftPlan(providerId: AgentProviderId, request: AgentTaskRequest): String? {
        drafts += 1
        return plan
    }

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        sessions += request
        return ManagedSessionHandle(request.taskRequest.taskRunId, providerId, ProviderRunId("provider-run-${sessions.size}"))
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
