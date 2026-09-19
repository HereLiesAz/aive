package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock

data class MemoryPromptRecall(
    val blocks: List<PromptContextBlock> = emptyList(),
    val memoryAddresses: Set<String> = emptySet(),
) {
    init {
        require(memoryAddresses.none(String::isBlank)) { "Memory recall addresses must not be blank" }
    }
}

fun interface MemoryPromptContextProvider {
    suspend fun recallFor(request: AgentTaskRequest): MemoryPromptRecall
}

private object NoOpMemoryPromptContextProvider : MemoryPromptContextProvider {
    override suspend fun recallFor(request: AgentTaskRequest): MemoryPromptRecall = MemoryPromptRecall()
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
