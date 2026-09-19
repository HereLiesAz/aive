package com.hereliesaz.aive

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.ApplicationRuntimeState
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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidWorkflowLifecycleSmokeTest {
    @Test
    fun androidRuntimeCompletesGovernedWorkflowLifecycle() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val provider = AndroidLifecycleProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ApplicationRuntime.create(
            providers = listOf(provider),
            scope = scope,
            persistence = persistence,
        )

        try {
            runtime.launchStarterWorkflow(
                projectName = "Android lifecycle proof",
                objective = "Verify governed workflow execution on the Android application runtime",
            )

            withTimeout(15_000L) {
                var implementationApproved = false
                while (true) {
                    val live = runtime.state.value as? ApplicationRuntimeState.Live ?: run {
                        delay(25L)
                        continue
                    }
                    val implementation = live.presentation.run.taskRuns[TaskDefinitionId("implementation")]
                    if (!implementationApproved && implementation?.status == TaskRunStatus.AwaitingApproval) {
                        runtime.approveTask(TaskDefinitionId("implementation"))
                        implementationApproved = true
                    }
                    val release = live.presentation.run.taskRuns[TaskDefinitionId("release-approval")]
                    if (release?.status == TaskRunStatus.AwaitingApproval) {
                        assertTrue(implementationApproved)
                        runtime.approveTask(TaskDefinitionId("release-approval"))
                        break
                    }
                    delay(25L)
                }
            }

            val completed = runtime.state.value as ApplicationRuntimeState.Live
            assertEquals(WorkflowRunStatus.Completed, completed.presentation.run.status)
            assertEquals(
                TaskRunStatus.Completed,
                completed.presentation.run.taskRuns.getValue(TaskDefinitionId("verification")).status,
            )
            assertEquals(
                TaskRunStatus.Completed,
                completed.presentation.run.taskRuns.getValue(TaskDefinitionId("review")).status,
            )
            assertTrue(provider.startedTaskIds.isNotEmpty())
        } finally {
            runtime.close()
            scope.cancel()
        }
    }
}

private class AndroidLifecycleProvider : AgentProvider {
    override val id = AgentProviderId("android-lifecycle")
    val startedTaskIds = mutableSetOf<String>()
    private val approvalRequired = mutableSetOf<ProviderRunId>()
    private val approved = mutableSetOf<ProviderRunId>()
    private var nextId = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.TestAuthoring,
            AgentCapability.Testing,
        ),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        nextId += 1
        val runId = ProviderRunId("android-lifecycle-$nextId")
        startedTaskIds += request.taskRunId.value
        if (request.requirePlanApproval) approvalRequired += runId
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> =
        if (runId in approvalRequired && runId !in approved) {
            flowOf(AgentEvent.PlanGenerated(runId, "1. Inspect scope\n2. Execute approved work"))
        } else {
            flowOf(AgentEvent.Completed(runId))
        }

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        approved += runId
        return ProviderActionResult.Accepted
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
