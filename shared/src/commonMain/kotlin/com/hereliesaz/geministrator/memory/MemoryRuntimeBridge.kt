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

/**
 * One recipient's cue state within one direct conversation. No messages or recalled passages are
 * persisted by this interface; implementations may retain bounded attention/novelty state.
 */
fun interface DirectChatCueSession {
    suspend fun tags(text: String): List<String>

    /** Advance attention from the recipient's own reply without banking it. */
    suspend fun observeReply(text: String) {}
}

/**
 * Query-local transactive directory pointer. Counts distinct retained agent episodes associated
 * with the query, not verified facts, competence, consensus or collaboration quality.
 */
data class CollectiveExpertiseMatch(val roleId: String, val specialistId: String, val matchedEpisodes: Int)

fun interface MemoryPromptContextProvider {
    suspend fun recallFor(request: AgentTaskRequest, queryPlan: MemoryQueryPlan): MemoryPromptRecall

    /** Starting a new provider invocation must not reuse a previous session's pending recall or attention. */
    suspend fun recallForNewSession(request: AgentTaskRequest, queryPlan: MemoryQueryPlan): MemoryPromptRecall =
        recallFor(request, queryPlan)

    /**
     * Cue-only recall in the caller's authorized project/lineage corpus and shared lessons.
     * The recipient role identifies attention state, not read permission.
     */
    suspend fun directChatTags(workflowIds: List<String>, roleId: String, text: String): List<String> = emptyList()

    /** Each participant opens its own attention session, even when participants share a role. */
    fun openDirectChat(workflowIds: List<String>, roleId: String): DirectChatCueSession =
        DirectChatCueSession { text -> directChatTags(workflowIds, roleId, text) }

    /** Resume a host-assigned participant in a stable conversation. The identity is not a memory grant. */
    fun resumeDirectChat(workflowIds: List<String>, roleId: String, conversationId: String,
        participantId: String): DirectChatCueSession = openDirectChat(workflowIds, roleId)

    /**
     * Host-captured actual collective dialogue, deposited only into its own conversation bank.
     * Implementations must honor disabled memory and never promote agent replies to Keeper speech.
     * Ordinary direct-chat observation remains cue-only, not an implicit banking authority.
     */
    suspend fun bankCollectiveContributions(conversationId: String, requests: List<MemoryBankRequest>) {}

    /**
     * Persist actual returned collective replies before the next inference, without exposing them
     * to recall. The subsequent bank call releases them; startup recovers interrupted turns.
     */
    suspend fun stageCollectiveContributions(conversationId: String, requests: List<MemoryBankRequest>) {}

    /** Stage one actual direct agent reply in its host-selected existing workflow, not a recall grant. */
    suspend fun stageRunDirectReply(workflowId: String, request: MemoryBankRequest) {}

    /** Release staged replies only when the exchange exits; never recreate cleared journal entries. */
    suspend fun bankRunDirectReplies(workflowId: String, sourceIds: List<String>) {}

    /**
     * Explicit Keeper import of retained historical utterances into a never-before-registered
     * conversation bank. Null means refused/unavailable; the count is actual queued utterances.
     */
    suspend fun importCollectiveHistory(conversationId: String, requests: List<MemoryBankRequest>): Int? = null

    /**
     * Discover prior contributors from the exact caller-authorized collective banks. No raw memory
     * or new permissions are returned; implementations must honor disabled memory and forgetting.
     */
    suspend fun collectiveExpertise(workflowIds: List<String>, question: String): List<CollectiveExpertiseMatch> = emptyList()

    /**
     * Host-local directory for an exact managed project/run. Only retained provider messages with
     * attributable matching sections contribute. No cross-run/project or role-bank discovery.
     */
    suspend fun managedExpertise(projectId: String, runId: String, question: String): List<CollectiveExpertiseMatch> = emptyList()

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
