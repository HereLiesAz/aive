package com.hereliesaz.geministrator.memory

/**
 * Public orchestration boundary for long-term agent memory.
 *
 * The live workflow only needs [sessionObserver] and [tool]. Consolidation is deliberately a
 * separate operation and is available only when a Memory Manager has been configured.
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
        .sortedBy(MemoryQueueEntry::sequence)

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
                tool = GraphMemoryTool(store),
                queue = queue,
                sessionObserver = QueuedMemorySessionObserver(queue),
                consolidator = manager?.let { MemoryConsolidator(store, it, policy) },
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
    }
}
