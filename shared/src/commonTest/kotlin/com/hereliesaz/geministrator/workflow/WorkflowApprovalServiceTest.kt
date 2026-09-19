package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowApprovalServiceTest {
    @Test
    fun architectApprovalDrivesProviderPlanApprovalAndAuditEvent() = runBlocking {
        val fixture = fixture()
        val decided = fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Plan is sound", 20L)
        assertEquals(ApprovalGateStatus.Approved, decided.status)
        assertEquals(1, fixture.gateway.approvalCalls)
        assertTrue(fixture.events.snapshot().any { it is ApprovalDecisionReceived && it.approved })
    }

    @Test
    fun providerRejectionCancelsSessionBeforePersistingRejectedGate() = runBlocking {
        val fixture = fixture(ProviderActionResult.Rejected("provider refused plan"))
        val decided = fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 20L)
        assertEquals(ApprovalGateStatus.Rejected, decided.status)
        assertEquals("provider refused plan", decided.decisionNote)
        assertEquals(ApprovalGateStatus.Rejected, fixture.repository.get(fixture.gate.id)?.status)
        assertEquals(1, fixture.gateway.cancelCalls)
        assertEquals(ManagedSessionStatus.Failed, fixture.gateway.sessionStatus)
        val decisions = fixture.events.snapshot().filterIsInstance<ApprovalDecisionReceived>()
        assertEquals(1, decisions.size)
        assertEquals(false, decisions.single().approved)
    }

    @Test
    fun providerExceptionLeavesDurableApplyingGateWithoutDecisionEvent() = runBlocking {
        val fixture = fixture(failure = IllegalStateException("provider unavailable"))
        assertFailsWith<IllegalStateException> {
            fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 20L)
        }
        assertEquals(ApprovalGateStatus.Applying, fixture.repository.get(fixture.gate.id)?.status)
        assertTrue(fixture.events.snapshot().none { it is ApprovalDecisionReceived })
    }

    @Test
    fun applyingGateRecoversAcceptedProviderWithoutRepeatingApproval() = runBlocking {
        val fixture = fixture(failure = IllegalStateException("response lost"))
        assertFailsWith<IllegalStateException> {
            fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 20L)
        }
        assertEquals(1, fixture.gateway.approvalCalls)
        fixture.gateway.failure = null
        fixture.gateway.sessionStatus = ManagedSessionStatus.Running

        val recovered = fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 21L)

        assertEquals(ApprovalGateStatus.Approved, recovered.status)
        assertEquals(1, fixture.gateway.approvalCalls)
        assertEquals(0, fixture.gateway.cancelCalls)
        assertEquals(1, fixture.events.snapshot().filterIsInstance<ApprovalDecisionReceived>().size)
    }

    @Test
    fun applyingGateStillAwaitingApprovalCancelsAndRejectsWithoutRepeatingApproval() = runBlocking {
        val fixture = fixture(failure = IllegalStateException("response lost"))
        assertFailsWith<IllegalStateException> {
            fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 20L)
        }
        assertEquals(1, fixture.gateway.approvalCalls)
        fixture.gateway.failure = null
        fixture.gateway.sessionStatus = ManagedSessionStatus.AwaitingApproval

        val recovered = fixture.service.approvePlan(fixture.gate.id, fixture.handle, BuiltInRoles.Architect.id, "Approve", 21L)

        assertEquals(ApprovalGateStatus.Rejected, recovered.status)
        assertEquals(1, fixture.gateway.approvalCalls)
        assertEquals(1, fixture.gateway.cancelCalls)
        assertEquals(ManagedSessionStatus.Failed, fixture.gateway.sessionStatus)
        val decisions = fixture.events.snapshot().filterIsInstance<ApprovalDecisionReceived>()
        assertEquals(1, decisions.size)
        assertEquals(false, decisions.single().approved)
    }

    private suspend fun fixture(
        result: ProviderActionResult = ProviderActionResult.Accepted,
        failure: Throwable? = null,
    ): ApprovalFixture {
        val taskId = TaskDefinitionId("task")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Workflow",
            tasks = listOf(TaskDefinition(id = taskId, name = "Task", objective = "Do it", roleId = BuiltInRoles.ImplementationEngineer.id)),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            objective = "Objective",
            nowEpochMillis = 0L,
            taskRunIdFactory = { TaskRunId("task-run") },
        )
        val repository = ApprovalMemoryRepository()
        val events = InMemoryWorkflowEventSink()
        val gateway = ApprovalGateway(result, failure)
        val service = WorkflowApprovalService(repository, ApprovalGateCoordinator(repository, events), gateway)
        val gate = service.ensurePlanGate(run, taskId, { ApprovalGateId("gate") }, 10L)
        return ApprovalFixture(
            repository,
            events,
            gateway,
            service,
            gate,
            ManagedSessionHandle(TaskRunId("task-run"), AgentProviderId("jules"), ProviderRunId("session")),
        )
    }
}

private data class ApprovalFixture(
    val repository: ApprovalMemoryRepository,
    val events: InMemoryWorkflowEventSink,
    val gateway: ApprovalGateway,
    val service: WorkflowApprovalService,
    val gate: ApprovalGate,
    val handle: ManagedSessionHandle,
)

private class ApprovalMemoryRepository : ApprovalGateRepository {
    private val values = mutableMapOf<ApprovalGateId, ApprovalGate>()
    override suspend fun put(gate: ApprovalGate) { values[gate.id] = gate }
    override suspend fun get(id: ApprovalGateId): ApprovalGate? = values[id]
    override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
        values.values.filter { it.workflowRunId == workflowRunId && (it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying) }
}

private class ApprovalGateway(
    private val result: ProviderActionResult = ProviderActionResult.Accepted,
    var failure: Throwable? = null,
) : ManagedSessionGateway {
    var approvalCalls: Int = 0
    var cancelCalls: Int = 0
    var sessionStatus: ManagedSessionStatus = ManagedSessionStatus.AwaitingApproval
    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = AgentProviderId("jules")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("not used")
    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = sessionStatus
    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult {
        approvalCalls += 1
        failure?.let { throw it }
        if (result == ProviderActionResult.Accepted) sessionStatus = ManagedSessionStatus.Running
        return result
    }
    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelCalls += 1
        sessionStatus = ManagedSessionStatus.Failed
        return ProviderActionResult.Accepted
    }
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}
