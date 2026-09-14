package com.hereliesaz.geministrator.memory

/**
 * Public orchestration boundary for long-term agent memory.
 *
 * The live workflow uses [sessionObserver] for lifecycle banking and [tool] for deliberate banking,
 * tag-first recall, and explicit drill-down. Consolidation remains a separate operation and is
 * available only when a Memory Manager has been configured.
 */
class AgentMemoryLayer private constructor(
    val store: MemoryStore,
    val tool: MemoryTool,
    val queue: MemoryConsolidationQueue,
    val sessionObserver: MemorySessionObserver,
    private val consolidator: MemoryConsolidator?,
) {
    suspend fun consolidateOne(nowEpochMillis: Long): MemoryConsolidationResult =
        consolidator?.processNext(nowEpochMillis) ?: MemoryConsolidationResult.Idle

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
        ): AgentMemoryLayer {
            val queue = MemoryConsolidationQueue(store, maxChunkChars)
            return AgentMemoryLayer(
                store = store,
                tool = GraphMemoryTool(store, queue),
                queue = queue,
                sessionObserver = QueuedMemorySessionObserver(queue),
                consolidator = manager?.let { MemoryConsolidator(store, it, policy) },
            )
        }

        /**
         * Preferred production path for local memory maintenance. Each specialist may use its own
         * small model or share a base model with role-specific adapters. The consolidation packet
         * budget is clamped to the smallest configured specialist before any work is scheduled, so
         * a model context overflow cannot be created by the memory engine itself.
         */
        fun createWithMicroAgents(
            store: MemoryStore,
            agents: Collection<MemoryMicroAgent>,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
            maxChunkChars: Int = 6_000,
        ): AgentMemoryLayer {
            val router = MemoryMicroAgentRouter(agents)
            return create(
                store = store,
                manager = router,
                policy = router.constrainPolicy(policy),
                maxChunkChars = maxChunkChars,
            )
        }

        fun createDefault(
            manager: MemoryManagerAgent? = null,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
        ): AgentMemoryLayer = create(
            store = SettingsMemoryStore.createDefault(),
            manager = manager,
            policy = policy,
        )

        fun createDefaultWithMicroAgents(
            agents: Collection<MemoryMicroAgent>,
            policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
        ): AgentMemoryLayer = createWithMicroAgents(
            store = SettingsMemoryStore.createDefault(),
            agents = agents,
            policy = policy,
        )
    }
}
