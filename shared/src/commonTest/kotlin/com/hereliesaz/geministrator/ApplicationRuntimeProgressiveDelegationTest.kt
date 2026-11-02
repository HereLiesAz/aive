package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ProgressiveDelegationStore
import com.hereliesaz.geministrator.workflow.isHeldAtEnginePlanGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A read-only role's plan gates, approved by the keeper until delegated, then approved by delegation. */
class ApplicationRuntimeProgressiveDelegationTest {
    private val first = TaskDefinitionId("research-1")
    private val second = TaskDefinitionId("research-2")
    private val third = TaskDefinitionId("research-3")

    @Test
    fun aDelegatedPlanGateApprovesItselfAndSaysSo(): Unit = runBlocking {
        val delegations = ProgressiveDelegationStore(InMemorySettings()).also { it.setThreshold(2) }
        withHeldRuntime(delegations) { runtime, persistence ->
            runtime.approveTask(first)
            assertEquals(null, delegations.state.value.pendingOffer, "no offer before the threshold")
            runtime.approveTask(second)

            val offer = assertNotNull(delegations.state.value.pendingOffer, "the streak earns one offer")
            assertEquals(BuiltInRoles.Researcher.id.value, offer.roleId)
            assertTrue(isHeld(runtime, third), "nothing is approved before the keeper accepts")

            delegations.accept(offer, nowEpochMillis = 1L)

            val runId = (runtime.state.value as ApplicationRuntimeState.Live).presentation.run.id
            val delegated = waitFor {
                persistence.events.forRun(runId)
                    .filterIsInstance<ApprovalDecisionReceived>()
                    .firstOrNull { it.taskDefinitionId == third && it.approved }
            }
            assertTrue(delegated.byDelegation, "the third gate was approved by delegation")
            val keeperDecisions = persistence.events.forRun(runId)
                .filterIsInstance<ApprovalDecisionReceived>()
                .filter { it.taskDefinitionId == first || it.taskDefinitionId == second }
            assertTrue(keeperDecisions.isNotEmpty() && keeperDecisions.none { it.byDelegation })
        }
    }

    @Test
    fun withoutAcceptanceTheGateKeepsAsking(): Unit = runBlocking {
        val delegations = ProgressiveDelegationStore(InMemorySettings()).also { it.setThreshold(2) }
        withHeldRuntime(delegations) { runtime, _ ->
            runtime.approveTask(first)
            runtime.approveTask(second)
            delegations.decline(assertNotNull(delegations.state.value.pendingOffer))

            delay(2_500L)
            assertTrue(isHeld(runtime, third), "a declined offer leaves the gate waiting on the keeper")
            assertFalse(delegations.state.value.delegations.isNotEmpty())
        }
    }

    private fun isHeld(runtime: ApplicationRuntime, task: TaskDefinitionId): Boolean =
        (runtime.state.value as? ApplicationRuntimeState.Live)
            ?.presentation?.run?.taskRuns?.get(task)
            ?.let { it.status == TaskRunStatus.AwaitingApproval && it.isHeldAtEnginePlanGate() } == true

    private suspend fun withHeldRuntime(
        delegations: ProgressiveDelegationStore,
        body: suspend (ApplicationRuntime, InMemoryWorkflowPersistence) -> Unit,
    ) {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(ReadOnlyDraftingProvider()),
                scope = scope,
                persistence = persistence,
                delegations = delegations,
            )
            runtime.launchSavedWorkflow(
                definition = researchOnly(),
                projectName = "Progressive delegation",
                objective = "Research three things",
                supplementalRoles = listOf(BuiltInRoles.Researcher),
            )
            waitFor { listOf(first, second, third).all { isHeld(runtime, it) }.takeIf { it } }
            body(runtime, persistence)
        } finally {
            scope.cancel()
        }
    }

    private fun researchOnly() = WorkflowDefinition(
        id = WorkflowDefinitionId("research-only"),
        name = "Research only",
        tasks = listOf(first, second, third).map { id ->
            TaskDefinition(
                id = id,
                name = "Research ${id.value}",
                objective = "Find out about ${id.value}",
                roleId = BuiltInRoles.Researcher.id,
                approvalPolicy = ApprovalPolicy.HumanApproval,
            )
        },
        integrationPolicy = IntegrationPolicy.Manual,
        testDesignPolicy = TestDesignPolicy.None,
    )

    private suspend fun <T : Any> waitFor(probe: suspend () -> T?): T = withTimeout(15_000L) {
        while (true) {
            probe()?.let { return@withTimeout it }
            delay(25L)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

/** A provider that can only read and plan: its plan gates are eligible for delegation. */
private class ReadOnlyDraftingProvider : AgentProvider {
    override val id = AgentProviderId("read-only-drafting-provider")
    private var runs = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(AgentCapability.Research, AgentCapability.PlanGeneration),
    )

    override suspend fun draftPlan(request: AgentTaskRequest): String = "1. Read\n2. Summarize"

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        runs += 1
        return AgentRunHandle(ProviderRunId("read-only-run-$runs"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flowOf(AgentEvent.Completed(runId))

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
