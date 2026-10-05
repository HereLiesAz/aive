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
 * The memory layer as one controllable unit, shared by every platform: workflow banks, user
 * settings, per-stage engines, consolidation, banking, recall, and the operations the Memory screen
 * offers.
 *
 * Every workflow run has its own memory bank ([MemoryBanks]); its lineage bank reads through its
 * ancestors' ([LineageMemoryStore], [MemoryLineage]). A session banks into its workflow's bank and
 * recalls from that workflow's lineage first, then, read-only and labelled, from the other workflows
 * of its project. Each workflow has its own complete memory layer over its lineage bank (queue,
 * clerks, consolidation, associations, register). The screen's data operations act on the workflow
 * selected with [selectBank].
 *
 * Settings changes rebuild the layer between packets. [attach] makes it the app's memory; the
 * workflow gateway reads [MemoryRuntimeBridge.observer] when it is constructed, so attach before the
 * application runtime is created.
 */
@OptIn(ExperimentalTime::class)
class MemoryLayerController(
    private val banks: MemoryBanks,
    private val settingsStore: MemoryLayerSettingsStore,
    private val engineProvider: MemoryEngineProvider,
    private val scope: CoroutineScope,
    /** Null where the platform has no on-device memory models. */
    val localModels: MemoryLocalModelManager? = null,
    /** The workflow lineage DAG and project membership (add-only). */
    val lineage: MemoryLineage = MemoryLineage(),
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val drainMutex = Mutex()

    val settings: StateFlow<MemoryLayerSettings> = settingsStore.state

    private val mutableEngines = MutableStateFlow(settings.value.assembleAgents(engineProvider, nowEpochMillis))
    /** What each stage actually runs after fallbacks. */
    val engines: StateFlow<MemoryEngineAssembly> = mutableEngines.asStateFlow()

    private val mutableSnapshot = MutableStateFlow(MemorySnapshot())
    /** The selected workflow's lineage bank (its own records and its ancestors'). */
    val snapshot: StateFlow<MemorySnapshot> = mutableSnapshot.asStateFlow()

    private val mutableSelectedBank = MutableStateFlow<String?>(null)
    /** The workflow whose bank the Memory screen shows and acts on; null until one exists. */
    val selectedBank: StateFlow<String?> = mutableSelectedBank.asStateFlow()

    private val mutableRawUsage = MutableStateFlow(MemoryRawUsage())
    /** Raw history (full session context) held across every workflow bank. */
    val rawUsage: StateFlow<MemoryRawUsage> = mutableRawUsage.asStateFlow()

    private val mutableKnownBanks = MutableStateFlow<List<String>>(emptyList())
    /** Every workflow with a memory bank. */
    val knownBanks: StateFlow<List<String>> = mutableKnownBanks.asStateFlow()

    private val mutableActivity = MutableStateFlow(MemoryActivity())
    val activity: StateFlow<MemoryActivity> = mutableActivity.asStateFlow()

    /** One memory layer per workflow, over its lineage bank; rebuilt after a settings change. */
    private val layers = HashMap<String, AgentMemoryLayer>()
    private val layersMutex = Mutex()

    private suspend fun lineageStore(workflowId: String): LineageMemoryStore =
        LineageMemoryStore(workflowId, banks.store(workflowId)) { lineage.ancestorsOf(workflowId).map { banks.store(it) } }

    private suspend fun layerFor(workflowId: String): AgentMemoryLayer {
        val store = lineageStore(workflowId)
        return layersMutex.withLock { layers.getOrPut(workflowId) { build(store, settings.value) } }
    }

    private suspend fun resetLayers() = layersMutex.withLock { layers.clear() }

    /** Workflow of each running agent (task run), fixed when its session starts. */
    private val agentWorkflows = HashMap<String, String>()

    private suspend fun workflowOfAgent(agentId: String): String = cueMutex.withLock {
        agentWorkflows[agentId] ?: cueSessions[agentId]?.request?.let { memoryWorkflowOf(it) } ?: memoryWorkflowOf(null, agentId)
    }

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

    /** Links memories that keep being delivered together (adds edges only). */
    private val coRecall = MemoryCoRecall(nowEpochMillis)

    /**
     * Records one add-only access event (a `Recalled` link from the resolving deliberation to the
     * memory, in the reading workflow's own bank)
     * for each delivered resolved memory: its contradiction history fades as these accumulate.
     */
    private fun recordAccess(layer: AgentMemoryLayer, hits: List<MemoryRecallHit>) {
        val resolved = hits.filter { it.resolution != null && it.provenance?.readOnlyFromWorkflow == null }
        if (resolved.isEmpty()) return
        val store = layer.store
        scope.launch {
            runCatching {
                while (true) {
                    val snapshot = store.read()
                    val existing = snapshot.edges.mapTo(hashSetOf()) { it.id }
                    val edges = resolved.map { hit ->
                        val count = snapshot.edges.count { it.relation == MemoryRelationKind.Recalled && it.to == hit.node.id }
                        var n = count + 1
                        while (MemoryEdgeId("recalled:${hit.node.id.value}:$n") in existing) n += 1
                        // From the resolving deliberation to the memory (an edge needs two distinct ends).
                        MemoryEdge(MemoryEdgeId("recalled:${hit.node.id.value}:$n"), hit.resolution!!.deliberation.id, hit.node.id, MemoryRelationKind.Recalled, createdAtEpochMillis = nowEpochMillis())
                    }
                    if (store.commit(snapshot.revision, MemoryStoreMutation(edgesToAdd = edges))) break
                }
            }
        }
    }

    /** Explicit history of a memory in [workflowId]'s lineage: what it replaced and was condensed from. */
    suspend fun history(workflowId: String, nodeId: MemoryNodeId): List<MemoryNode> = lineageStore(workflowId).read().historyOf(nodeId)

    /** Co-recall links only memories of the workflow's own lineage; read-only hits are never linked. */
    private fun recordCoRecall(layer: AgentMemoryLayer, hits: List<MemoryRecallHit>) {
        val own = hits.filter { it.provenance?.readOnlyFromWorkflow == null }
        if (own.size < 2) return
        val store = layer.store
        val ids = own.map { it.node.id }
        scope.launch { runCatching { coRecall.recalledTogether(store, ids) } }
    }
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

    private fun build(store: MemoryStore, settings: MemoryLayerSettings): AgentMemoryLayer =
        AgentMemoryLayer.createWithMicroAgents(store, mutableEngines.value.agents, settings.policy)

    /** Shows (and directs the screen's data operations at) [workflowId]'s bank. */
    suspend fun selectBank(workflowId: String) {
        mutableSelectedBank.value = requireNotNull(memoryBankKey(workflowId))
        publish()
    }

    private suspend fun selectedWorkflow(): String? =
        selectedBank.value ?: banks.known().firstOrNull()?.also { mutableSelectedBank.value = it }

    /** The selected workflow's own bank (only the records it appended). */
    private suspend fun selectedOwnStore(): MemoryStore? = selectedWorkflow()?.let { banks.store(it) }

    private suspend fun requireSelectedOwnStore(): MemoryStore =
        selectedOwnStore() ?: throw IllegalStateException("No workflow's memory bank is selected")

    // ---- lineage --------------------------------------------------------------------------------

    /**
     * Records [workflowId] (in [projectId]) and its [parents]: one parent continues a lineage, several
     * marry lineages. Add-only. When a workflow gains a second (or further) parent, the contrasts
     * between the married lineages are marked in its bank; nothing is resolved.
     */
    suspend fun registerWorkflow(workflowId: String, projectId: String?, parents: List<String> = emptyList()) {
        val change = lineage.registerWorkflow(workflowId, projectId, parents, nowEpochMillis())
        banks.store(workflowId)
        if (change.newParents.isNotEmpty() && lineage.isMerge(workflowId)) markMergeContrasts(workflowId)
    }

    /** Appends: [projectId] now also includes every workflow of [incorporatesProjectId]. */
    suspend fun expandProject(projectId: String, incorporatesProjectId: String, by: String, reason: String): MemoryProjectExpansion =
        lineage.expandProject(projectId, incorporatesProjectId, by, reason, nowEpochMillis())

    private suspend fun markMergeContrasts(workflowId: String) {
        val store = layerFor(workflowId).store
        val parents = lineage.parentsOf(workflowId)
        while (true) {
            val view = store.read()
            val producers = view.nodes.associate { it.id to it.metadata[PRODUCED_BY_WORKFLOW] }
            val sides = parents.map { parent ->
                val side = (lineage.ancestorsOf(parent) + parent).toSet()
                producers.filterValues { it in side }.keys
            }
            val mutation = MemoryVariantRegister.mergeMutationFor(view, sides, nowEpochMillis())
            if (mutation.edgesToAdd.isEmpty() && mutation.nodesToAdd.isEmpty()) return
            if (store.commit(view.revision, mutation)) return
        }
    }

    // ---- settings -------------------------------------------------------------------------------

    /** Applies a settings change; the layer is rebuilt between packets, never during one. */
    suspend fun updateSettings(transform: (MemoryLayerSettings) -> MemoryLayerSettings) {
        drainMutex.withLock {
            val next = settingsStore.update(transform)
            mutableEngines.value = next.assembleAgents(engineProvider, nowEpochMillis)
            resetLayers()
        }
        drainSoon()
    }

    // ---- consolidation --------------------------------------------------------------------------

    fun drainSoon() {
        scope.launch {
            drain()
            // The user's raw-retention setting, if any (the default keeps everything).
            runCatching { applyRawRetention() }
        }
    }

    /** Consolidates until idle, paused or disabled. */
    /** Consolidates every workflow's queue, each over its own lineage bank, until idle, paused or disabled. */
    suspend fun drain() = drainMutex.withLock {
        publish()
        for (workflow in banks.known()) {
            val layer = layerFor(workflow)
            while (active && !settings.value.consolidationPaused) {
                val next = layer.store.read().nextWorkable(settings.value.policy)
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
        (selectedOwnStore() ?: return).read().queue
            .filter { it.status == MemoryQueueStatus.Failed }
            .forEach { entry -> retry(entry.id) }
    }

    private suspend fun updateQueue(queueId: MemoryQueueId, change: (MemoryQueueEntry) -> MemoryQueueEntry) {
        drainMutex.withLock {
            val store = selectedOwnStore() ?: return@withLock
            while (true) {
                val snapshot = store.read()
                val entry = snapshot.queue.firstOrNull { it.id == queueId } ?: break
                if (store.commit(snapshot.revision, MemoryStoreMutation(queueUpserts = listOf(change(entry))))) break
            }
        }
        publish()
        drainSoon()
    }

    // ---- raw retention ----------------------------------------------------------------------------

    /**
     * Applies the user's raw-retention settings: per project ([MemoryLayerSettings.rawRetentionByProject])
     * or global. With the default (keep all) nothing happens. Otherwise the selected episodes' raw text
     * is purged with a tombstone — the one audited exception to add-only. Returns the purged episodes.
     */
    suspend fun applyRawRetention(): List<MemoryEpisodeId> = drainMutex.withLock {
        val current = settings.value
        val byScope = banks.known().groupBy { workflow ->
            val project = lineage.projectOf(workflow)
            if (project != null && project in current.rawRetentionByProject) "project:$project" else "global"
        }
        val purged = mutableListOf<MemoryEpisodeId>()
        byScope.forEach { (scope, workflows) ->
            val retention = if (scope == "global") current.rawRetention else current.rawRetentionByProject.getValue(scope.removePrefix("project:"))
            val stores = workflows.associateWith { banks.store(it) }
            // Raw history still waiting for consolidation is never purged.
            val episodes = stores.values.flatMap { store ->
                val snapshot = store.read()
                val pending = snapshot.queue.filter { it.status != MemoryQueueStatus.Complete }.mapTo(hashSetOf()) { it.episodeId }
                snapshot.episodes.filter { it.id !in pending }
            }
            val selected = rawPurgeSelection(episodes, retention, nowEpochMillis())
            if (selected.isEmpty()) return@forEach
            stores.values.forEach { store ->
                val snapshot = store.read()
                val mine = snapshot.episodes.map { it.id }.filter { it in selected }.toSet()
                if (mine.isNotEmpty()) {
                    store.replace(snapshot.purgeRaw(mine, retention, scope, nowEpochMillis()))
                    purged += mine
                }
            }
        }
        if (purged.isNotEmpty()) resetLayers()
        purged
    }.also { publish() }

    // ---- data -----------------------------------------------------------------------------------

    /** The selected workflow's own records (not its ancestors', which stay in their own banks). */
    suspend fun exportJson(): String =
        SettingsMemoryStore.defaultJson.encodeToString(MemorySnapshot.serializer(), requireSelectedOwnStore().read())

    /**
     * Replaces the selected workflow's own records with an exported graph. Refused
     * ([MemoryCrossBankException]) when the export holds another workflow's episode.
     */
    suspend fun importJson(json: String) {
        val snapshot = SettingsMemoryStore.defaultJson.decodeFromString(MemorySnapshot.serializer(), json)
        drainMutex.withLock {
            requireSelectedOwnStore().replace(snapshot)
            resetLayers()
        }
        publish()
        drainSoon()
    }

    /** Clears the selected workflow's own records. Ancestors' and other workflows' banks are untouched. */
    suspend fun clearAll() {
        drainMutex.withLock {
            (selectedOwnStore() ?: return@withLock).replace(MemorySnapshot())
            resetLayers()
        }
        publish()
    }

    /**
     * Removes one episode and everything derived only from it. Memories that also came from other
     * episodes stay, without this episode in their provenance.
     */
    suspend fun forgetEpisode(episodeId: MemoryEpisodeId) {
        drainMutex.withLock {
            val store = selectedOwnStore() ?: return@withLock
            store.replace(store.read().without(episodeId))
            resetLayers()
        }
        publish()
    }

    // ---- banking and recall -----------------------------------------------------------------------

    val observer: MemorySessionObserver = object : MemorySessionObserver {
        override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) {
            if (!active) return
            val workflow = memoryWorkflowOf(request, handle.providerRunId.value)
            registerWorkflow(
                workflow,
                request.orchestrationContext.projectId?.value,
                request.orchestrationContext.parentWorkflowRunIds.map { it.value },
            )
            cueMutex.withLock { agentWorkflows[handle.taskRunId.value] = workflow }
            layerFor(workflow).sessionObserver.onSessionStarted(handle, request)
            cueSession(handle.taskRunId.value, request)
        }

        override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
            if (!active) return
            layerFor(workflowOfAgent(handle.taskRunId.value)).sessionObserver.onSessionEvent(handle, event)
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
            layerFor(workflowOfAgent(handle.taskRunId.value)).sessionObserver.onSessionFinished(handle, status)
            attention.forget(handle.taskRunId.value)
            cueMutex.withLock {
                cueSessions.remove(handle.taskRunId.value)
                agentWorkflows.remove(handle.taskRunId.value)
            }
            mutableActivity.value = mutableActivity.value.copy(banked = mutableActivity.value.banked + 1)
            publish()
            drainSoon()
        }
    }

    val promptContextProvider: MemoryPromptContextProvider = object : MemoryPromptContextProvider {
        override suspend fun recallFor(request: AgentTaskRequest, queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan): MemoryPromptRecall =
            recallForPrompt(request, queryPlan)

        override suspend fun queryHints(request: AgentTaskRequest): MemoryQueryHints =
            if (!active) MemoryQueryHints() else runCatching { queryHintsFor(request) }.getOrDefault(MemoryQueryHints())

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
        val workflow = memoryWorkflowOf(request)
        val layer = layerFor(workflow)
        val hitsById = linkedMapOf<String, MemoryRecallHit>()
        val chronological = HashSet<String>()
        queryPlan.queries.forEach { querySpec ->
            val resolution = querySpec.resolution.toMemoryResolution()
            // The lineage bank is the scope: no project filter (an ancestor may predate a project expansion).
            val recall = layer.tool.grip(
                MemoryQuery(
                    text = querySpec.text,
                    resolution = resolution,
                    maxResults = MAX_RECALL_RESULTS,
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
        val own = withProvenance(
            workflow,
            hitsById.values
                .sortedWith(compareByDescending<MemoryRecallHit> { it.score }.thenBy { it.node.id.value })
                .take(MAX_RECALL_RESULTS),
        )
        // Then, read-only and labelled, the other workflows of the project, ranked after the lineage.
        val readOnly = readOnlyHits(workflow) { tool ->
            queryPlan.queries.flatMap { querySpec ->
                val hits = tool.grip(MemoryQuery(querySpec.text, querySpec.resolution.toMemoryResolution(), MAX_RECALL_RESULTS, expansionTerms = querySpec.expansionTerms)).hits
                if (querySpec.chronological) hits.forEach { chronological += it.node.id.value }
                hits
            }
        }
        return own + readOnly to chronological
    }

    /**
     * Recall for [workflowId] without gating or side effects: its lineage bank first, then, read-only
     * and labelled, the other workflows of its project. Every hit carries its provenance.
     */
    suspend fun recallFor(
        workflowId: String,
        text: String,
        resolution: MemoryResolution = MemoryResolution.Context,
        maxResults: Int = MAX_RECALL_RESULTS,
    ): List<MemoryRecallHit> {
        val query = MemoryQuery(text, resolution, maxResults)
        return withProvenance(workflowId, layerFor(workflowId).tool.grip(query).hits) + readOnlyHits(workflowId) { it.grip(query).hits }
    }

    /** Tags each hit of [reader]'s lineage with its producing workflow and the lineage path from it. */
    private suspend fun withProvenance(reader: String, hits: List<MemoryRecallHit>): List<MemoryRecallHit> {
        val view = layerFor(reader).store.read()
        val episodes = view.episodes.associateBy { it.id }
        return hits.map { hit ->
            val producer = hit.node.metadata[PRODUCED_BY_WORKFLOW]
                ?: hit.node.sourceEpisodeIds.firstNotNullOfOrNull { episodes[it]?.workflowRunId }
            hit.copy(
                provenance = MemoryHitProvenance(
                    producedByWorkflow = producer,
                    producedBySession = hit.node.metadata[PRODUCED_BY_SESSION],
                    projectId = producer?.let { lineage.projectOf(it) },
                    lineagePath = producer?.let { lineage.lineagePath(it, reader) },
                ),
            )
        }
    }

    /**
     * Hits from the own banks of the other workflows of [reader]'s project (not its lineage), best
     * first per workflow, labelled. Read-only: nothing is written to either bank.
     */
    private suspend fun readOnlyHits(reader: String, search: suspend (MemoryTool) -> List<MemoryRecallHit>): List<MemoryRecallHit> =
        lineage.readOnlyNeighboursOf(reader).flatMap { other ->
            val store = banks.store(other)
            search(GraphMemoryTool(store, MemoryConsolidationQueue(store)))
                .groupBy { it.node.id }.values.map { group -> group.maxBy(MemoryRecallHit::score) }
                .sortedWith(compareByDescending<MemoryRecallHit> { it.score }.thenBy { it.node.id.value })
                .take(MAX_RECALL_RESULTS)
                .map { hit ->
                    hit.copy(
                        provenance = MemoryHitProvenance(
                            producedByWorkflow = hit.node.metadata[PRODUCED_BY_WORKFLOW] ?: other,
                            producedBySession = hit.node.metadata[PRODUCED_BY_SESSION],
                            projectId = lineage.projectOf(other),
                            lineagePath = null,
                            readOnlyFromWorkflow = other,
                        ),
                    )
                }
        }

    /** The objective's entities, actions and code symbols, with each word's memory count. */
    private suspend fun queryHintsFor(request: AgentTaskRequest): MemoryQueryHints {
        val objective = request.objective
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
        val tool = layerFor(memoryWorkflowOf(request)).tool
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
        val tool = layerFor(memoryWorkflowOf(request)).tool
        val counts = linkedMapOf<String, Int>()
        ranked.filter { it.provenance?.readOnlyFromWorkflow == null }.take(FEEDBACK_RESULTS).forEach { hit ->
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
        // The lineage first, whatever the gate's order; read-only hits after, labelled.
        val hits = agentAttention.select(ranked).sortedBy { it.provenance?.readOnlyFromWorkflow != null }
        val session = cueSession(request.taskRunId.value, request)
        // Recalls the agent asked for that its provider could not take mid-session arrive now.
        val (pending, firstPrompt) = cueMutex.withLock {
            val first = !session.protocolGiven
            session.protocolGiven = true
            session.pending.toList().also { session.pending.clear() } to first
        }
        recordCoRecall(layerFor(memoryWorkflowOf(request)), hits)
        recordAccess(layerFor(memoryWorkflowOf(request)), hits)
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
                        relevant.map { "[${it.score.twoDecimals()}] ${it.sourceLabel()}${it.node.kind.name}: ${it.node.text}${it.divergenceLines()}" }
                            .joinWholeUnits(MAX_RECALL_CHARS),
                    ),
                )
            }
            if (timeline.isNotEmpty()) {
                add(
                    PromptContextBlock(
                        "Memory timeline",
                        timeline.sortedWith(compareBy<MemoryRecallHit> { it.node.createdAtEpochMillis }.thenBy { it.node.id.value })
                            .map { "${it.sourceLabel()}${it.node.kind.name}: ${it.node.text}${it.divergenceLines()}" }
                            .joinWholeUnits(MAX_RECALL_CHARS),
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
        val workflow = workflowOfAgent(agentId)
        val layer = layerFor(workflow)
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
                val hits = recallSummaries(workflow, trigger, session.request)
                val shown = if (trigger.deliberate) hits else gate.select(hits)
                if (shown.isNotEmpty()) {
                    recordCoRecall(layer, shown)
                    recordAccess(layer, shown)
                    deliver(
                        buildString {
                            append(MEMORY_MESSAGE_MARKER).append(' ').append(MemoryCueWatcher.hashtag(trigger.subject)).append('\n')
                            shown.forEach { append("- ").append(it.sourceLabel()).append(it.node.text.take(MAX_SUMMARY_CHARS)).append(it.divergenceLines()).append('\n') }
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
        val tool = layerFor(request?.let { memoryWorkflowOf(it) } ?: workflowOfAgent(agentId)).tool
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

    private suspend fun recallSummaries(workflow: String, trigger: MemoryRecallTrigger, request: AgentTaskRequest?): List<MemoryRecallHit> {
        suspend fun search(tool: MemoryTool, taskRunId: String?): List<MemoryRecallHit> = when (trigger) {
            is MemoryRecallTrigger.Phrase -> tool.grip(
                MemoryQuery(
                    text = trigger.subject,
                    resolution = MemoryResolution.Summary,
                    maxResults = MAX_TRIGGER_SUMMARIES,
                    taskRunId = taskRunId,
                ),
            ).hits
            else -> tool.grip(
                MemoryTagQuery(
                    tags = listOf(trigger.subject),
                    resolution = MemoryResolution.Summary,
                    maxResults = MAX_TRIGGER_SUMMARIES,
                    scope = MemoryBankScope(taskRunId = taskRunId),
                ),
            ).hits
        }
        // The lineage first; then, read-only and labelled, the project's other workflows.
        val own = withProvenance(workflow, search(layerFor(workflow).tool, request?.taskRunId?.value))
        return own + readOnlyHits(workflow) { search(it, null) }.take(MAX_TRIGGER_SUMMARIES)
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
        mutableKnownBanks.value = banks.known()
        mutableRawUsage.value = banks.known().map { banks.store(it).read() }.rawUsage()
        mutableSnapshot.value = selectedWorkflow()?.let { lineageStore(it).read() } ?: MemorySnapshot()
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

private fun QueryResolution.toMemoryResolution(): MemoryResolution = when (this) {
    QueryResolution.Category -> MemoryResolution.Category
    QueryResolution.Summary -> MemoryResolution.Summary
    QueryResolution.Phrase -> MemoryResolution.Phrase
    QueryResolution.Entity, QueryResolution.Action -> MemoryResolution.Tag
    QueryResolution.GranularEvidence -> MemoryResolution.Context
}

/**
 * Where a hit came from, as a prefix: read-only from another workflow of the project, or from an
 * ancestor through the lineage path (merge points included). Empty for the reader's own memory.
 */
internal fun MemoryRecallHit.sourceLabel(): String = timeLabel() + lineageLabel()

/** A consolidated memory's time range (exact times only through the history). */
private fun MemoryRecallHit.timeLabel(): String = MemoryTimeRange.label(node)?.let { "[$it] " }.orEmpty()

private fun MemoryRecallHit.lineageLabel(): String {
    val provenance = provenance ?: return ""
    provenance.readOnlyFromWorkflow?.let { return "[read-only from workflow $it] " }
    val path = provenance.lineagePath ?: return ""
    return if (path.size > 1) "[from workflow ${path.first()} via ${path.joinToString(" → ")}] " else ""
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
    val survivingIds = nodes.mapTo(hashSetOf()) { it.id }
    val survivingEdges = edges.filter { it.from in survivingIds && it.to in survivingIds }
    // A register entry no remaining memory attests is forgotten with it: a filler is not kept once
    // every memory that stated it is gone. A frame with no variants left goes too.
    val attested = survivingEdges.filter { it.relation == MemoryRelationKind.Attests }.mapTo(hashSetOf()) { it.to }
    val withVariants = survivingEdges
        .filter { it.relation == MemoryRelationKind.VariantOf && it.from in attested }
        .mapTo(hashSetOf()) { it.to }
    val survivingEdgeIds = survivingEdges.mapTo(hashSetOf()) { it.id.value }
    val pruned = nodes.filter { node ->
        when (node.kind) {
            // A pair summary goes with its link.
            MemoryNodeKind.PairSummary -> node.metadata[PAIR_OF] in survivingEdgeIds
            MemoryNodeKind.Variant -> node.id in attested
            MemoryNodeKind.Frame -> node.id in withVariants
            else -> true
        }
    }
    val nodeIds = pruned.mapTo(hashSetOf()) { it.id }
    return copy(
        revision = revision + 1,
        episodes = episodes.filter { it.id != episodeId },
        sections = sections,
        nodes = pruned,
        edges = survivingEdges.filter { it.from in nodeIds && it.to in nodeIds },
        queue = queue.filter { it.episodeId != episodeId },
    )
}

/**
 * A hit's divergent partners and deliberations, as indented lines under it. Partners are stated side
 * by side; nothing says which is right.
 */
internal fun MemoryRecallHit.divergenceLines(): String = buildString {
    conflicts.forEach { partner ->
        append("\n  ↔ diverges (same subject, different content; both remembered): ").append(partner.text)
    }
    deliberations.forEach { append("\n  ✎ earlier deliberation: ").append(it.text) }
    // The summary of each link delivered with the hit, and the hit's summary-tree levels.
    pairSummaries.forEach { pair ->
        append("\n  ⇄ ").append(pair.relation.name).append(" link, together: ").append(pair.summary.text.replace('\n', ' '))
    }
    if (outline.isNotEmpty()) {
        append("\n  ⌂ outline: ").append(outline.joinToString(" › ") { it.text.replace('\n', ' ').take(OUTLINE_LEVEL_CHARS) })
    }
    // A resolved contrast is one memory; its history fades with each recall (always reachable via history()).
    resolution?.let { history ->
        when {
            history.accessCount < RESOLVED_NOTE_RECALLS -> {
                append("\n  ✓ resolved, previously contested; not chosen: ")
                append(history.notChosen.joinToString(" | ") { it.text.take(160) })
                append(" (history: memory-history:").append(node.id.value).append(')')
            }
            history.accessCount < RESOLVED_LINK_RECALLS -> append("\n  (history: memory-history:").append(node.id.value).append(')')
            else -> Unit
        }
    }
}

/** Characters of each summary-tree level shown under a hit (the full node is reachable by id). */
internal const val OUTLINE_LEVEL_CHARS = 160

/** Recalls of a resolved memory that still show the brief "previously contested" note. */
internal const val RESOLVED_NOTE_RECALLS = 2

/** Recalls of a resolved memory after which not even the history link is rendered. */
internal const val RESOLVED_LINK_RECALLS = 5

/**
 * Joins recall units whole, in order, while they fit [maxChars]: a unit (a hit with its divergent
 * partners) is never cut in two. The first unit is always kept whole.
 */
internal fun List<String>.joinWholeUnits(maxChars: Int, separator: String = "\n\n"): String = buildString {
    this@joinWholeUnits.forEach { unit ->
        val extra = if (isEmpty()) unit.length else separator.length + unit.length
        if (isNotEmpty() && length + extra > maxChars) return@forEach
        if (isNotEmpty()) append(separator)
        append(unit)
    }
}

private fun Float.twoDecimals(): String {
    val hundredths = (this * 100).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
