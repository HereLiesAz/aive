package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeferredMemoryStoreTest {
    @Test
    fun callsWaitForTheStoreToOpenThenDelegate() = runTest {
        val opening = CompletableDeferred<MemoryStore>()
        val store = DeferredMemoryStore(opening)
        val read = async { store.read() }
        yield()
        assertFalse(read.isCompleted)

        val backing = InMemoryMemoryStore(MemorySnapshot(revision = 7))
        opening.complete(backing)

        assertEquals(7, read.await().revision)
        store.replace(MemorySnapshot(revision = 3))
        assertEquals(3, backing.read().revision)
    }
}
