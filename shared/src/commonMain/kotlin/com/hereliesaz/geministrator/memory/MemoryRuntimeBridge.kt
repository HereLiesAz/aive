package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock

fun interface MemoryPromptContextProvider {
    suspend fun contextFor(request: AgentTaskRequest): List<PromptContextBlock>
}

private object NoOpMemoryPromptContextProvider : MemoryPromptContextProvider {
    override suspend fun contextFor(request: AgentTaskRequest): List<PromptContextBlock> = emptyList()
}

/**
 * Process-local bridge used by platform bootstraps to attach the live Memory layer to provider
 * sessions before ApplicationRuntime is created. Tests and platforms that do not install a memory
 * runtime retain fail-safe no-op observer/recall providers.
 */
object MemoryRuntimeBridge {
    var observer: MemorySessionObserver = NoOpMemorySessionObserver
    var promptContextProvider: MemoryPromptContextProvider = NoOpMemoryPromptContextProvider

    fun reset() {
        observer = NoOpMemorySessionObserver
        promptContextProvider = NoOpMemoryPromptContextProvider
    }
}
