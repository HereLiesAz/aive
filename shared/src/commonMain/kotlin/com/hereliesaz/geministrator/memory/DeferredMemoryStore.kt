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
