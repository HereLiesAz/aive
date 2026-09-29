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

    /** Attention Deficit Dial state, one per agent (task run). */
    private val attention = PerAgentAttention()

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
        }

        override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
            if (!active) return
            layer.sessionObserver.onSessionEvent(handle, event)
            val text = when (event) {
                is AgentEvent.Message -> event.content
                is AgentEvent.PlanGenerated -> event.summary
                else -> null
            }
            attention.forAgent(handle.taskRunId.value)
                .consumeTokens(AttentionGatedRecall.approximateTokens(text, APPROXIMATE_CHARS_PER_TOKEN))
        }

        override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
            if (!active) return
            layer.sessionObserver.onSessionFinished(handle, status)
            attention.forget(handle.taskRunId.value)
            mutableActivity.value = mutableActivity.value.copy(banked = mutableActivity.value.banked + 1)
            publish()
            drainSoon()
        }
    }

    val promptContextProvider = MemoryPromptContextProvider { request, queryPlan ->
        if (!active) return@MemoryPromptContextProvider MemoryPromptRecall()
        val context = request.orchestrationContext
        // The incoming task prompt is cognition the agent is about to spend; count it toward recovery.
        val agentAttention = attention.forAgent(request.taskRunId.value)
        agentAttention.consumeTokens(
            AttentionGatedRecall.approximateTokens(request.objective + request.roleInstructions, APPROXIMATE_CHARS_PER_TOKEN),
        )
        val hitsById = linkedMapOf<String, MemoryRecallHit>()
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
                ),
            )
            recall.hits.forEach { hit ->
                val current = hitsById[hit.node.id.value]
                if (current == null || hit.score > current.score) hitsById[hit.node.id.value] = hit
            }
        }
        val ranked = hitsById.values
            .sortedWith(compareByDescending<MemoryRecallHit> { it.score }.thenBy { it.node.id.value })
            .take(MAX_RECALL_RESULTS)
        // Cue-first: the Attention Deficit Dial decides whether deeper resolutions may surface.
        val hits = agentAttention.select(ranked)
        if (hits.isEmpty()) {
            MemoryPromptRecall()
        } else {
            MemoryPromptRecall(
                blocks = listOf(
                    PromptContextBlock(
                        "Relevant memory",
                        hits.joinToString("\n\n") { "[${it.score.twoDecimals()}] ${it.node.kind.name}: ${it.node.text}" }
                            .take(MAX_RECALL_CHARS),
                    ),
                ),
                memoryAddresses = hits.mapTo(linkedSetOf()) { "memory-node:${it.node.id.value}" },
                maxContextTokens = MAX_RECALL_CHARS / APPROXIMATE_CHARS_PER_TOKEN,
            )
        }
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
        const val MAX_RECALL_RESULTS = 6
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
