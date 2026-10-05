package com.hereliesaz.geministrator.memory

/**
 * Public orchestration boundary for long-term agent memory.
 *
 * The live workflow uses [sessionObserver] for lifecycle banking and [tool] for deliberate banking,
 * tag-first recall, and explicit drill-down. Consolidation remains a separate operation and is
 * available only when a Memory Manager has been configured.
 *
 * Deterministic temporal organization and bookkeeping associations are kept outside the Memory
 * Clerks. Reproducible lexical/structural heuristics are also kept outside the model clerks, but
 * remain a distinct evidence class from facts derived from immutable provenance and scope.
 */
class AgentMemoryLayer private constructor(
    val store: MemoryStore,
    val tool: MemoryTool,
    val queue: MemoryConsolidationQueue,
    val sessionObserver: MemorySessionObserver,
    private val consolidator: MemoryConsolidator?,
    private val programmaticAssociator: MemoryProgrammaticAssociator,
    private val lexicalAssociator: MemoryLexicalAssociator,
    private val summarizer: MemorySummarizerChain,
) {
    /** Summarizer cost: model and embedding calls, time, per-engine counts, pair-summary violations. */
    val summaryMetrics: MemorySummaryMetrics get() = summarizer.metrics

    suspend fun consolidateOne(nowEpochMillis: Long): MemoryConsolidationResult {
        absorbDeliberations(nowEpochMillis)
        val result = consolidator?.processNext(nowEpochMillis) ?: MemoryConsolidationResult.Idle
        if (result is MemoryConsolidationResult.Completed) {
            while (programmaticAssociator.refresh(nowEpochMillis) > 0) { /* drain */ }
            while (lexicalAssociator.refresh(nowEpochMillis) > 0) { /* drain */ }
        }
        // Pair summaries for links made so far, bounded per pass; the remainder carries over.
        if (consolidator == null) return result
        val pairs = MemoryPairSummaries.step(store, nowEpochMillis, summarizer)
        return if (result == MemoryConsolidationResult.Idle && pairs > 0) MemoryConsolidationResult.PairSummaries(pairs) else result
    }

    /**
     * Absorbs every deliberation that chose a memory and has not been absorbed yet, oldest first
     * ([MemoryDeliberationAbsorber]). Returns how many were absorbed. Deterministic; no model call.
     */
    suspend fun absorbDeliberations(nowEpochMillis: Long): Int {
        var absorbed = 0
        while (true) {
            val snapshot = store.read()
            val mutation = MemoryDeliberationAbsorber.mutationFor(snapshot, nowEpochMillis)
            if (mutation.nodesToAdd.isEmpty()) return absorbed
            if (store.commit(snapshot.revision, mutation)) absorbed += 1
        }
    }

    /** Refresh exact/bookkeeping associations without invoking a model. */
    suspend fun refreshProgrammaticAssociations(nowEpochMillis: Long): Int {
        var total = 0
        var batch: Int
        do {
            batch = programmaticAssociator.refresh(nowEpochMillis)
            total += batch
        } while (batch > 0)
        return total
    }

    /** Refresh deterministic lexical/structural heuristic associations without invoking a model. */
    suspend fun refreshLexicalAssociations(nowEpochMillis: Long): Int =
        lexicalAssociator.refresh(nowEpochMillis)

    /** Refresh both non-model association layers while preserving their distinct evidence metadata. */
    suspend fun refreshNonModelAssociations(nowEpochMillis: Long): Int =
        programmaticAssociator.refresh(nowEpochMillis) + lexicalAssociator.refresh(nowEpochMillis)

    /**
     * Returns the active derived temporal index. Building it is deterministic and non-destructive;
     * no model call or persistence mutation is required.
     */
    suspend fun temporalIndex(): MemoryTemporalIndex =
        MemoryTemporalIndex.build(store.read().episodes)

    suspend fun pendingEpisodes(): List<MemoryQueueEntry> = store.read().queue
        .filter { it.status != MemoryQueueStatus.Complete }
        .sortedWith(
            compareByDescending<MemoryQueueEntry> { it.priority.ordinal }
                .thenBy { it.sequence },
        )

    companion object {
        fun create(
            store: MemoryStore,
            manager: MemoryManagerAgent? = null,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
            maxChunkChars: Int = 6_000,
            lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
            summarizer: MemorySummarizerChain = MemorySummarizerChain(),
        ): AgentMemoryLayer {
            val queue = MemoryConsolidationQueue(
                store,
                minOf(maxChunkChars, policy.maxPacketChars),
            )
            val graphTool = GraphMemoryTool(store, queue, summarizer)
            return AgentMemoryLayer(
                store = store,
                tool = LexicalMemoryTool(graphTool, lexicon),
                queue = queue,
                sessionObserver = QueuedMemorySessionObserver(queue),
                consolidator = manager?.let { MemoryConsolidator(store, it, policy, summarizer) },
                programmaticAssociator = MemoryProgrammaticAssociator(store),
                lexicalAssociator = MemoryLexicalAssociator(store, lexicon),
                summarizer = summarizer,
            )
        }

        /**
         * Preferred production path for local memory maintenance. NounTagger and VerbTagger are
         * automatically wrapped with conservative deterministic technical fast paths; their trained
         * specialists remain the fallback for ambiguous or natural-language packets. Other clerks
         * remain unchanged.
         */
        fun createWithMicroAgents(
            store: MemoryStore,
            agents: Collection<MemoryMicroAgent>,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
            maxChunkChars: Int = 6_000,
            lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
            programmaticSemanticFastPaths: Boolean = true,
            summarizer: MemorySummarizerChain = summarizerChainFor(agents),
        ): AgentMemoryLayer {
            val routedAgents = if (programmaticSemanticFastPaths) {
                agents.withProgrammaticSemanticFastPaths()
            } else {
                agents.toList()
            }
            val router = MemoryMicroAgentRouter(routedAgents)
            val constrainedPolicy = router.constrainPolicy(policy)
            return create(
                store = store,
                manager = router,
                policy = constrainedPolicy,
                maxChunkChars = minOf(maxChunkChars, constrainedPolicy.maxPacketChars),
                lexicon = lexicon,
                summarizer = summarizer,
            )
        }

        /**
         * The summarizer chain the [agents] support: the SummarySynthesizer clerk when it is a local
         * model (never a programmatic or hosted one), and the AssociationLinker's embedder.
         */
        fun summarizerChainFor(agents: Collection<MemoryMicroAgent>): MemorySummarizerChain = MemorySummarizerChain(
            model = agents.firstOrNull {
                it.role == MemoryMicroAgentRole.SummarySynthesizer && it !is ProgrammaticMemoryMicroAgent && it.model.localOnly
            }?.let(::ClerkAbstractiveSummarizer),
            embedder = agents.filterIsInstance<EmbeddingAssociationLinkerMicroAgent>().firstOrNull()?.let { linker ->
                MemoryTextEmbedder { texts -> linker.embedTexts(texts) }
            },
        )

        fun createDefault(
            manager: MemoryManagerAgent? = null,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
            lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
        ): AgentMemoryLayer = create(
            store = SettingsMemoryStore.createDefault(),
            manager = manager,
            policy = policy,
            lexicon = lexicon,
        )

        fun createDefaultWithMicroAgents(
            agents: Collection<MemoryMicroAgent>,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
            lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
            programmaticSemanticFastPaths: Boolean = true,
        ): AgentMemoryLayer = createWithMicroAgents(
            store = SettingsMemoryStore.createDefault(),
            agents = agents,
            policy = policy,
            lexicon = lexicon,
            programmaticSemanticFastPaths = programmaticSemanticFastPaths,
        )
    }
}
