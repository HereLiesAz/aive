package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.MemoryPromptContextProvider
import com.hereliesaz.geministrator.memory.MemoryPromptRecall
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryPromptInjectionTest {
    @Test
    fun recalledMemoryIsInjectedBeforeProviderStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = RecordingStartProvider()
        try {
            MemoryRuntimeBridge.promptContextProvider = MemoryPromptContextProvider { _, _ ->
                MemoryPromptRecall(
                    blocks = listOf(
                        PromptContextBlock("Relevant memory", "Prior implementation used the repository gateway."),
                    ),
                    memoryAddresses = setOf("memory-node:test-recall"),
                )
            }
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )
            gateway.createSession(
                ManagedSessionRequest(
                    providerSelection = ProviderSelectionRequest(),
                    taskRequest = AgentTaskRequest(
                        taskRunId = TaskRunId("memory-prompt-task"),
                        objective = "Update the repository gateway",
                        roleInstructions = "Implement the requested change.",
                        acceptanceCriteria = emptyList(),
                    ),
                ),
            )

            val block = requireNotNull(provider.startedRequest)
                .promptContext.dynamicContext.single { it.label == "Relevant memory" }
            assertEquals("Prior implementation used the repository gateway.", block.content)
        } finally {
            MemoryRuntimeBridge.reset()
            scope.cancel()
        }
    }
}

private class RecordingStartProvider : AgentProvider {
    override val id = AgentProviderId("memory-prompt-provider")
    var startedRequest: AgentTaskRequest? = null

    override suspend fun capabilities() = AgentCapabilities(supported = emptySet())

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startedRequest = request
        return AgentRunHandle(ProviderRunId("memory-prompt-run"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
