package com.hereliesaz.geministrator.duplex

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuplexAgentBusTest {
    @Test
    fun bothPeersSendAndReceiveWithoutTakingTurns() = runTest {
        val bus = DuplexAgentBus(nowMillis = { 42L })
        val a = async { bus.send(DuplexPeer.A, DuplexKind.Request, "question A") }
        val b = async { bus.send(DuplexPeer.B, DuplexKind.Request, "question B") }
        val sentA = a.await()
        val sentB = b.await()
        assertEquals("question B", bus.receive(DuplexPeer.A).single().body)
        assertEquals("question A", bus.receive(DuplexPeer.B).single().body)
        assertEquals(DuplexPeer.A, sentA.from)
        assertEquals(DuplexPeer.B, sentB.from)
        bus.send(DuplexPeer.A, DuplexKind.Response, "reply A", replyTo = sentB.id)
        bus.send(DuplexPeer.B, DuplexKind.Response, "reply B", replyTo = sentA.id)
        assertEquals("reply B", bus.receive(DuplexPeer.A).last().body)
        assertEquals("reply A", bus.receive(DuplexPeer.B).last().body)
    }

    @Test
    fun correlationAndOwnershipCannotBeSpoofed() = runTest {
        val bus = DuplexAgentBus(nowMillis = { 12L })
        val request = bus.send(DuplexPeer.A, DuplexKind.Request, "request")
        assertFailsWith<IllegalArgumentException> {
            bus.send(DuplexPeer.A, DuplexKind.Response, "wrong owner", request.id)
        }
        assertFailsWith<IllegalArgumentException> {
            bus.send(DuplexPeer.B, DuplexKind.Cancel, "wrong owner", request.id)
        }
        bus.send(DuplexPeer.A, DuplexKind.Cancel, "cancel", request.id)
        assertFailsWith<IllegalArgumentException> {
            bus.send(DuplexPeer.B, DuplexKind.Response, "late reply", request.id)
        }
    }

    @Test
    fun acknowledgedMessagesFreeBoundedInboxAndCannotBeReadAgain() = runTest {
        val bus = DuplexAgentBus(nowMillis = { 0L }, maxInbox = 1)
        val first = bus.send(DuplexPeer.A, DuplexKind.Message, "first")
        assertFailsWith<IllegalStateException> {
            bus.send(DuplexPeer.A, DuplexKind.Message, "second")
        }
        assertEquals(first.id, bus.receive(DuplexPeer.B).single().id)
        assertEquals(1, bus.acknowledge(DuplexPeer.B, first.seq))
        assertTrue(bus.receive(DuplexPeer.B).isEmpty())
        assertFailsWith<IllegalArgumentException> { bus.receive(DuplexPeer.B, after = 0L) }
        assertEquals("second", bus.send(DuplexPeer.A, DuplexKind.Message, "second").body)
    }

    @Test
    fun byteLimitsAndPendingRequestExpiryAreIndependent() = runTest {
        var clock = 0L
        val bus = DuplexAgentBus(nowMillis = { clock }, maxBodyBytes = 4, maxPendingRequests = 1,
            pendingTtlMillis = 10L)
        assertFailsWith<IllegalArgumentException> {
            bus.send(DuplexPeer.A, DuplexKind.Message, "€€")
        }
        val old = bus.send(DuplexPeer.A, DuplexKind.Request, "one")
        assertFailsWith<IllegalStateException> {
            bus.send(DuplexPeer.B, DuplexKind.Request, "two")
        }
        clock = 11L
        bus.send(DuplexPeer.B, DuplexKind.Request, "two")
        assertFailsWith<IllegalArgumentException> {
            bus.send(DuplexPeer.B, DuplexKind.Response, "late", old.id)
        }
    }
}
