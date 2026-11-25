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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApplicationRuntimePlanRejectionTest {
    @Test
    fun rejectingProviderPlanCancelsSessionAndAppliesPlanRejectedPolicy() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val provider = RejectablePlanProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(provider),
                scope = scope,
                persistence = persistence,
            )
            runtime.launchStarterWorkflow(
                projectName = "Plan rejection",
                objective = "Reject the first implementation plan",
                repository = com.hereliesaz.geministrator.domain.RepositoryRef("test", "project"),
            )

            val implementationId = TaskDefinitionId("implementation")
            withTimeout(12_000L) {
                while (true) {
                    val live = runtime.state.value as? ApplicationRuntimeState.Live
                    if (live?.presentation?.run?.taskRuns?.get(implementationId)?.status == TaskRunStatus.AwaitingApproval) {
                        break
                    }
                    delay(25L)
                }
            }

            val before = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val beforeTask = before.presentation.run.taskRuns.getValue(implementationId)
            val rejectedProviderRun = beforeTask.providerRunId
            runtime.rejectPlan(implementationId)

            val after = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val afterTask = after.presentation.run.taskRuns.getValue(implementationId)
            val gateId = ApprovalGateId(
                "plan:${after.presentation.run.id.value}:implementation:${beforeTask.attempt}",
            )
            assertEquals(1, provider.cancelCalls)
            assertEquals(ApprovalGateStatus.Rejected, persistence.approvalGates.get(gateId)?.status)
            assertNotEquals(TaskRunStatus.AwaitingApproval, afterTask.status)
            assertTrue(afterTask.attempt > beforeTask.attempt || afterTask.status == TaskRunStatus.Escalated)
            assertTrue(afterTask.providerRunId != rejectedProviderRun || afterTask.status == TaskRunStatus.Escalated)
        } finally {
            scope.cancel()
        }
    }
}

private class RejectablePlanProvider : AgentProvider {
    override val id = AgentProviderId("rejectable-plan-provider")
    private val approvalRequired = mutableSetOf<ProviderRunId>()
    private var runCount = 0
    var cancelCalls = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.TestAuthoring,
            AgentCapability.Testing,
        ),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        runCount += 1
        val runId = ProviderRunId("rejectable-run-$runCount")
        if (request.requirePlanApproval) approvalRequired += runId
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = if (runId in approvalRequired) {
        flowOf(AgentEvent.PlanGenerated(runId, "1. Inspect\n2. Implement"))
    } else {
        flowOf(AgentEvent.Completed(runId))
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        cancelCalls += 1
        approvalRequired -= runId
        return ProviderActionResult.Accepted
    }
}
