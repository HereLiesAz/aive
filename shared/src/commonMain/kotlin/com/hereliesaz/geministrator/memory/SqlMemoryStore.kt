package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The memory graph in SQLite (SQLDelight), one row per record.
 *
 * Every commit is one transaction that inserts only the mutation's rows and bumps the revision, so
 * persistence costs the size of the change. The decoded snapshot and ID index stay in memory for the
 * snapshot-based consolidator and GRIP; the database is the source of truth on open.
 *
 * The schema must already exist on [driver] (platform factories create it). One instance per
 * database per process: the cache assumes nothing else writes those tables.
 */
class SqlMemoryStore(
    driver: SqlDriver,
    private val json: Json = SettingsMemoryStore.defaultJson,
) : MemoryStore {
    private val database = MemoryDatabase(driver)
    private val queries = database.memoryQueries
    private val mutex = Mutex()
    private var cached: MemorySnapshot? = null
    private var ids: MemoryIdIndex? = null

    override suspend fun read(): MemorySnapshot = mutex.withLock { loadUnlocked() }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        mutex.withLock {
            val current = loadUnlocked()
            if (current.revision != expectedRevision) return@withLock false
            // Validates first (and updates the ID index); nothing reaches the database unless it passes.
            val next = current.applyMutation(mutation, requireNotNull(ids))
            try {
                database.transaction {
                    writeMutation(mutation)
                    queries.setRevision(next.revision.toString())
                }
            } catch (failure: Throwable) {
                // The index already holds the new IDs; reload both from the database on next use.
                cached = null
                ids = null
                throw failure
            }
            cached = next
            true
        }

    override suspend fun replace(snapshot: MemorySnapshot) {
        mutex.withLock {
            database.transaction {
                deleteAll()
                writeMutation(
                    MemoryStoreMutation(
                        episodesToAdd = snapshot.episodes,
                        sectionsToAdd = snapshot.sections,
                        nodesToAdd = snapshot.nodes,
                        edgesToAdd = snapshot.edges,
                        queueUpserts = snapshot.queue,
                        condensationDeclinesToAdd = snapshot.declinedCondensations,
                    ),
                )
                queries.setRevision(snapshot.revision.toString())
            }
            cached = snapshot
            ids = MemoryIdIndex(snapshot)
        }
    }

    suspend fun clear() = replace(MemorySnapshot())

    /**
     * Moves an older [SettingsMemoryStore] into this database once: only when this database has never
     * been written and the legacy store holds something. The legacy keys are cleared afterwards.
     */
    suspend fun importLegacy(legacy: SettingsMemoryStore): Boolean {
        val empty = mutex.withLock { queries.revision().awaitAsOneOrNull() == null }
        if (!empty) return false
        val snapshot = legacy.read()
        if (snapshot.revision == 0L && snapshot.episodes.isEmpty()) return false
        replace(snapshot)
        legacy.clear()
        return true
    }

    private suspend fun loadUnlocked(): MemorySnapshot {
        cached?.let { return it }
        val snapshot = try {
            MemorySnapshot(
                revision = queries.revision().awaitAsOneOrNull()?.toLong() ?: 0L,
                episodes = queries.allEpisodes().awaitAsList().map { json.decodeFromString(MemoryEpisode.serializer(), it) },
                sections = queries.allSections().awaitAsList().map { json.decodeFromString(MemorySection.serializer(), it) },
                nodes = queries.allNodes().awaitAsList().map { json.decodeFromString(MemoryNode.serializer(), it) },
                edges = queries.allEdges().awaitAsList().map { json.decodeFromString(MemoryEdge.serializer(), it) },
                queue = queries.allQueue().awaitAsList().map { json.decodeFromString(MemoryQueueEntry.serializer(), it) },
                declinedCondensations = queries.allDeclined().awaitAsList(),
            )
        } catch (failure: Exception) {
            throw MemoryStoreCorruptionException("Memory database is unreadable and must be repaired or cleared.", failure)
        }
        cached = snapshot
        ids = MemoryIdIndex(snapshot)
        return snapshot
    }

    private suspend fun writeMutation(mutation: MemoryStoreMutation) {
        mutation.episodesToAdd.forEach {
            queries.insertEpisode(it.id.value, it.projectId, it.createdAtEpochMillis, json.encodeToString(MemoryEpisode.serializer(), it))
        }
        mutation.sectionsToAdd.forEach {
            queries.insertSection(it.id.value, it.episodeId.value, json.encodeToString(MemorySection.serializer(), it))
        }
        mutation.nodesToAdd.forEach {
            queries.insertNode(
                it.id.value, it.kind.name, it.text, it.createdAtEpochMillis,
                json.encodeToString(MemoryNode.serializer(), it),
            )
        }
        mutation.edgesToAdd.forEach {
            queries.insertEdge(
                it.id.value, it.from.value, it.to.value, it.relation.name,
                json.encodeToString(MemoryEdge.serializer(), it),
            )
        }
        mutation.queueUpserts.forEach {
            queries.upsertQueue(
                it.id.value, it.sequence, it.episodeId.value, it.stage.name, it.status.name,
                json.encodeToString(MemoryQueueEntry.serializer(), it),
            )
        }
        mutation.condensationDeclinesToAdd.forEach { queries.insertDeclined(it) }
    }

    private suspend fun deleteAll() {
        queries.deleteMeta()
        queries.deleteEpisodes()
        queries.deleteSections()
        queries.deleteNodes()
        queries.deleteEdges()
        queries.deleteQueue()
        queries.deleteDeclined()
    }

}
