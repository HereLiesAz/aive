package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
}

class InMemoryMemoryStore(
    initial: MemorySnapshot = MemorySnapshot(),
) : MemoryStore {
    private val mutex = Mutex()
    private var snapshot = initial

    override suspend fun read(): MemorySnapshot = mutex.withLock { snapshot }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        mutex.withLock {
            if (snapshot.revision != expectedRevision) return@withLock false
            snapshot = snapshot.applyMutation(mutation)
            true
        }
}

class MemoryStoreCorruptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SettingsMemoryStore(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = defaultJson,
) : MemoryStore {
    override suspend fun read(): MemorySnapshot = settingsMemoryMutex.withLock { readUnlocked() }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        settingsMemoryMutex.withLock {
            val current = readUnlocked()
            if (current.revision != expectedRevision) return@withLock false
            val next = current.applyMutation(mutation)
            settings.putString(storageKey, json.encodeToString(MemorySnapshot.serializer(), next))
            true
        }

    suspend fun clear() {
        settingsMemoryMutex.withLock { settings.remove(storageKey) }
    }

    private fun readUnlocked(): MemorySnapshot {
        val encoded = settings.getStringOrNull(storageKey) ?: return MemorySnapshot()
        return try {
            json.decodeFromString(MemorySnapshot.serializer(), encoded)
        } catch (failure: Exception) {
            throw MemoryStoreCorruptionException(
                "Memory persistence data is unreadable and must be repaired or cleared.",
                failure,
            )
        }
    }

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "haive.memory.persistence.v1"

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
            classDiscriminator = "type"
        }

        fun createDefault(): SettingsMemoryStore = SettingsMemoryStore(Settings())
    }
}

private val settingsMemoryMutex = Mutex()

private fun MemorySnapshot.applyMutation(mutation: MemoryStoreMutation): MemorySnapshot {
    val episodeIds = episodes.mapTo(mutableSetOf()) { it.id }
    mutation.episodesToAdd.forEach { episode ->
        require(episodeIds.add(episode.id)) { "Memory episode ${episode.id.value} already exists" }
    }

    val sectionIds = sections.mapTo(mutableSetOf()) { it.id }
    mutation.sectionsToAdd.forEach { section ->
        require(sectionIds.add(section.id)) { "Memory section ${section.id.value} already exists" }
        require(section.episodeId in episodeIds) {
            "Memory section ${section.id.value} references missing episode ${section.episodeId.value}"
        }
    }

    val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
    mutation.nodesToAdd.forEach { node ->
        require(nodeIds.add(node.id)) { "Memory node ${node.id.value} already exists" }
        require(node.sourceEpisodeIds.all { it in episodeIds }) {
            "Memory node ${node.id.value} references a missing episode"
        }
        require(node.sourceSectionIds.all { it in sectionIds }) {
            "Memory node ${node.id.value} references a missing section"
        }
    }

    val edgeIds = edges.mapTo(mutableSetOf()) { it.id }
    mutation.edgesToAdd.forEach { edge ->
        require(edgeIds.add(edge.id)) { "Memory edge ${edge.id.value} already exists" }
        require(edge.from in nodeIds && edge.to in nodeIds) {
            "Memory edge ${edge.id.value} references a missing node"
        }
    }

    val nextQueue = queue.toMutableList()
    mutation.queueUpserts.forEach { entry ->
        require(entry.episodeId in episodeIds) {
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

    return copy(
        revision = revision + 1,
        episodes = episodes + mutation.episodesToAdd,
        sections = sections + mutation.sectionsToAdd,
        nodes = nodes + mutation.nodesToAdd,
        edges = edges + mutation.edgesToAdd,
        queue = nextQueue.sortedBy(MemoryQueueEntry::sequence),
    )
}
