package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowGovernanceTest {
    @Test
    fun providerRequiredEnvironmentPlanningInjectsEpaBeforeWorker() = runBlocking {
        val worker = GovernanceFakeProvider(
            id = AgentProviderId("worker"),
            providerCapabilities = AgentCapabilities(
                supported = setOf(
                    AgentCapability.RepositoryRead,
                    AgentCapability.RepositoryWrite,
                ),
                requiresEnvironmentPlanning = true,
            ),
        )
        val epa = GovernanceFakeProvider(
            id = AgentProviderId("epa"),
            providerCapabilities = AgentCapabilities(
                setOf(AgentCapability.EnvironmentPlanning),
            ),
        )
        val preparer = WorkflowDefinitionPreparer(
            providerRegistry = AgentProviderRegistry(listOf(worker, epa), inferenceSettings = InMemorySettings()),
            roles = BuiltInRoles.all,
        )
        val implementationId = TaskDefinitionId("implement")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Workflow",
            testDesignPolicy = com.hereliesaz.geministrator.domain.TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = implementationId,
                    name = "Implement",
                    objective = "Implement feature",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    acceptanceCriteria = listOf(AcceptanceCriterion("works")),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                ),
            ),
        )

        val prepared = preparer.prepare(definition)
        val epaTask = prepared.tasks.single { it.roleId == BuiltInRoles.EpaRepresentative.id }
        val implementation = prepared.tasks.single { it.id == implementationId }

        assertTrue(epaTask.id in implementation.dependsOn)
        assertEquals(EnvironmentPlanningPolicy.NotRequired, implementation.environmentPlanningPolicy)
    }

    @Test
    fun exhaustedRetryUsesEscalationPolicy() {
        val taskRun = TaskRun(
            id = TaskRunId("run"),
            taskDefinitionId = TaskDefinitionId("task"),
            status = TaskRunStatus.Failed,
            attempt = 2,
            assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
        )

        val decision = FailurePolicyEvaluator.decide(
            taskRun = taskRun,
            retryPolicy = RetryPolicy(
                maxAttempts = 2,
                retryOn = setOf(RetryReason.ProviderFailure),
            ),
            escalationPolicy = com.hereliesaz.geministrator.domain.EscalationPolicy.Reassign(
                BuiltInRoles.RecoveryEngineer.id,
            ),
            reason = RetryReason.ProviderFailure,
        )

        val reassignment = assertIs<FailureDecision.Reassign>(decision)
        assertEquals(BuiltInRoles.RecoveryEngineer.id, reassignment.roleId)
    }

    @Test
    fun approvalDecisionPersistsAndEmitsAuditEvents() = runBlocking {
        val repository = MemoryGateRepository()
        val events = InMemoryWorkflowEventSink()
        val coordinator = ApprovalGateCoordinator(repository, events)
        val gateId = ApprovalGateId("gate-1")
        val workflowRunId = WorkflowRunId("workflow-run")
        val taskId = TaskDefinitionId("task")

        coordinator.open(
            id = gateId,
            workflowRunId = workflowRunId,
            taskDefinitionId = taskId,
            kind = ApprovalGateKind.PlanApproval,
            reason = "Review provider plan",
            requiredRoleId = BuiltInRoles.Architect.id,
            nowEpochMillis = 10L,
        )
        coordinator.decide(
            id = gateId,
            approved = true,
            decidedByRoleId = BuiltInRoles.Architect.id,
            note = "Approved",
            nowEpochMillis = 20L,
        )

        assertEquals(ApprovalGateStatus.Approved, repository.get(gateId)?.status)
        val approvalRequired = events.snapshot().filterIsInstance<ApprovalRequired>().single()
        assertTrue(approvalRequired.hasGateId)
        assertEquals(gateId, approvalRequired.gateId)
        assertTrue(
            events.snapshot().any {
                it is ApprovalDecisionReceived && it.approved && it.gateId == gateId
            },
        )
    }
}

private class MemoryGateRepository : ApprovalGateRepository {
    private val gates = mutableMapOf<ApprovalGateId, ApprovalGate>()

    override suspend fun put(gate: ApprovalGate) {
        gates[gate.id] = gate
    }

    override suspend fun get(id: ApprovalGateId): ApprovalGate? = gates[id]

    override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
        gates.values.filter {
            it.workflowRunId == workflowRunId &&
                (it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying)
        }
}

private class GovernanceFakeProvider(
    override val id: AgentProviderId,
    private val providerCapabilities: AgentCapabilities,
) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = providerCapabilities

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle =
        AgentRunHandle(ProviderRunId("run"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(
        runId: ProviderRunId,
        message: String,
    ): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Accepted
}
