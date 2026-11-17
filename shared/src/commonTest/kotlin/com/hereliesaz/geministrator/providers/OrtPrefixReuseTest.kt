package com.hereliesaz.geministrator.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OrtPrefixReuseTest {
    /** One token per character, so a head and a tail always split cleanly unless [glue] says otherwise. */
    private class FakeBackend(
        var failPrime: Boolean = false,
        var failTail: Boolean = false,
        /** When true, tokenising the whole prompt gives one token fewer than its halves (a merge across the cut). */
        var glue: Boolean = false,
    ) : OrtPrefixBackend {
        val primed = mutableListOf<String>()
        val tails = mutableListOf<String>()
        val whole = mutableListOf<String>()
        var closed = 0

        override fun encode(text: String): LongArray {
            val ids = LongArray(text.length) { text[it].code.toLong() }
            // Only the whole prompt (the marked head plus a tail) is longer than the marked head itself.
            return if (glue && text.startsWith(GLUE_MARK) && text.length > bigHead.length + 1) ids.copyOf(ids.size - 1) else ids
        }

        override fun prime(head: String): OrtPrimedHead {
            if (failPrime) throw IllegalStateException("prime failed")
            primed += head
            return object : OrtPrimedHead {
                override fun generate(tail: String, maxNewTokens: Int): OnDeviceTextGeneration {
                    if (failTail) throw IllegalStateException("tail failed")
                    tails += tail
                    return OnDeviceTextGeneration("tail:$tail")
                }

                override fun close() {
                    closed++
                }
            }
        }

        override fun generateWhole(prompt: String, maxNewTokens: Int): OnDeviceTextGeneration {
            whole += prompt
            return OnDeviceTextGeneration("whole")
        }
    }

    private companion object {
        const val GLUE_MARK = "G"
        val bigHead = "a".repeat(400)
        val otherHead = "b".repeat(400)
    }

    @Test
    fun primesOnceThenHitsOnTheSameHead() = runTest {
        val backend = FakeBackend()
        val reuse = OrtPrefixReuse(backend)
        assertEquals("tail:t1", reuse.generate(bigHead, "t1", 8).text)
        assertEquals("tail:t2", reuse.generate(bigHead, "t2", 8).text)
        assertEquals(listOf(bigHead), backend.primed)
        val stats = reuse.stats()
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertEquals(0, stats.fallbacks)
        assertEquals(2, stats.tasks)
        assertTrue(backend.whole.isEmpty())
    }

    @Test
    fun aDifferentHeadOrAdapterPrimesAnewAndFreesTheOld() = runTest {
        val backend = FakeBackend()
        var adapter: String? = "one"
        val reuse = OrtPrefixReuse(backend, adapterId = { adapter })
        reuse.generate(bigHead, "x", 8)
        reuse.generate(otherHead, "x", 8)
        assertEquals(1, backend.closed)
        adapter = "two"
        reuse.generate(otherHead, "x", 8)
        assertEquals(2, backend.closed)
        assertEquals(3, backend.primed.size)
        assertEquals(3, reuse.stats().misses)
        assertEquals(0, reuse.stats().hits)
    }

    @Test
    fun aHeadUnderTheMinimumUsesThePlainPathAndIsNotCounted() = runTest {
        val backend = FakeBackend()
        val reuse = OrtPrefixReuse(backend)
        assertEquals("whole", reuse.generate("short head ", "tail", 8).text)
        assertEquals(listOf("short head tail"), backend.whole)
        assertTrue(backend.primed.isEmpty())
        assertEquals(0, reuse.stats().tasks)
    }

    @Test
    fun aFailureFallsBackTurnsReuseOffAndFreesTheHead() = runTest {
        val backend = FakeBackend()
        val reuse = OrtPrefixReuse(backend)
        reuse.generate(bigHead, "t1", 8)
        backend.failTail = true
        assertEquals("whole", reuse.generate(bigHead, "t2", 8).text)
        assertEquals(1, backend.closed)
        assertEquals(1, reuse.stats().fallbacks)
        assertTrue(reuse.stats().switchedOff)
        // Off for good: the next call is plain and does not prime again.
        assertEquals("whole", reuse.generate(bigHead, "t3", 8).text)
        assertEquals(1, backend.primed.size)
    }

    @Test
    fun aPrimeFailureFallsBackToo() = runTest {
        val backend = FakeBackend(failPrime = true)
        val reuse = OrtPrefixReuse(backend)
        assertEquals("whole", reuse.generate(bigHead, "t", 8).text)
        assertEquals(1, reuse.stats().fallbacks)
        assertTrue(reuse.stats().switchedOff)
    }

    @Test
    fun aTokenisationMismatchRunsPlainWithoutSwitchingOff() = runTest {
        val backend = FakeBackend(glue = true)
        val reuse = OrtPrefixReuse(backend)
        val head = GLUE_MARK + bigHead
        assertEquals("whole", reuse.generate(head, "t", 8).text)
        assertEquals(listOf(head + "t"), backend.whole)
        assertTrue(backend.primed.isEmpty())
        assertEquals(1, reuse.stats().fallbacks)
        assertTrue(!reuse.stats().switchedOff)
        // Another prompt that splits cleanly still uses the cache.
        assertEquals("tail:t", reuse.generate(bigHead, "t", 8).text)
        assertEquals(1, reuse.stats().misses)
    }

    @Test
    fun closeFreesTheHeldHead() = runTest {
        val backend = FakeBackend()
        val reuse = OrtPrefixReuse(backend)
        reuse.generate(bigHead, "t", 8)
        reuse.close()
        assertEquals(1, backend.closed)
        assertEquals(0, reuse.stats().prefixTokensEstimate)
    }
}
