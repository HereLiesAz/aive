package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.Deferred

/**
 * A store that is still opening (the browser database starts in a worker). Every call waits for it,
 * so the memory layer can be built synchronously before the app starts.
 */
class DeferredMemoryStore(private val store: Deferred<MemoryStore>) : MemoryStore {
    override suspend fun read(): MemorySnapshot = store.await().read()

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        store.await().commit(expectedRevision, mutation)

    override suspend fun replace(snapshot: MemorySnapshot) = store.await().replace(snapshot)
}

/** Project memory banks that are still opening; every call waits for them. */
fun deferredMemoryBanks(banks: Deferred<MemoryBanks>): MemoryBanks = MemoryBanks(
    openBank = { projectId -> banks.await().store(projectId) },
    registry = object : MemoryBankRegistry {
        override suspend fun known(): Set<String> = banks.await().known().toSet()
        override suspend fun add(projectId: String) { banks.await().store(projectId) }
        override suspend fun migrationReport(): MemoryBankMigrationReport? = banks.await().migrationState.migrationReport()
        override suspend fun recordMigration(report: MemoryBankMigrationReport) = banks.await().migrationState.recordMigration(report)
    },
)
