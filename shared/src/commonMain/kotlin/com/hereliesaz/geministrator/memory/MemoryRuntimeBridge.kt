package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.orchestration.MemoryQueryPlan
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock

data class MemoryPromptRecall(
    val blocks: List<PromptContextBlock> = emptyList(),
    val memoryAddresses: Set<String> = emptySet(),
    /** Optional provider-context budget used by the local Context Packer. */
    val maxContextTokens: Int? = null,
) {
    init {
        require(memoryAddresses.none(String::isBlank)) { "Memory recall addresses must not be blank" }
    }
}

/** What the Memory Query Composer is given beyond the objective: the request's terms and their rarity. */
data class MemoryQueryHints(
    val entities: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val codeSymbols: List<String> = emptyList(),
    /** Stored memories per lowercase word, for the words above and the objective's. */
    val documentFrequency: Map<String, Int> = emptyMap(),
)

fun interface MemoryPromptContextProvider {
    suspend fun recallFor(request: AgentTaskRequest, queryPlan: MemoryQueryPlan): MemoryPromptRecall

    /** Entities, actions and code symbols in the request, with how many memories hold each word. */
    suspend fun queryHints(request: AgentTaskRequest): MemoryQueryHints = MemoryQueryHints()

    /**
     * A side-effect-free first pass: the uncommon words of the top results for [queryPlan], each with
     * its memory count, for a second-pass query. Empty when there are none.
     */
    suspend fun feedbackTerms(request: AgentTaskRequest, queryPlan: MemoryQueryPlan): Map<String, Int> = emptyMap()
}

private object NoOpMemoryPromptContextProvider : MemoryPromptContextProvider {
    override suspend fun recallFor(
        request: AgentTaskRequest,
        queryPlan: MemoryQueryPlan,
    ): MemoryPromptRecall = MemoryPromptRecall()
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
