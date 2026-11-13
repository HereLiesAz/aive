package com.hereliesaz.geministrator.relay

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame

class RelayHubSweepTest {
    @Test
    fun aFreshlyHandedOutEmptyPoolSurvivesTheSweep() = runBlocking {
        var now = 1_000L
        val hub = ComputeRelayHub(nowEpochMillis = { now })
        val pool = hub.pool("p")

        hub.sweepExpired()
        assertSame(pool, hub.pool("p"), "the connecting node must register on the same pool")

        now += 120_000L
        hub.sweepExpired()
        // Idle and empty for longer than the grace period: a new pool is built on demand.
        val replacement = hub.pool("p")
        assert(replacement !== pool)
    }

    @Test
    fun lookingUpAPoolNeverCreatesOneAndCreationIsBounded() {
        val hub = ComputeRelayHub()
        kotlin.test.assertNull(hub.existingPool("p"))
        repeat(256) { hub.pool("p$it") }
        kotlin.test.assertFailsWith<IllegalStateException> { hub.pool("one-too-many") }
        assertSame(hub.pool("p1"), hub.existingPool("p1"))
    }
}
