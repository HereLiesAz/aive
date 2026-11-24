package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.orchestration.MemoryQueryPlan
import com.hereliesaz.geministrator.memory.procedural.NoOpProcedureTraceReader
import com.hereliesaz.geministrator.memory.procedural.NoOpProcedureTraceSink
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceReader
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
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

    /** Cue-only recall for a one-shot model conversation, restricted to caller-authorized existing banks. */
    suspend fun directChatTags(workflowIds: List<String>, roleId: String, text: String): List<String> = emptyList()

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
    /** Optional system-executor observer, enabled only by MemoryLayerSettings. */
    var procedureTraceSink: ProcedureTraceSink = NoOpProcedureTraceSink
    /** Read-only receipts for role-instruction proposals; never an execution path. */
    var procedureTraceReader: ProcedureTraceReader = NoOpProcedureTraceReader
    /** Optional clerk that adds recall to the caution recognizer; null leaves the recognizer alone. */
    var cautionClerk: com.hereliesaz.geministrator.memory.procedural.CautionClerk? = null
    /** The habit replay state (docs/architecture/PROCEDURAL_MEMORY.md, section 11); forgetting and clearing erase it. */
    var replayLedger: com.hereliesaz.geministrator.memory.procedural.HabitReplayLedger? = null
    /** Tasks an approved executor habit proposes for an objective; each is gated when added. Null while memory is not attached. */
    /** True while memory is attached and habit learning is switched on; replay learns and replays only then. */
    var habitLearningOn: () -> Boolean = { false }
    var habitStepProposer: (suspend (String) -> List<com.hereliesaz.geministrator.domain.TaskDefinition>)? = null
    /** The one task an approved executor habit an agent chose (`proc:<operation>@<version>`) proposes, or why not; null while memory is not attached. */
    var habitRefProposer: (suspend (ref: String, idPrefix: String) -> com.hereliesaz.geministrator.memory.procedural.HabitInvocation.Result)? = null

    fun reset() {
        observer = NoOpMemorySessionObserver
        promptContextProvider = NoOpMemoryPromptContextProvider
        procedureTraceSink = NoOpProcedureTraceSink
        procedureTraceReader = NoOpProcedureTraceReader
        cautionClerk = null
        habitStepProposer = null
        habitRefProposer = null
        habitLearningOn = { false }
    }
}
