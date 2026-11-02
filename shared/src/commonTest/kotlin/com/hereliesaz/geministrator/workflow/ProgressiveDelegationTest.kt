package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.domain.SpreadsheetSource
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressiveDelegationTest {
    private val researcher = BuiltInRoles.Researcher
    private val textProvider = AgentProviderId("text-llm")
    private val readOnly = setOf(AgentCapability.PlanGeneration, AgentCapability.PlanApproval, AgentCapability.Research)

    private fun context(
        gateKind: ApprovalGateKind = ApprovalGateKind.PlanApproval,
        role: RoleDefinition = researcher,
        executor: TaskExecutor = TaskExecutor.RoleAgent(role.id),
        requiredRoleAuthority: RoleAuthority? = null,
        capabilities: Set<AgentCapability>? = readOnly,
        tainted: Boolean = false,
    ) = GateDelegationContext(
        gateKind = gateKind,
        executor = executor,
        role = role,
        requiredRoleAuthority = requiredRoleAuthority,
        providerId = textProvider,
        providerCapabilities = capabilities,
        raisedByContextTaint = tainted,
    )

    private fun key(): DelegationKey =
        assertIs<DelegationEligibility.Eligible>(DelegationPolicy.eligibility(context())).key

    @Test
    fun aReadOnlyRolesPlanGateIsEligible() {
        val key = key()
        assertEquals(researcher.id.value, key.roleId)
        assertEquals(DelegableGateKind.PlanApproval, key.gateKind)
        assertEquals(textProvider.value, key.providerId)
    }

    @Test
    fun aStreakReachingTheThresholdOffersOnce() {
        val key = key()
        var state = ProgressiveDelegationState(threshold = 3)
        repeat(2) { state = state.afterApproval(key, "Researcher") }
        assertNull(state.pendingOffer, "no offer before the threshold")

        state = state.afterApproval(key, "Researcher")
        assertEquals(key, state.pendingOffer)
        assertEquals(3, state.pendingOfferRecord?.consecutiveApprovals)
        assertFalse(state.isDelegated(key), "an offer is not a delegation")

        state = state.decline(key)
        assertNull(state.pendingOffer)
        repeat(5) { state = state.afterApproval(key, "Researcher") }
        assertNull(state.pendingOffer, "a declined offer is not repeated during the same streak")
        assertFalse(state.isDelegated(key))
    }

    @Test
    fun acceptingTheOfferDelegates() {
        val key = key()
        var state = ProgressiveDelegationState(threshold = 2)
        repeat(2) { state = state.afterApproval(key, "Researcher") }
        state = state.accept(key, nowEpochMillis = 42L)
        assertTrue(state.isDelegated(key))
        assertNull(state.pendingOffer)
        assertEquals(42L, state.record(key)?.delegatedAtEpochMillis)
        assertEquals(listOf(key), state.delegations.map { it.key })
    }

    @Test
    fun nothingButThePendingOfferCanBeAccepted() {
        val key = key()
        val state = ProgressiveDelegationState(threshold = 5).afterApproval(key, "Researcher")
        assertFalse(state.accept(key, nowEpochMillis = 1L).isDelegated(key))
    }

    @Test
    fun aRejectionResetsTheStreakAndEndsADelegation() {
        val key = key()
        var state = ProgressiveDelegationState(threshold = 3)
        repeat(2) { state = state.afterApproval(key, "Researcher") }
        state = state.afterRejection(key.roleId, key.providerId)
        assertEquals(0, state.record(key)?.consecutiveApprovals)
        repeat(2) { state = state.afterApproval(key, "Researcher") }
        assertNull(state.pendingOffer, "the streak started over")

        state = state.afterApproval(key, "Researcher").accept(key, nowEpochMillis = 1L)
        assertTrue(state.isDelegated(key))
        state = state.afterRejection(key.roleId, providerId = null)
        assertFalse(state.isDelegated(key), "a rejection ends the delegation")
    }

    @Test
    fun aRejectionWithdrawsAPendingOffer() {
        val key = key()
        var state = ProgressiveDelegationState(threshold = 2)
        repeat(2) { state = state.afterApproval(key, "Researcher") }
        assertEquals(key, state.pendingOffer)
        state = state.afterRejection(key.roleId, key.providerId)
        assertNull(state.pendingOffer)
    }

    @Test
    fun revokingRestoresAsking() {
        val key = key()
        val store = ProgressiveDelegationStore(InMemorySettings())
        store.setThreshold(2)
        repeat(2) { store.recordApproval(key, "Researcher") }
        store.accept(key, nowEpochMillis = 7L)
        assertTrue(store.isDelegated(key))

        store.revoke(key)
        assertFalse(store.isDelegated(key))
        assertTrue(store.state.value.delegations.isEmpty())
        assertEquals(0, store.state.value.record(key)?.consecutiveApprovals)
    }

    @Test
    fun theStorePersistsAcrossInstances() {
        val key = key()
        val settings = InMemorySettings()
        val first = ProgressiveDelegationStore(settings)
        first.setThreshold(2)
        repeat(2) { first.recordApproval(key, "Researcher") }
        first.accept(key, nowEpochMillis = 7L)

        val second = ProgressiveDelegationStore(settings)
        assertTrue(second.isDelegated(key))
        assertEquals(2, second.state.value.threshold)
    }

    @Test
    fun theThresholdIsBounded() {
        val state = ProgressiveDelegationState()
        assertEquals(DEFAULT_DELEGATION_THRESHOLD, state.threshold)
        assertEquals(MIN_DELEGATION_THRESHOLD, state.withThreshold(0).threshold)
        assertEquals(MAX_DELEGATION_THRESHOLD, state.withThreshold(1_000).threshold)
    }

    @Test
    fun mutatingGatesAreNeverEligible() {
        val ineligible = buildList {
            // Every gate kind but plan approval.
            ApprovalGateKind.entries.filter { it != ApprovalGateKind.PlanApproval }.forEach { add(context(gateKind = it)) }
            // Invariant 4: HumanApproval tasks and repository mutations; deployments; external writes; code.
            add(context(executor = TaskExecutor.HumanApproval()))
            add(context(executor = TaskExecutor.RepositoryOperation("push")))
            add(context(executor = TaskExecutor.Deployment("production")))
            add(context(executor = TaskExecutor.ExternalService("slack", "post")))
            add(context(executor = TaskExecutor.GitHubAction("deploy.yml")))
            add(context(executor = TaskExecutor.TestRunner()))
            add(context(executor = TaskExecutor.NestedWorkflow(WorkflowDefinitionId("child"))))
            add(context(executor = TaskExecutor.Distributed(TaskExecutor.RoleAgent(researcher.id))))
            // Roles that can write, integrate or release, or that write to a surface.
            listOf(
                BuiltInRoles.ImplementationEngineer,
                BuiltInRoles.CrashTestDummy,
                BuiltInRoles.ReleaseEngineer,
                BuiltInRoles.Orchestrator,
            ).forEach { add(context(role = it)) }
            add(
                context(
                    role = researcher.copy(
                        surfaces = listOf(
                            RoleSurface.Spreadsheet(
                                alias = "sheet",
                                source = SpreadsheetSource.AppFile("ledger.csv"),
                                writable = true,
                            ),
                        ),
                    ),
                ),
            )
            add(context(role = researcher.copy(executionSource = RoleExecutionSource.GitHubAction(workflow = "agent.yml"))))
            add(context(role = researcher.copy(capabilitiesRequired = setOf(AgentCapability.RepositoryWrite))))
            // A task exercising a writing authority.
            add(context(requiredRoleAuthority = RoleAuthority.Implement))
            // A provider that writes or runs code, or whose reach is unknown.
            add(context(capabilities = readOnly + AgentCapability.RepositoryWrite))
            add(context(capabilities = readOnly + AgentCapability.ShellExecution))
            add(context(capabilities = readOnly + AgentCapability.PullRequestCreation))
            add(context(capabilities = null))
            // A gate raised by context taint.
            add(context(tainted = true))
            // An executor that names another role.
            add(context(executor = TaskExecutor.RoleAgent(RoleDefinitionId("someone-else"))))
        }
        ineligible.forEach { candidate ->
            assertIs<DelegationEligibility.Ineligible>(DelegationPolicy.eligibility(candidate), "$candidate")
        }
    }

    @Test
    fun onlyPlanApprovalHasADelegableKind() {
        ApprovalGateKind.entries.forEach { kind ->
            val delegable = DelegationPolicy.delegableKind(kind)
            if (kind == ApprovalGateKind.PlanApproval) {
                assertEquals(DelegableGateKind.PlanApproval, delegable)
            } else {
                assertNull(delegable, "$kind must never be delegable")
            }
        }
    }

    @Test
    fun onlyAnApprovedPlanGateCanBeDecidedByDelegation() = kotlinx.coroutines.test.runTest {
        val gates = InMemoryGates()
        val coordinator = ApprovalGateCoordinator(gates, com.hereliesaz.geministrator.events.NoOpWorkflowEventSink)
        val now = 1L
        val escalation = coordinator.open(
            id = com.hereliesaz.geministrator.domain.ApprovalGateId("escalation"),
            workflowRunId = com.hereliesaz.geministrator.domain.WorkflowRunId("run"),
            taskDefinitionId = null,
            kind = ApprovalGateKind.FailureEscalation,
            reason = "escalated",
            requiresHuman = true,
            nowEpochMillis = now,
        )
        val refused = runCatching {
            coordinator.decide(escalation.id, approved = true, decidedByRoleId = null, note = null, nowEpochMillis = now, byDelegation = true)
        }
        assertTrue(refused.isFailure)
        assertEquals(ApprovalGateStatus.Pending, gates.get(escalation.id)?.status)
    }
}

private class InMemoryGates : ApprovalGateRepository {
    private val gates = mutableMapOf<com.hereliesaz.geministrator.domain.ApprovalGateId, ApprovalGate>()
    override suspend fun put(gate: ApprovalGate) {
        gates[gate.id] = gate
    }
    override suspend fun get(id: com.hereliesaz.geministrator.domain.ApprovalGateId): ApprovalGate? = gates[id]
    override suspend fun unresolved(workflowRunId: com.hereliesaz.geministrator.domain.WorkflowRunId): List<ApprovalGate> =
        gates.values.filter { it.workflowRunId == workflowRunId && it.status == ApprovalGateStatus.Pending }
}
