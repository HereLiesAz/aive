package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Memory is deliberately persisted outside WorkflowPersistence. The graph is append-only; the only
 * mutable records are queue cursors/status. Graph revision compare-and-set prevents concurrent
 * consolidators from overwriting one another.
 */
interface MemoryStore {
    suspend fun read(): MemorySnapshot

    /** Returns false when [expectedRevision] is stale. */
    suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean

    /**
     * Replaces the whole graph: import, forgetting an episode, clearing. The only operation that can
     * remove records; ordinary consolidation is append-only.
     */
    suspend fun replace(snapshot: MemorySnapshot)
}

class InMemoryMemoryStore(
    initial: MemorySnapshot = MemorySnapshot(),
    /** A workflow's own records in a lineage bank: may reference its ancestors' records (see [LineageMemoryStore]). */
    private val allowExternalReferences: Boolean = false,
) : MemoryStore {
    private val mutex = Mutex()
    private var snapshot = initial
    private var ids = MemoryIdIndex(initial, allowExternalReferences)

    override suspend fun read(): MemorySnapshot = mutex.withLock { snapshot }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        mutex.withLock {
            if (snapshot.revision != expectedRevision) return@withLock false
            snapshot = snapshot.applyMutation(mutation, ids)
            true
        }

    override suspend fun replace(snapshot: MemorySnapshot) {
        mutex.withLock {
            this.snapshot = snapshot
            ids = MemoryIdIndex(snapshot, allowExternalReferences)
        }
    }
}

class MemoryStoreCorruptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Persists the graph in [Settings] as a compacted snapshot plus an append-only mutation log.
 *
 * The decoded snapshot is kept in memory, so reads cost nothing and a commit encodes only its own
 * mutation. Every [compactEvery] commits the log is folded into the snapshot. Each log entry records
 * the revision it produces; on load, entries already contained in the snapshot are skipped, so a
 * crash between writing a compacted snapshot and clearing the log cannot apply a mutation twice.
 *
 * One instance per [storageKey] per process: the cache assumes nothing else writes those keys.
 */
class SettingsMemoryStore(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = defaultJson,
    private val compactEvery: Int = DEFAULT_COMPACT_EVERY,
    /** A workflow's own records in a lineage bank: may reference its ancestors' records (see [LineageMemoryStore]). */
    private val allowExternalReferences: Boolean = false,
) : MemoryStore {
    private val mutex = Mutex()
    private var cached: MemorySnapshot? = null
    private var ids: MemoryIdIndex? = null
    private var logSize: Int = 0

    init {
        require(compactEvery > 0)
    }

    override suspend fun read(): MemorySnapshot = mutex.withLock { loadUnlocked() }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        mutex.withLock {
            val current = loadUnlocked()
            if (current.revision != expectedRevision) return@withLock false
            val next = current.applyMutation(mutation, requireNotNull(ids))
            if (logSize + 1 >= compactEvery) {
                writeCompacted(next)
            } else {
                settings.putString(
                    logKey(logSize),
                    json.encodeToString(MemoryLogEntry.serializer(), MemoryLogEntry(next.revision, mutation)),
                )
                logSize += 1
                settings.putInt(logCountKey, logSize)
            }
            cached = next
            true
        }

    override suspend fun replace(snapshot: MemorySnapshot) {
        mutex.withLock {
            loadUnlocked()
            writeCompacted(snapshot)
            cached = snapshot
            ids = MemoryIdIndex(snapshot, allowExternalReferences)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            clearLog()
            settings.remove(storageKey)
            cached = MemorySnapshot()
            ids = MemoryIdIndex(MemorySnapshot(), allowExternalReferences)
        }
    }

    private fun loadUnlocked(): MemorySnapshot {
        cached?.let { return it }
        val base = try {
            settings.getStringOrNull(storageKey)?.let { json.decodeFromString(MemorySnapshot.serializer(), it) }
                ?: MemorySnapshot()
        } catch (failure: Exception) {
            throw MemoryStoreCorruptionException(
                "Memory persistence data is unreadable and must be repaired or cleared.",
                failure,
            )
        }
        logSize = settings.getInt(logCountKey, 0)
        var snapshot = base
        val replayIds = MemoryIdIndex(base, allowExternalReferences)
        for (index in 0 until logSize) {
            val encoded = settings.getStringOrNull(logKey(index)) ?: throw MemoryStoreCorruptionException(
                "Memory log entry $index of $logSize is missing.",
            )
            val entry = try {
                json.decodeFromString(MemoryLogEntry.serializer(), encoded)
            } catch (failure: Exception) {
                throw MemoryStoreCorruptionException("Memory log entry $index is unreadable.", failure)
            }
            if (entry.revision <= snapshot.revision) continue
            snapshot = snapshot.applyMutation(entry.mutation, replayIds)
            if (snapshot.revision != entry.revision) {
                throw MemoryStoreCorruptionException(
                    "Memory log entry $index produces revision ${snapshot.revision}, expected ${entry.revision}.",
                )
            }
        }
        cached = snapshot
        ids = replayIds
        return snapshot
    }

    private fun writeCompacted(snapshot: MemorySnapshot) {
        settings.putString(storageKey, json.encodeToString(MemorySnapshot.serializer(), snapshot))
        clearLog()
    }

    private fun clearLog() {
        val count = settings.getInt(logCountKey, 0)
        settings.remove(logCountKey)
        for (index in 0 until maxOf(count, logSize)) settings.remove(logKey(index))
        logSize = 0
    }

    private val logCountKey get() = "$storageKey.log.count"

    private fun logKey(index: Int) = "$storageKey.log.$index"

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "haive.memory.persistence.v1"
        const val DEFAULT_COMPACT_EVERY: Int = 256

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
            classDiscriminator = "type"
        }

        fun createDefault(): SettingsMemoryStore = SettingsMemoryStore(Settings())
    }
}

