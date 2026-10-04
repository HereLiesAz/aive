package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Memories surfaced to an agent together, again and again, get linked (Hebbian: what fires
 * together wires together).
 *
 * Every [linkEvery] times the same two memories are delivered together (the prompt's recall, a
 * recall an agent asked for), one `AssociatedWith` edge of weight [WEIGHT] is added between them,
 * up to [maxLinks] per pair. Edges are independent evidence, so they accumulate (four reach 0.76).
 * Nothing stored is changed or removed: this only adds links, and it never compares what the two
 * memories say. Counts live in this process; after a restart they start again, and a link that
 * already exists is not added twice.
 */
internal class MemoryCoRecall(
    private val nowEpochMillis: () -> Long,
    private val linkEvery: Int = LINK_EVERY,
    private val maxLinks: Int = MAX_LINKS,
) {
    private val mutex = Mutex()
    private val counts = HashMap<Pair<String, String>, Int>()

    /** Records that [nodeIds] were delivered together; returns the links added to [store]. */
    suspend fun recalledTogether(store: MemoryStore, nodeIds: Collection<MemoryNodeId>): List<MemoryEdge> {
        if (linkEvery <= 0) return emptyList()
        val ids = nodeIds.map { it.value }.distinct().sorted()
        if (ids.size < 2) return emptyList()
        val due = mutex.withLock {
            buildList {
                for (i in ids.indices) for (j in i + 1 until ids.size) {
                    val pair = ids[i] to ids[j]
                    val count = (counts[pair] ?: 0) + 1
                    counts[pair] = count
                    val link = count / linkEvery
                    if (count % linkEvery == 0 && link <= maxLinks) add(Triple(pair, link, count))
                }
            }
        }
        if (due.isEmpty()) return emptyList()
        val createdAt = nowEpochMillis()
        val edges = due.map { (pair, link, count) ->
            MemoryEdge(
                id = MemoryEdgeId("co-recall:${pair.first}|${pair.second}:$link"),
                from = MemoryNodeId(pair.first),
                to = MemoryNodeId(pair.second),
                relation = MemoryRelationKind.AssociatedWith,
                weight = WEIGHT,
                createdAtEpochMillis = createdAt,
                metadata = mapOf("deterministic" to "true", "basis" to "co-recall", "coRecalls" to count.toString()),
            )
        }
        repeat(COMMIT_ATTEMPTS) {
            val snapshot = store.read()
            val wanted = edges.mapTo(hashSetOf()) { it.id }
            val existing = snapshot.edges.asSequence().map { it.id }.filter(wanted::contains).toSet()
            val nodes = snapshot.nodes.asSequence().map { it.id }.filter { id -> edges.any { it.from == id || it.to == id } }.toSet()
            val fresh = edges.filter { it.id !in existing && it.from in nodes && it.to in nodes }
            if (fresh.isEmpty()) return emptyList()
            if (store.commit(snapshot.revision, MemoryStoreMutation(edgesToAdd = fresh))) return fresh
        }
        return emptyList()
    }

    companion object {
        const val LINK_EVERY = 3
        const val MAX_LINKS = 4
        const val WEIGHT = 0.3f
        private const val COMMIT_ATTEMPTS = 3
    }
}
