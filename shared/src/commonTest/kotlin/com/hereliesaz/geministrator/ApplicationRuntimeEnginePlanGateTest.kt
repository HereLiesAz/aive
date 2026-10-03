package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A provider that drafts its plan before any run: the app's approve and reject act on the engine's gate. */
class ApplicationRuntimeEnginePlanGateTest {
    private val implementation = TaskDefinitionId("implementation")

    @Test
    fun approvingAHeldPlanStartsTheRunWithTheApprovedPlan(): Unit = runBlocking {
        val provider = DraftingProvider()
        withHeldRuntime(provider) { runtime, persistence ->
            assertTrue(provider.startedFor(implementation).isEmpty(), "nothing starts before approval")

            runtime.approveTask(implementation)

            val request = waitFor { provider.startedFor(implementation).firstOrNull() }
            assertFalse(request.requirePlanApproval)
            assertTrue(request.promptContext.dynamicContext.any { it.label == "Approved plan" && it.content == PLAN })
            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val gateId = ApprovalGateId("plan:${live.presentation.run.id.value}:implementation:1")
            assertEquals(ApprovalGateStatus.Approved, persistence.approvalGates.get(gateId)?.status)
        }
    }

    @Test
    fun rejectingAHeldPlanCancelsNothingAndDraftsAgain(): Unit = runBlocking {
        val provider = DraftingProvider()
        withHeldRuntime(provider) { runtime, _ ->
            runtime.rejectPlan(implementation)

            assertEquals(0, provider.cancelCalls, "an engine-held plan has no session to cancel")
            assertTrue(provider.startedFor(implementation).isEmpty())
            assertTrue(waitFor { provider.drafts.takeIf { it >= 2 } } >= 2)
        }
    }

    private suspend fun withHeldRuntime(
        provider: DraftingProvider,
        body: suspend (ApplicationRuntime, InMemoryWorkflowPersistence) -> Unit,
    ) {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = listOf(provider), scope = scope, persistence = persistence)
            runtime.launchStarterWorkflow(projectName = "Engine plan gate", objective = "Hold the plan")
            waitFor {
                (runtime.state.value as? ApplicationRuntimeState.Live)
                    ?.presentation?.run?.taskRuns?.get(implementation)
                    ?.takeIf { it.isHeldAtEnginePlanGate() }
            }
            body(runtime, persistence)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun <T : Any> waitFor(probe: () -> T?): T = withTimeout(12_000L) {
        while (true) {
            probe()?.let { return@withTimeout it }
            delay(25L)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private companion object {
        const val PLAN = "1. Inspect\n2. Implement"
    }
}

private class DraftingProvider : AgentProvider {
    override val id = AgentProviderId("drafting-provider")
    val started = mutableListOf<AgentTaskRequest>()
    var drafts = 0
    var cancelCalls = 0

    fun startedFor(task: TaskDefinitionId) = started.filter { it.orchestrationContext.taskDefinitionId == task }

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.TestAuthoring,
            AgentCapability.Testing,
        ),
    )

    override suspend fun draftPlan(request: AgentTaskRequest): String {
        drafts += 1
        return "1. Inspect\n2. Implement"
    }

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        started += request
        return AgentRunHandle(ProviderRunId("drafting-run-${started.size}"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flowOf(AgentEvent.Completed(runId))

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        cancelCalls += 1
        return ProviderActionResult.Accepted
    }
}
