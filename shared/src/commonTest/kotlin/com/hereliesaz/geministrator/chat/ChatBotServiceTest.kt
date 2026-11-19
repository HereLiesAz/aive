package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.queen.QueenLevel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatBotServiceTest {
    private val keeper = ChatIdentity("42", "42")
    private val token = "123456789:AAH-abcdefghijklmnopqrstuvwxyz012345"

    private class Rig(paired: ChatIdentity? = null, val host: RecordingHost = RecordingHost()) {
        val clock = TestClock()
        val transport = FakeTransport()
        val storedPairing = mutableListOf<ChatIdentity?>()
        val runs = mutableListOf<ChatRunSnapshot>()
        val service = ChatBotService(
            platform = ChatPlatform.Telegram,
            transport = transport,
            pairing = ChatPairing(clock::read, { "123456" }, initiallyPaired = paired),
            handler = ChatCommandHandler(host, clock::read, ChatPlatform.Telegram.safeChars),
            notifier = ChatNotifier({ QueenLevel.Quiet }, clock::read),
            attention = ChatAttentionSource { runs.toList() },
            clock = clock::read,
            secrets = { listOf("123456789:AAH-abcdefghijklmnopqrstuvwxyz012345") },
            onPairedChanged = { storedPairing += it },
        )
    }

    @Test
    fun aStrangerIsIgnoredSilentlyOncePaired(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.onInbound(dm("66", "/status"))
        rig.service.onInbound(dm("66", "/run hive-a anything"))
        rig.service.onInbound(dm("66", "/pair"))
        assertEquals(emptyList(), rig.transport.sent, "a stranger gets no reply at all")
        assertEquals(emptyList(), rig.host.calls, "and nothing reaches the app")
        assertEquals(3L, rig.service.ignored)
    }

    @Test
    fun groupsChannelsAndBotsAreIgnored(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.onInbound(dm("42", "/status", chat = "-100", direct = false))
        rig.service.onInbound(dm("42", "/status", bot = true))
        assertEquals(emptyList(), rig.transport.sent)
        assertEquals(emptyList(), rig.host.calls)
        assertEquals(2L, rig.service.ignored)
    }

    @Test
    fun theSameUserInAnotherChatIsAStranger(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.onInbound(dm("42", "/status", chat = "999"))
        assertEquals(emptyList(), rig.transport.sent)
    }

    @Test
    fun strangersAreRateLimitedAndCounted(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        repeat(20) { rig.service.onInbound(dm("66", "/status")) }
        assertEquals(6L, rig.service.ignored)
        assertEquals(14L, rig.service.rateLimited)
        assertEquals(emptyList(), rig.transport.sent)
    }

    @Test
    fun theKeepersMessagesAreRateLimitedToo(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        repeat(25) { rig.service.onInbound(dm("42", "/help")) }
        assertEquals(20, rig.transport.sent.size)
        assertEquals(5L, rig.service.rateLimited)
    }

    @Test
    fun statusIsAnsweredToThePairedChatOnly(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.onInbound(dm("42", "/status"))
        val (to, text) = rig.transport.sent.single()
        assertEquals("42", to)
        assertTrue("Live: 1" in text && "Waiting on you: 1" in text, text)
        assertTrue("open The Aive to approve" in text)
    }

    @Test
    fun pairingEndToEnd(): Unit = runBlocking {
        val rig = Rig()
        // Before pairing only /pair and its code are answered.
        rig.service.onInbound(dm("42", "/status"))
        rig.service.onInbound(dm("42", "hello"))
        assertEquals(emptyList(), rig.transport.sent)
        rig.service.onInbound(dm("42", "/pair"))
        assertEquals(1, rig.transport.sent.size)
        assertNotNull(rig.service.pairing.pending())
        // A second person cannot take over the waiting code, and gets nothing back.
        rig.service.onInbound(dm("66", "/pair"))
        rig.service.onInbound(dm("66", "123456"))
        assertEquals(1, rig.transport.sent.size)
        assertNull(rig.service.pairing.paired)
        rig.service.onInbound(dm("42", "000000"))
        assertTrue("not right" in rig.transport.sent.last().second)
        rig.service.onInbound(dm("42", "123456"))
        assertEquals(keeper, rig.service.pairing.paired)
        assertEquals(listOf<ChatIdentity?>(keeper), rig.storedPairing)
        assertTrue("decided in The Aive" in rig.transport.sent.last().second)
        // And now commands work for that chat.
        rig.service.onInbound(dm("42", "/runs"))
        assertTrue(rig.host.calls.contains("listRuns"))
    }

    @Test
    fun anExpiredCodeNeedsANewPair(): Unit = runBlocking {
        val rig = Rig()
        rig.service.onInbound(dm("42", "/pair"))
        rig.clock.now += ChatPairing.VALID_MILLIS + 1
        rig.service.onInbound(dm("42", "123456"))
        assertNull(rig.service.pairing.paired)
    }

    @Test
    fun unpairingForgetsTheKeeper(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.unpair()
        assertEquals(listOf<ChatIdentity?>(null), rig.storedPairing)
        rig.service.onInbound(dm("42", "/status"))
        assertEquals(emptyList(), rig.transport.sent)
    }

    @Test
    fun postToKeeperGoesOnlyToThePairedChatAndIsScrubbed(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        assertTrue(rig.service.postToKeeper("done. key $token and see https://evil.example/x"))
        val (to, text) = rig.transport.sent.single()
        assertEquals("42", to)
        assertTrue(token !in text && "evil.example" !in text, text)
        val unpaired = Rig()
        assertEquals(false, unpaired.service.postToKeeper("hello"))
        assertEquals(emptyList(), unpaired.transport.sent)
    }

    @Test
    fun theTokenNeverLeavesInAReply(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.service.onInbound(dm("42", "/run hive-a use $token"))
        // The refusal and the reply are built from the app's words; the key is also masked if it ever appeared.
        rig.transport.sent.forEach { assertTrue(token !in it.second) }
    }

    @Test
    fun notificationsGoToThePairedChatWhenSomethingNeedsTheKeeper(): Unit = runBlocking {
        val rig = Rig(paired = keeper)
        rig.runs += ChatRunSnapshot("run-1", "Running", emptyList())
        rig.service.notifyOnce()
        assertEquals(emptyList(), rig.transport.sent)
        rig.runs.clear()
        rig.runs += ChatRunSnapshot("run-1", "Running", listOf("approval|run-1|t|1"))
        rig.service.notifyOnce()
        val (to, text) = rig.transport.sent.single()
        assertEquals("42", to)
        assertTrue("Open The Aive to approve." in text)
    }
}
