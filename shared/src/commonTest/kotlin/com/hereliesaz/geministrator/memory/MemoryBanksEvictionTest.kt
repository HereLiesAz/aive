package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryBanksEvictionTest {
    private class CountingStore(private val inner: MemoryStore = InMemoryMemoryStore(allowExternalReferences = true)) : ClosableMemoryStore {
        var closed = false
        override fun close() { closed = true }
        override suspend fun read(): MemorySnapshot = inner.read()
        override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean = inner.commit(expectedRevision, mutation)
        override suspend fun replace(snapshot: MemorySnapshot) = inner.replace(snapshot)
        override suspend fun replaceIfRevision(expectedRevision: Long, snapshot: MemorySnapshot): Boolean =
            inner.replaceIfRevision(expectedRevision, snapshot)
    }

    @Test
    fun idleBanksPastTheLimitAreClosedAndReopenedOnUse() = runBlocking {
        val opened = mutableListOf<CountingStore>()
        val banks = MemoryBanks({ CountingStore().also(opened::add) }, maxOpen = 2)
        val handles = listOf("a", "b", "c").map { banks.store(it) }
        handles.forEach { it.read() }
        assertEquals(3, opened.size)
        assertEquals(1, opened.count { it.closed })
        assertTrue(opened[0].closed, "the least recently used bank is the one closed")
        handles[0].read()
        assertEquals(4, opened.size, "a closed bank is reopened by its next use")
    }

    @Test
    fun aBankWithNoConnectionIsNeverDropped() = runBlocking {
        val banks = MemoryBanks({ InMemoryMemoryStore(allowExternalReferences = true) }, maxOpen = 1)
        val a = banks.store("a")
        banks.store("b").read()
        banks.store("c").read()
        assertEquals(0L, a.read().revision)
    }
}
