package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.hereliesaz.geministrator.orchestration.MemoryResolution as QueryResolution

/** What consolidation is doing right now, and what it did last. Drives the terrarium. */
data class MemoryActivity(
    /** Stage of the packet being processed now; null when idle. */
    val working: MemoryConsolidationStage? = null,
    val workingQueueId: MemoryQueueId? = null,
    val last: MemoryConsolidationResult? = null,
    val lastStage: MemoryConsolidationStage? = null,
    val lastAtEpochMillis: Long? = null,
    /** Sessions banked since the app started. */
    val banked: Int = 0,
)

/** One downloadable on-device clerk. */
data class MemoryLocalModelStatus(
    val role: MemoryMicroAgentRole,
    val name: String,
    val installed: Boolean,
    val installing: Boolean = false,
    val error: String? = null,
)

/** Platform hook for downloading and removing on-device memory models. */
interface MemoryLocalModelManager {
    val models: StateFlow<List<MemoryLocalModelStatus>>

    suspend fun install(role: MemoryMicroAgentRole)

    suspend fun remove(role: MemoryMicroAgentRole)
}

/**
 * The memory layer as one controllable unit, shared by every platform: store, user settings,
 * per-stage engines, consolidation, banking, recall, and the operations the Memory screen offers.
 *
 * Settings changes rebuild the layer between packets. [attach] makes it the app's memory; the
 * workflow gateway reads [MemoryRuntimeBridge.observer] when it is constructed, so attach before the
 * application runtime is created.
 */
