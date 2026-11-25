package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
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
import kotlin.test.assertTrue

class StarterWorkflowEndToEndTest {
    @Test
    fun starterWorkflowRunsImplementationTestsVerificationReviewAndReleaseGate() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val provider = CompletingGovernedProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(provider),
                scope = scope,
                persistence = persistence,
            )

            runtime.launchStarterWorkflow(
                projectName = "Alpha",
                objective = "Ship the requested change",
                repository = com.hereliesaz.geministrator.domain.RepositoryRef("test", "project"),
            )

            var implementationApproved = false
            withTimeout(12_000L) {
                while (true) {
                    val live = runtime.state.value as? ApplicationRuntimeState.Live
                    val implementation = live?.presentation?.run?.taskRuns?.get(TaskDefinitionId("implementation"))
                    if (!implementationApproved && implementation?.status == TaskRunStatus.AwaitingApproval) {
                        assertTrue(implementation.progressMessage?.contains("Inspect the requested change") == true)
                        runtime.approveTask(TaskDefinitionId("implementation"))
                        implementationApproved = true
                    }

                    val release = live?.presentation?.run?.taskRuns?.get(TaskDefinitionId("release-approval"))
                    if (live?.presentation?.run?.status == WorkflowRunStatus.AwaitingHuman &&
                        release?.status == TaskRunStatus.AwaitingApproval
                    ) break
                    delay(50L)
                }
            }

            assertTrue(implementationApproved)
            val awaiting = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val taskRuns = awaiting.presentation.run.taskRuns
            assertEquals(TaskRunStatus.Completed, taskRuns.getValue(TaskDefinitionId("implementation--pre-code-tests")).status)
            assertEquals(TaskRunStatus.Completed, taskRuns.getValue(TaskDefinitionId("implementation")).status)
            assertEquals(TaskRunStatus.Completed, taskRuns.getValue(TaskDefinitionId("implementation--post-code-tests")).status)
            assertEquals(TaskRunStatus.Completed, taskRuns.getValue(TaskDefinitionId("verification")).status)
            assertEquals(TaskRunStatus.Completed, taskRuns.getValue(TaskDefinitionId("review")).status)
            assertEquals(TaskRunStatus.AwaitingApproval, taskRuns.getValue(TaskDefinitionId("release-approval")).status)
            assertTrue(
                provider.startedTaskIds.containsAll(
                    setOf(
                        "implementation--pre-code-tests",
                        "implementation",
                        "implementation--post-code-tests",
                        "verification",
                        "review",
                    ),
                ),
            )

            runtime.approveTask(TaskDefinitionId("release-approval"))

            val completed = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(WorkflowRunStatus.Completed, completed.presentation.run.status)
            assertEquals(TaskRunStatus.Completed, completed.presentation.run.taskRuns.getValue(TaskDefinitionId("release-approval")).status)
            assertEquals(WorkflowRunStatus.Completed, persistence.runs.get(completed.presentation.run.id)?.status)
        } finally {
            scope.cancel()
        }
    }
}

private class CompletingGovernedProvider : AgentProvider {
    override val id = AgentProviderId("governed-provider")
    val startedTaskIds = mutableSetOf<String>()
    private val planApprovalRequired = mutableSetOf<ProviderRunId>()
    private val approvedPlans = mutableSetOf<ProviderRunId>()
    private var runCount = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.TestAuthoring,
            AgentCapability.Testing,
        ),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        // Assert policy delivery at the provider boundary without using the policy as the oracle.
        assertTrue(request.roleInstructions.contains("Timestamp every response: mm/dd/yyyy hh:mm am/pm"))
        assertTrue(request.roleInstructions.contains("caller outside its own tests"))
        assertTrue(request.roleInstructions.contains("versionMinor"))
        assertTrue(request.roleInstructions.contains("request a Glee Audit from the Antagonist"))
        if (request.taskRunId.value.endsWith("-review")) {
            assertTrue(request.roleInstructions.contains("Review correctness, maintainability, side effects"))
            assertTrue(!request.roleInstructions.contains("# You are the Antagonist."))
        }
        startedTaskIds += request.taskRunId.value.substringAfter("run-").substringAfter("-")
        runCount += 1
        val runId = ProviderRunId("governed-run-$runCount")
        if (request.requirePlanApproval) planApprovalRequired += runId
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = if (
        runId in planApprovalRequired && runId !in approvedPlans
    ) {
        flowOf(
            AgentEvent.PlanGenerated(
                runId,
                "1. Inspect the requested change\n2. Implement only the approved scope",
            ),
        )
    } else {
        flowOf(AgentEvent.Completed(runId))
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        approvedPlans += runId
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
