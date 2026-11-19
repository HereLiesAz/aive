package com.hereliesaz.geministrator.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatWireTest {
    private val token = "123456789:AAH-abcdefghijklmnopqrstuvwxyz012345"

    // ---- text ----

    @Test
    fun safeTextIsScrubbedLinkFreeAndBounded() {
        val out = ChatText.safe("key $token and sk-abcdefghijklmnopqrstuvwxyz see https://x.example/a?b www.evil.test now", 500, listOf(token))
        assertTrue(token !in out && "sk-abc" !in out && "x.example" !in out && "evil.test" !in out, out)
        assertTrue(ChatText.safe("a".repeat(5_000), 100).length <= 100)
        assertEquals("ab…", ChatText.clip("abcdef", 3))
    }

    @Test
    fun aKeySplitByInvisibleCharactersIsStillScrubbed() {
        val split = "sk-abcdefgh​ijklmnopqrstuvwxyz"
        assertFalse("sk-abcdefghij" in ChatText.safe(split, 200))
        assertTrue("sk-abcdefgh" !in ChatText.safe(split, 200))
    }

    @Test
    fun clipNeverSplitsASurrogatePair() {
        val text = "ab" + "😀" + "cd"
        val clipped = ChatText.clip(text, 4)
        assertTrue(clipped.none { it.isHighSurrogate() && !clipped.contains("😀") })
    }

    @Test
    fun theTokenShapesAreRedactedByTheOneRedactor() {
        val discord = "MTA2NzA5" + "NjY1NDU4" + "MjQ3MDM4" + "OA" + "." + "GabcDE" + "." + "abcdefgh" + "ijklmnop" + "qrstuvwx" + "yz012345" + "6789"
        assertTrue(token !in com.hereliesaz.geministrator.control.ControlRedaction.scrub("t=$token"))
        assertTrue(discord !in com.hereliesaz.geministrator.control.ControlRedaction.scrub("d $discord end"))
    }

    // ---- telegram ----

    @Test
    fun telegramUpdatesParseAndOnlyPrivateChatsAreDirect() {
        val body = """{"ok":true,"result":[
          {"update_id":10,"message":{"message_id":1,"from":{"id":42,"is_bot":false},"chat":{"id":42,"type":"private"},"text":"/status"}},
          {"update_id":11,"message":{"message_id":2,"from":{"id":42,"is_bot":false},"chat":{"id":-100,"type":"supergroup"},"text":"/status"}},
          {"update_id":12,"channel_post":{"message_id":3,"chat":{"id":-5,"type":"channel"},"text":"x"}},
          {"update_id":13,"message":{"message_id":4,"from":{"id":7,"is_bot":true},"chat":{"id":7,"type":"private"},"text":"hi"}},
          {"update_id":14,"message":{"message_id":5,"from":{"id":42},"chat":{"id":42,"type":"private"}}}
        ]}"""
        val batch = TelegramWire.parseUpdates(body)
        assertEquals(15L, batch.nextOffset)
        assertEquals(3, batch.inbound.size)
        assertTrue(batch.inbound[0].isDirect && !batch.inbound[0].fromBot)
        assertFalse(batch.inbound[1].isDirect)
        assertTrue(batch.inbound[2].fromBot)
    }

    @Test
    fun telegramGarbageIsEmptyNotAnException() {
        assertEquals(0, TelegramWire.parseUpdates("not json").inbound.size)
        assertNull(TelegramWire.parseUpdates("""{"ok":true}""").nextOffset)
        assertEquals(7, TelegramWire.retryAfterSeconds("""{"ok":false,"parameters":{"retry_after":7}}"""))
    }

    @Test
    fun telegramSendIsPlainTextWithNoParseModeAndNoPreview(): Unit = runBlocking {
        var url = ""
        var body = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = request.body.toByteArray().decodeToString()
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val transport = TelegramTransport(HttpClient(engine), { token })
        assertTrue(transport.send("42", "hello *bold* <b>x</b>"))
        assertTrue(url.endsWith("/bot$token/sendMessage"))
        assertTrue("parse_mode" !in body, body)
        assertTrue("\"is_disabled\":true" in body, body)
        assertTrue("\"chat_id\":\"42\"" in body, body)
    }

    @Test
    fun telegramSendWithNoTokenSendsNothing(): Unit = runBlocking {
        var called = false
        val engine = MockEngine { called = true; respond("{}", HttpStatusCode.OK) }
        assertFalse(TelegramTransport(HttpClient(engine), { null }).send("42", "x"))
        assertFalse(called)
    }

    @Test
    fun telegramSendRetriesOnceAfterFloodControl(): Unit = runBlocking {
        var n = 0
        val engine = MockEngine {
            n += 1
            if (n == 1) respond("""{"ok":false,"parameters":{"retry_after":1}}""", HttpStatusCode.TooManyRequests)
            else respond("""{"ok":true}""", HttpStatusCode.OK)
        }
        val waits = mutableListOf<Long>()
        val transport = TelegramTransport(HttpClient(engine), { token }, pause = { waits += it })
        assertTrue(transport.send("42", "x"))
        assertEquals(listOf(1_000L), waits)
        assertEquals(2, n)
    }

    // ---- discord ----

    @Test
    fun discordAsksForDirectMessagesOnlyAndNoPrivilegedIntent() {
        assertEquals(1 shl 12, DiscordWire.INTENTS_DIRECT_MESSAGES)
        val identify = DiscordWire.identify("KEY")
        assertTrue("\"op\":2" in identify && "\"intents\":4096" in identify, identify)
        assertEquals(0, DiscordWire.INTENTS_DIRECT_MESSAGES and (1 shl 15), "MESSAGE_CONTENT is not asked for")
    }

    @Test
    fun discordFramesParse() {
        val hello = DiscordWire.parseFrame("""{"op":10,"d":{"heartbeat_interval":41250}}""")!!
        assertEquals(10, hello.op)
        assertEquals(41_250L, DiscordWire.heartbeatIntervalMillis(hello))
        val dispatch = DiscordWire.parseFrame("""{"op":0,"s":5,"t":"MESSAGE_CREATE","d":{}}""")!!
        assertEquals(5L, dispatch.seq)
        assertEquals("MESSAGE_CREATE", dispatch.type)
        assertNull(DiscordWire.parseFrame("nope"))
        assertEquals("""{"op":1,"d":null}""", DiscordWire.heartbeat(null))
        assertEquals("""{"op":1,"d":9}""", DiscordWire.heartbeat(9))
        assertTrue("\"session_id\":\"s\"" in DiscordWire.resume("K", "s", 3) && "\"op\":6" in DiscordWire.resume("K", "s", 3))
    }

    @Test
    fun onlyDirectMessagesFromPeopleAreDirect() {
        val dmFrame = DiscordWire.parseFrame("""{"op":0,"t":"MESSAGE_CREATE","d":{"channel_id":"900","content":"/status","author":{"id":"42"}}}""")!!
        val dm = DiscordWire.message(dmFrame.data)!!
        assertTrue(dm.isDirect && !dm.fromBot)
        assertEquals(ChatIdentity("42", "900"), dm.identity)
        val guild = DiscordWire.message(DiscordWire.parseFrame("""{"op":0,"d":{"guild_id":"1","channel_id":"900","content":"x","author":{"id":"42"}}}""")!!.data)!!
        assertFalse(guild.isDirect)
        val bot = DiscordWire.message(DiscordWire.parseFrame("""{"op":0,"d":{"channel_id":"900","content":"x","author":{"id":"3","bot":true}}}""")!!.data)!!
        assertTrue(bot.fromBot)
    }

    @Test
    fun discordCloseCodesDecideRetry() {
        assertEquals(DiscordClose.Stop, DiscordWire.afterClose(4004))
        assertEquals(DiscordClose.Stop, DiscordWire.afterClose(4013))
        assertEquals(DiscordClose.Stop, DiscordWire.afterClose(4014))
        assertEquals(DiscordClose.ReidentifyFresh, DiscordWire.afterClose(1000))
        assertEquals(DiscordClose.Resume, DiscordWire.afterClose(4000))
        assertEquals(DiscordClose.Resume, DiscordWire.afterClose(null))
    }

    @Test
    fun discordSendMentionsNoOneAndUsesTheBotHeaderAndRefusesOddChannelIds(): Unit = runBlocking {
        var auth: String? = null
        var url = ""
        var body = ""
        val engine = MockEngine { request ->
            auth = request.headers[HttpHeaders.Authorization]
            url = request.url.toString()
            body = request.body.toByteArray().decodeToString()
            respond("{}", HttpStatusCode.OK)
        }
        val transport = DiscordTransport(HttpClient(engine), { token })
        assertTrue(transport.send("900", "hello @everyone"))
        assertEquals("Bot $token", auth)
        assertTrue(url.endsWith("/channels/900/messages"))
        assertTrue("\"allowed_mentions\":{\"parse\":[]}" in body, body)
        assertFalse(transport.send("900/../../users/@me", "x"))
    }

    @Test
    fun platformLimits() {
        assertEquals(4096, ChatPlatform.Telegram.maxChars)
        assertEquals(2000, ChatPlatform.Discord.maxChars)
        assertTrue(ChatPlatform.entries.all { it.safeChars < it.maxChars })
    }
}