@OptIn(ExperimentalTime::class)
class MemoryLayerController(
    private val store: MemoryStore,
    private val settingsStore: MemoryLayerSettingsStore,
    private val engineProvider: MemoryEngineProvider,
    private val scope: CoroutineScope,
    /** Null where the platform has no on-device memory models. */
    val localModels: MemoryLocalModelManager? = null,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val drainMutex = Mutex()

    val settings: StateFlow<MemoryLayerSettings> = settingsStore.state

    private val mutableEngines = MutableStateFlow(settings.value.assembleAgents(engineProvider, nowEpochMillis))
    /** What each stage actually runs after fallbacks. */
    val engines: StateFlow<MemoryEngineAssembly> = mutableEngines.asStateFlow()

    private val mutableSnapshot = MutableStateFlow(MemorySnapshot())
    val snapshot: StateFlow<MemorySnapshot> = mutableSnapshot.asStateFlow()

    private val mutableActivity = MutableStateFlow(MemoryActivity())
    val activity: StateFlow<MemoryActivity> = mutableActivity.asStateFlow()

    private var layer = build(settings.value)

    private val attentionPolicy = MemoryAttentionPolicy()

    /** Attention Deficit Dial state, one per agent (task run). */
    private val attention = PerAgentAttention(attentionPolicy)

    /** Per agent: the recall-trigger watcher, the session's scope, and recalls waiting for its next turn. */
    private class CueSession(
        val watcher: MemoryCueWatcher,
        val request: AgentTaskRequest?,
        val pending: MutableList<String> = mutableListOf(),
        /** The protocol is starting context: given once, not with every prompt or cue. */
        var protocolGiven: Boolean = false,
    )

    private val cueMutex = Mutex()
    private val cueSessions = mutableMapOf<String, CueSession>()

    private suspend fun cueSession(agentId: String, request: AgentTaskRequest? = null): CueSession = cueMutex.withLock {
        cueSessions.getOrPut(agentId) { CueSession(MemoryCueWatcher(attentionPolicy), request) }
    }

    /** Turns the dial for agents that have not started yet. */
    fun setDefaultAttentionLevel(level: Float) { attention.defaultLevel = level }

    /** Turns one running agent's dial persistently. */
    suspend fun setAttentionLevel(taskRunId: String, level: Float) = attention.setLevel(taskRunId, level)

    /** Temporarily lowers one agent's dial; its own token use recovers it. */
    suspend fun suppressAttention(taskRunId: String, level: Float) = attention.suppress(taskRunId, level)

    private val active: Boolean get() = settings.value.enabled

    init {
        scope.launch { publish() }
    }

    private fun build(settings: MemoryLayerSettings): AgentMemoryLayer =
        AgentMemoryLayer.createWithMicroAgents(store, mutableEngines.value.agents, settings.policy)

    // ---- settings -------------------------------------------------------------------------------

    /** Applies a settings change; the layer is rebuilt between packets, never during one. */
    suspend fun updateSettings(transform: (MemoryLayerSettings) -> MemoryLayerSettings) {
        drainMutex.withLock {
            val next = settingsStore.update(transform)
            mutableEngines.value = next.assembleAgents(engineProvider, nowEpochMillis)
            layer = build(next)
        }
        drainSoon()
    }

    // ---- consolidation --------------------------------------------------------------------------

    fun drainSoon() {
        scope.launch { drain() }
    }

    /** Consolidates until idle, paused or disabled. */
    suspend fun drain() = drainMutex.withLock {
        publish()
        while (active && !settings.value.consolidationPaused) {
            val next = store.read().nextWorkable(settings.value.policy)
            mutableActivity.value = mutableActivity.value.copy(working = next?.stage, workingQueueId = next?.id)
            val result = layer.consolidateOne(nowEpochMillis())
            mutableActivity.value = mutableActivity.value.copy(
                working = null,
                workingQueueId = null,
                last = result,
                lastStage = next?.stage ?: mutableActivity.value.lastStage,
                lastAtEpochMillis = nowEpochMillis(),
            )
            publish()
            if (result == MemoryConsolidationResult.Idle) break
        }
    }

    // ---- queue ----------------------------------------------------------------------------------

    /** Gives a parked entry a fresh set of attempts. */
    suspend fun retry(queueId: MemoryQueueId) = updateQueue(queueId) {
        it.copy(status = MemoryQueueStatus.Pending, attempt = 0)
    }

    /** Stops consolidating an entry. Its episode and anything already derived stay in memory. */
    suspend fun discard(queueId: MemoryQueueId) = updateQueue(queueId) {
        it.copy(status = MemoryQueueStatus.Complete, lastError = "Discarded: ${it.lastError.orEmpty()}".take(1_000))
    }

    suspend fun retryAllParked() {
        store.read().queue
            .filter { it.status == MemoryQueueStatus.Failed }
            .forEach { entry -> retry(entry.id) }
    }

    private suspend fun updateQueue(queueId: MemoryQueueId, change: (MemoryQueueEntry) -> MemoryQueueEntry) {
        drainMutex.withLock {
            while (true) {
                val snapshot = store.read()
                val entry = snapshot.queue.firstOrNull { it.id == queueId } ?: break
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(change(entry))))) break
            }
        }
        publish()
        drainSoon()
    }

    // ---- data -----------------------------------------------------------------------------------

    suspend fun exportJson(): String =
        SettingsMemoryStore.defaultJson.encodeToString(MemorySnapshot.serializer(), store.read())

    /** Replaces all memory with an exported graph. */
    suspend fun importJson(json: String) {
        val snapshot = SettingsMemoryStore.defaultJson.decodeFromString(MemorySnapshot.serializer(), json)
        drainMutex.withLock {
            store.replace(snapshot)
            layer = build(settings.value)
        }
        publish()
        drainSoon()
    }

    suspend fun clearAll() {
        drainMutex.withLock {
            store.replace(MemorySnapshot())
            layer = build(settings.value)
        }
        publish()
    }

    /**
     * Removes one episode and everything derived only from it. Memories that also came from other
     * episodes stay, without this episode in their provenance.
     */
    suspend fun forgetEpisode(episodeId: MemoryEpisodeId) {
        drainMutex.withLock {
            store.replace(store.read().without(episodeId))
            layer = build(settings.value)
        }
        publish()
    }

    // ---- banking and recall -----------------------------------------------------------------------

    val observer: MemorySessionObserver = object : MemorySessionObserver {
        override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) {
            if (!active) return
            layer.sessionObserver.onSessionStarted(handle, request)
            cueSession(handle.taskRunId.value, request)
        }

        override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
            if (!active) return
            layer.sessionObserver.onSessionEvent(handle, event)
            val text = when (event) {
                is AgentEvent.Message -> event.content
                is AgentEvent.PlanGenerated -> event.summary
                is AgentEvent.Thinking -> event.content
                else -> null
            }
            attention.forAgent(handle.taskRunId.value)
                .consumeTokens(AttentionGatedRecall.approximateTokens(text, APPROXIMATE_CHARS_PER_TOKEN))
        }

        override suspend fun onSessionEvent(
            handle: ManagedSessionHandle,
            event: AgentEvent,
            reply: suspend (String) -> com.hereliesaz.geministrator.providers.ProviderActionResult,
        ) {
            onSessionEvent(handle, event)
            if (!active) return
            val text = when (event) {
                is AgentEvent.Message -> event.content
                is AgentEvent.PlanGenerated -> event.summary
                is AgentEvent.Thinking -> event.content
                else -> null
            } ?: return
            if (text.isBlank() || text.startsWith(MEMORY_MESSAGE_MARKER)) return
            respondToThought(handle.taskRunId.value, text, reply)
        }

        override suspend fun annotateUserMessage(handle: ManagedSessionHandle, text: String): String {
            if (!active || text.isBlank()) return text
            val agentId = handle.taskRunId.value
            attention.forAgent(agentId).consumeTokens(AttentionGatedRecall.approximateTokens(text, APPROXIMATE_CHARS_PER_TOKEN))
            val cloud = runCatching { cueCloud(agentId, text) }.getOrNull() ?: return text
            return "$text\n\n$cloud"
        }

        override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
            if (!active) return
            layer.sessionObserver.onSessionFinished(handle, status)
            attention.forget(handle.taskRunId.value)
            cueMutex.withLock { cueSessions.remove(handle.taskRunId.value) }
            mutableActivity.value = mutableActivity.value.copy(banked = mutableActivity.value.banked + 1)
            publish()
            drainSoon()
        }
    }

    val promptContextProvider: MemoryPromptContextProvider = object : MemoryPromptContextProvider {
        override suspend fun recallFor(request: AgentTaskRequest, queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan): MemoryPromptRecall =
            recallForPrompt(request, queryPlan)

        override suspend fun queryHints(request: AgentTaskRequest): MemoryQueryHints =
            if (!active) MemoryQueryHints() else runCatching { queryHintsFor(request.objective) }.getOrDefault(MemoryQueryHints())

        override suspend fun feedbackTerms(
            request: AgentTaskRequest,
            queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan,
        ): Map<String, Int> = if (!active) emptyMap() else runCatching { feedbackTermsFor(request, queryPlan) }.getOrDefault(emptyMap())
    }

    /** One grip per planned query; each hit keeps its best score and whether a chronological query found it. */
    private suspend fun gripPlan(
        request: AgentTaskRequest,
        queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan,
    ): Pair<List<MemoryRecallHit>, Set<String>> {
        val context = request.orchestrationContext
        val hitsById = linkedMapOf<String, MemoryRecallHit>()
        val chronological = HashSet<String>()
        queryPlan.queries.forEach { querySpec ->
            val resolution = when (querySpec.resolution) {
                QueryResolution.Category -> MemoryResolution.Category
                QueryResolution.Summary -> MemoryResolution.Summary
                QueryResolution.Phrase -> MemoryResolution.Phrase
                QueryResolution.Entity, QueryResolution.Action -> MemoryResolution.Tag
                QueryResolution.GranularEvidence -> MemoryResolution.Context
            }
            val recall = layer.tool.grip(
                MemoryQuery(
                    text = querySpec.text,
                    resolution = resolution,
                    maxResults = MAX_RECALL_RESULTS,
                    projectId = context.projectId?.value,
                    workflowRunId = context.workflowRunId?.value,
                    workflowDefinitionId = context.workflowDefinitionId?.value,
                    taskRunId = request.taskRunId.value,
                    taskDefinitionId = context.taskDefinitionId?.value,
                    roleId = context.roleId?.value,
                    expansionTerms = querySpec.expansionTerms,
                ),
            )
            recall.hits.forEach { hit ->
                val current = hitsById[hit.node.id.value]
                if (current == null || hit.score > current.score) hitsById[hit.node.id.value] = hit
                if (querySpec.chronological) chronological += hit.node.id.value
            }
        }
        val ranked = hitsById.values
            .sortedWith(compareByDescending<MemoryRecallHit> { it.score }.thenBy { it.node.id.value })
            .take(MAX_RECALL_RESULTS)
        return ranked to chronological
    }

    /** The objective's entities, actions and code symbols, with each word's memory count. */
    private suspend fun queryHintsFor(objective: String): MemoryQueryHints {
        if (objective.isBlank()) return MemoryQueryHints()
        val code = extractCodeSemanticHints(objective)
        // Never wait on loading the language data at task start; the clerks load it in the background.
        val analysis = MemoryLanguageResources.loadedOrNull()?.let { resources -> runCatching { MemoryTextAnalyzer(resources).analyze(objective) }.getOrNull() }
        val entities = analysis?.entities().orEmpty()
            .filter { it.impliedBy == null && !it.negated }
            .map { it.text }
            .filterNot { entity -> code.nounCandidates.any { it.equals(entity, ignoreCase = true) } }
            .distinctBy(String::lowercase)
            .take(MAX_HINTS)
        val actions = (analysis?.actions().orEmpty().filter { !it.negated }.map { it.key } + code.verbCandidates)
            .distinctBy(String::lowercase)
            .take(MAX_HINTS)
        val symbols = code.nounCandidates.take(MAX_HINTS)
        val words = (listOf(objective) + entities + actions + symbols)
            .flatMap { com.hereliesaz.geministrator.orchestration.queryWords(it) }
            .toSet()
        val tool = layer.tool
        return MemoryQueryHints(
            entities = entities,
            actions = actions,
            codeSymbols = symbols,
            documentFrequency = words.associateWith { tool.termFrequency(it).memories },
        )
    }

    /**
     * Uncommon words from the top first-pass results that the plan did not already ask about, with
     * their memory counts. Reads only: nothing is gated, delivered or counted toward attention.
     */
    private suspend fun feedbackTermsFor(
        request: AgentTaskRequest,
        queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan,
    ): Map<String, Int> {
        val (ranked, _) = gripPlan(request, queryPlan)
        if (ranked.isEmpty()) return emptyMap()
        val asked = queryPlan.queries.flatMap { com.hereliesaz.geministrator.orchestration.queryWords(it.text) }.toSet()
        val tool = layer.tool
        val counts = linkedMapOf<String, Int>()
        ranked.take(FEEDBACK_RESULTS).forEach { hit ->
            MemorySalienceFeatures.terms(hit.node.text).filter { it !in asked && it !in counts }.forEach { term ->
                counts[term] = tool.termFrequency(term).memories
            }
        }
        return counts.filter { (word, count) -> count > 0 && !isCommonWord(tool, word) }
            .entries.sortedBy { it.value }.take(FEEDBACK_TERMS).associate { it.key to it.value }
    }

    private suspend fun recallForPrompt(
        request: AgentTaskRequest,
        queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan,
    ): MemoryPromptRecall {
        if (!active) return MemoryPromptRecall()
        // The incoming task prompt is cognition the agent is about to spend; count it toward recovery.
        val agentAttention = attention.forAgent(request.taskRunId.value)
        agentAttention.consumeTokens(
            AttentionGatedRecall.approximateTokens(request.objective + request.roleInstructions, APPROXIMATE_CHARS_PER_TOKEN),
        )
        val (ranked, chronological) = gripPlan(request, queryPlan)
        // Cue-first: the Attention Deficit Dial decides whether deeper resolutions may surface.
        val hits = agentAttention.select(ranked)
        val session = cueSession(request.taskRunId.value, request)
        // Recalls the agent asked for that its provider could not take mid-session arrive now.
        val (pending, firstPrompt) = cueMutex.withLock {
            val first = !session.protocolGiven
            session.protocolGiven = true
            session.pending.toList().also { session.pending.clear() } to first
        }
        // Hits a chronological query found read in time order, apart from the rest.
        val (timeline, relevant) = hits.partition { it.node.id.value in chronological }
        val blocks = buildList {
            if (firstPrompt) add(PromptContextBlock("Memory protocol", MEMORY_PROTOCOL))
            // The user's prompt draws its own cue cloud, like any thought.
            runCatching { cueCloud(request.taskRunId.value, request.objective, request, alongside = true) }.getOrNull()?.let { add(PromptContextBlock("Memory", it)) }
            if (pending.isNotEmpty()) {
                add(PromptContextBlock("Recalled on request", pending.joinToString("\n\n") { it.removePrefix(MEMORY_MESSAGE_MARKER).trim() }.take(MAX_RECALL_CHARS)))
            }
            if (relevant.isNotEmpty()) {
                add(
                    PromptContextBlock(
                        "Relevant memory",
                        relevant.joinToString("\n\n") { "[${it.score.twoDecimals()}] ${it.node.kind.name}: ${it.node.text}" }
                            .take(MAX_RECALL_CHARS),
                    ),
                )
            }
            if (timeline.isNotEmpty()) {
                add(
                    PromptContextBlock(
                        "Memory timeline",
                        timeline.sortedWith(compareBy<MemoryRecallHit> { it.node.createdAtEpochMillis }.thenBy { it.node.id.value })
                            .joinToString("\n\n") { "${it.node.kind.name}: ${it.node.text}" }
                            .take(MAX_RECALL_CHARS),
                    ),
                )
            }
        }
        return MemoryPromptRecall(
            blocks = blocks,
            memoryAddresses = hits.mapTo(linkedSetOf()) { "memory-node:${it.node.id.value}" },
            maxContextTokens = if (hits.isEmpty() && pending.isEmpty()) null else MAX_RECALL_CHARS / APPROXIMATE_CHARS_PER_TOKEN,
        )
    }

    // ---- recall triggers in the agent's own thinking -------------------------------------------

    /**
     * Reads a chunk of an agent's thinking or output. Triggers (#tag, "what I remember about …",
     * an echoed cue, a doubled word) recall summaries; otherwise the text's uncommon words may draw
     * an ambient cue cloud of #tags. Both go back into the session through [reply]; a provider that
     * cannot take a message mid-session gets them with its next turn's prompt.
     */
    private suspend fun respondToThought(
        agentId: String,
        text: String,
        reply: suspend (String) -> com.hereliesaz.geministrator.providers.ProviderActionResult,
    ) {
        val session = cueSession(agentId)
        val gate = attention.forAgent(agentId)
        val tool = layer.tool
        val isCommon: suspend (String) -> Boolean = { word -> isCommonWord(tool, word) }
        val triggers = cueMutex.withLock { session.watcher }.read(text, isCommon)

        suspend fun deliver(message: String) {
            val accepted = runCatching { reply(message) }.getOrNull() is com.hereliesaz.geministrator.providers.ProviderActionResult.Accepted
            if (!accepted) cueMutex.withLock {
                session.pending += message
                while (session.pending.size > MAX_PENDING_RECALLS) session.pending.removeAt(0)
            }
        }

        if (triggers.isNotEmpty()) {
            triggers.take(MAX_TRIGGERS_PER_THOUGHT).forEach { trigger ->
                val hits = recallSummaries(trigger, session.request)
                val shown = if (trigger.deliberate) hits else gate.select(hits)
                if (shown.isNotEmpty()) {
                    deliver(
                        buildString {
                            append(MEMORY_MESSAGE_MARKER).append(' ').append(MemoryCueWatcher.hashtag(trigger.subject)).append('\n')
                            shown.forEach { append("- ").append(it.node.text.take(MAX_SUMMARY_CHARS)).append('\n') }
                        }.trimEnd(),
                    )
                }
            }
            return
        }

        // Ambient cue cloud from the thought's uncommon words, paced by the dial.
        cueCloud(agentId, text)?.let { deliver(it) }
    }

    /**
     * The cue cloud for [text] (an agent's thought or a user's prompt): its uncommon words seed a
     * Tag recall, the dial decides whether cues surface, and surfaced cues count as offered so the
     * agent can follow them. Null when nothing surfaces.
     */
    private suspend fun cueCloud(agentId: String, text: String, request: AgentTaskRequest? = null, alongside: Boolean = false): String? {
        val session = cueSession(agentId, request)
        val gate = attention.forAgent(agentId)
        val tool = layer.tool
        val words = CONTENT_WORD.findAll(text.lowercase()).map { it.value }.filter { it !in CLOUD_STOPWORDS }.distinct().toList()
        val cueWords = words.filterNot { isCommonWord(tool, it) }.take(MAX_CLOUD_QUERY_WORDS)
        if (cueWords.isEmpty()) return null
        val scope = request ?: session.request
        // Each uncommon word seeds its own cues (a word should not be diluted by its neighbours);
        // a tag reached by several words keeps its best score.
        val best = LinkedHashMap<MemoryNodeId, MemoryRecallHit>()
        cueWords.forEach { word ->
            tool.grip(
                MemoryQuery(
                    text = word,
                    resolution = MemoryResolution.Tag,
                    maxResults = MAX_RECALL_RESULTS,
                    projectId = scope?.orchestrationContext?.projectId?.value,
                    taskRunId = agentId,
                ),
            ).hits.filter { it.node.kind in AttentionGatedRecall.CUE_KINDS }.forEach { hit ->
                if ((best[hit.node.id]?.score ?: -1f) < hit.score) best[hit.node.id] = hit
            }
        }
        val hits = best.values.sortedByDescending { it.score }.take(MAX_RECALL_RESULTS)
        val cues = (if (alongside) gate.selectAlongside(hits) else gate.select(hits)).take(MemoryAttentionGate(attentionPolicy).cuePolicy(gate.currentState()).maxTags)
        if (cues.isEmpty()) return null
        cueMutex.withLock { session.watcher.offered(cues.map { it.node.text }) }
        return "$MEMORY_MESSAGE_MARKER " + cues.joinToString(" ") { MemoryCueWatcher.hashtag(it.node.text) }
    }

    private suspend fun recallSummaries(trigger: MemoryRecallTrigger, request: AgentTaskRequest?): List<MemoryRecallHit> {
        val context = request?.orchestrationContext
        val tool = layer.tool
        return when (trigger) {
            is MemoryRecallTrigger.Phrase -> tool.grip(
                MemoryQuery(
                    text = trigger.subject,
                    resolution = MemoryResolution.Summary,
                    maxResults = MAX_TRIGGER_SUMMARIES,
                    projectId = context?.projectId?.value,
                    taskRunId = request?.taskRunId?.value,
                ),
            ).hits
            else -> tool.grip(
                MemoryTagQuery(
                    tags = listOf(trigger.subject),
                    resolution = MemoryResolution.Summary,
                    maxResults = MAX_TRIGGER_SUMMARIES,
                    scope = MemoryBankScope(projectId = context?.projectId?.value, taskRunId = request?.taskRunId?.value),
                ),
            ).hits
        }
    }

    /** The frequency filter: a word in more than the policy's share (and count) of memories is common. */
    private suspend fun isCommonWord(tool: MemoryTool, word: String): Boolean {
        val frequency = tool.termFrequency(word)
        if (frequency.total == 0) return false
        val limit = maxOf(attentionPolicy.commonWordMinimumMemories.toFloat(), attentionPolicy.commonWordShare * frequency.total)
        return frequency.memories > limit
    }

    /** Makes this the app's memory and starts consolidating any backlog. */
    fun attach() {
        MemoryRuntimeBridge.observer = observer
        MemoryRuntimeBridge.promptContextProvider = promptContextProvider
        drainSoon()
    }

    fun detach() {
        if (MemoryRuntimeBridge.observer === observer) MemoryRuntimeBridge.reset()
    }

    private suspend fun publish() {
        mutableSnapshot.value = store.read()
    }

    private companion object {
        const val MAX_TRIGGERS_PER_THOUGHT = 3
        const val MAX_TRIGGER_SUMMARIES = 3
        const val MAX_SUMMARY_CHARS = 400
        const val MAX_PENDING_RECALLS = 8
        const val MAX_CLOUD_QUERY_WORDS = 12
        val CONTENT_WORD = Regex("[a-z][a-z0-9_-]{3,}")
        val CLOUD_STOPWORDS = setOf(
            "that", "this", "with", "from", "have", "will", "would", "should", "could", "there", "their", "they", "them",
            "then", "than", "what", "when", "where", "which", "while", "about", "into", "also", "just", "only", "very",
            "some", "more", "most", "other", "such", "need", "make", "like", "want", "know", "think", "look", "here",
            "were", "been", "being", "does", "done", "each", "your", "let's", "it's", "i'll", "going",
        )

        /**
         * A hint, not a requirement: recall runs on what an agent thinks and writes anyway (cue
         * clouds, echoes, doubled words). This only tells it what the marked lines are, once.
         */
        val MEMORY_PROTOCOL = "Lines starting $MEMORY_MESSAGE_MARKER are your memory. #tags are things you remember; " +
            "dwell on one (or write \"Let me see what I remember about …\") and more of it comes back."

        const val MAX_RECALL_RESULTS = 6
        const val MAX_HINTS = 4
        const val FEEDBACK_RESULTS = 3
        const val FEEDBACK_TERMS = 5
        const val MAX_RECALL_CHARS = 6_000
        const val APPROXIMATE_CHARS_PER_TOKEN = 4
    }
}