@Serializable
private data class MemoryLogEntry(
    val revision: Long,
    val mutation: MemoryStoreMutation,
)

/**
 * IDs already in the graph, kept alongside a store's snapshot so a commit validates in proportion to
 * its own size rather than rebuilding every set. Only [applyMutation] updates it, and only after the
 * whole mutation has validated.
 */
internal class MemoryIdIndex(snapshot: MemorySnapshot, val allowExternalReferences: Boolean = false) {
    val episodes: MutableSet<MemoryEpisodeId> = snapshot.episodes.mapTo(hashSetOf()) { it.id }
    val sections: MutableSet<MemorySectionId> = snapshot.sections.mapTo(hashSetOf()) { it.id }
    val nodes: MutableSet<MemoryNodeId> = snapshot.nodes.mapTo(hashSetOf()) { it.id }
    val edges: MutableSet<MemoryEdgeId> = snapshot.edges.mapTo(hashSetOf()) { it.id }
}

private fun MemorySnapshot.applyMutation(mutation: MemoryStoreMutation): MemorySnapshot =
    applyMutation(mutation, MemoryIdIndex(this))

internal fun MemorySnapshot.applyMutation(mutation: MemoryStoreMutation, ids: MemoryIdIndex): MemorySnapshot {
    val newEpisodes = hashSetOf<MemoryEpisodeId>()
    mutation.episodesToAdd.forEach { episode ->
        require(episode.id !in ids.episodes && newEpisodes.add(episode.id)) {
            "Memory episode ${episode.id.value} already exists"
        }
    }
    fun episodeKnown(id: MemoryEpisodeId) = id in ids.episodes || id in newEpisodes
    // A lineage bank's own store may point at its ancestors' records; the lineage view checks those.
    val external = ids.allowExternalReferences

    val newSections = hashSetOf<MemorySectionId>()
    mutation.sectionsToAdd.forEach { section ->
        require(section.id !in ids.sections && newSections.add(section.id)) {
            "Memory section ${section.id.value} already exists"
        }
        require(external || episodeKnown(section.episodeId)) {
            "Memory section ${section.id.value} references missing episode ${section.episodeId.value}"
        }
    }

    val newNodes = hashSetOf<MemoryNodeId>()
    mutation.nodesToAdd.forEach { node ->
        require(node.id !in ids.nodes && newNodes.add(node.id)) { "Memory node ${node.id.value} already exists" }
        require(external || node.sourceEpisodeIds.all(::episodeKnown)) {
            "Memory node ${node.id.value} references a missing episode"
        }
        require(external || node.sourceSectionIds.all { it in ids.sections || it in newSections }) {
            "Memory node ${node.id.value} references a missing section"
        }
    }

    // A memory never grows: a rewrite must be smaller than the predecessor it supersedes.
    requireRewritesShrink(mutation)

    val newEdges = hashSetOf<MemoryEdgeId>()
    mutation.edgesToAdd.forEach { edge ->
        require(edge.id !in ids.edges && newEdges.add(edge.id)) { "Memory edge ${edge.id.value} already exists" }
        require(external || (edge.from in ids.nodes || edge.from in newNodes) && (edge.to in ids.nodes || edge.to in newNodes)) {
            "Memory edge ${edge.id.value} references a missing node"
        }
    }

    val nextQueue = queue.toMutableList()
    mutation.queueUpserts.forEach { entry ->
        require(episodeKnown(entry.episodeId)) {
            "Memory queue entry ${entry.id.value} references missing episode ${entry.episodeId.value}"
        }
        val index = nextQueue.indexOfFirst { it.id == entry.id }
        if (index < 0) {
            require(nextQueue.none { it.sequence == entry.sequence }) {
                "Memory queue sequence ${entry.sequence} already exists"
            }
            nextQueue += entry
        } else {
            require(nextQueue[index].sequence == entry.sequence) {
                "Memory queue sequence is immutable"
            }
            nextQueue[index] = entry
        }
    }

    ids.episodes += newEpisodes
    ids.sections += newSections
    ids.nodes += newNodes
    ids.edges += newEdges
    return copy(
        revision = revision + 1,
        declinedCondensations = (declinedCondensations + mutation.condensationDeclinesToAdd).distinct(),
        episodes = episodes + mutation.episodesToAdd,
        sections = sections + mutation.sectionsToAdd,
        nodes = nodes + mutation.nodesToAdd,
        edges = edges + mutation.edgesToAdd,
        queue = nextQueue.sortedBy(MemoryQueueEntry::sequence),
    )
}