/** The entry [MemoryConsolidator.processNext] would pick next, for display. */
internal fun MemorySnapshot.nextWorkable(policy: MemoryConsolidationPolicy): MemoryQueueEntry? = queue
    .asSequence()
    .filter { it.status != MemoryQueueStatus.Complete }
    .filterNot { it.status == MemoryQueueStatus.Failed && it.attempt >= policy.maxAttempts }
    .sortedWith(compareByDescending<MemoryQueueEntry> { it.priority.ordinal }.thenBy { it.sequence })
    .firstOrNull()

/** Parked entries: failed [MemoryConsolidationPolicy.maxAttempts] times and skipped until retried. */
fun MemorySnapshot.parked(policy: MemoryConsolidationPolicy): List<MemoryQueueEntry> =
    queue.filter { it.status == MemoryQueueStatus.Failed && it.attempt >= policy.maxAttempts }

/** The graph without [episodeId] and anything derived only from it. */
internal fun MemorySnapshot.without(episodeId: MemoryEpisodeId): MemorySnapshot {
    val sections = sections.filter { it.episodeId != episodeId }
    val sectionIds = sections.mapTo(hashSetOf()) { it.id }
    val nodes = nodes.mapNotNull { node ->
        if (episodeId !in node.sourceEpisodeIds) return@mapNotNull node
        val remaining = node.sourceEpisodeIds - episodeId
        if (remaining.isEmpty()) null else node.copy(
            sourceEpisodeIds = remaining,
            sourceSectionIds = node.sourceSectionIds.filterTo(linkedSetOf()) { it in sectionIds },
        )
    }
    val nodeIds = nodes.mapTo(hashSetOf()) { it.id }
    return copy(
        revision = revision + 1,
        episodes = episodes.filter { it.id != episodeId },
        sections = sections,
        nodes = nodes,
        edges = edges.filter { it.from in nodeIds && it.to in nodeIds },
        queue = queue.filter { it.episodeId != episodeId },
    )
}

private fun Float.twoDecimals(): String {
    val hundredths = (this * 100).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
