package com.hereliesaz.aive.remote

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class RelayHostRouterTest {
    private val room = "room-0123456789abcdef"
    private val key = ByteArray(32) { (it * 3).toByte() }
    private var now = 1_700_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val actions = FakeActions()
    private val status = MutableStateFlow<RemoteRunStatus?>(null)
    private val outcomes = MutableSharedFlow<RemoteLaunchOutcome>(extraBufferCapacity = 16)

    /** What the router handed to the (fake) socket: relay client id to sealed data. */
    private val sent = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val router = RelayHostRouter(
        room = room,
        key = key,
        actions = actions,
        scope = scope,
        send = { clientId, data -> sent.send(clientId to data) },
        runStatus = status,
        outcomes = outcomes,
        clock = { now },
    )
    private val client = TestClient()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aPingGetsASealedPongWithTheHostsSessionAndSequence() = runBlocking<Unit> {
        router.onFrame("c1", client.frame { put("t", "ping"); put("id", 11) })
        val (to, pong) = nextMessage()
        assertEquals("c1", to)
        assertEquals("pong", pong.str("t"))
        assertEquals(11L, pong.num("id"))
        assertEquals(1L, pong.num("seq"))
        assertEquals(now, pong.num("ts"))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(pong.str("sid")))

        router.onFrame("c1", client.frame { put("t", "ping"); put("id", 12) })
        val second = nextMessage().second
        assertEquals(2L, second.num("seq"))
        assertEquals(pong.str("sid"), second.str("sid"))
    }

    @Test
    fun aPromptRunsThroughTheSharedActionsAndRepliesWithTheRequestId() = runBlocking<Unit> {
        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 5); put("prompt", "hi"); put("providerId", "kilo"); put("model", "m1") })
        val (_, result) = nextMessage()
        assertEquals("result", result.str("t"))
        assertEquals(5L, result.num("id"))
        assertEquals("echo:hi", result.str("text"))
        assertEquals(listOf<Triple<String, String?, String?>>(Triple("hi", "kilo", "m1")), actions.prompts.toList())
    }

    @Test
    fun aPromptFailureBecomesAnErrorNotAFallback() = runBlocking<Unit> {
        actions.promptFailure = IllegalStateException("Aive provider kilo is not linked")
        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 6); put("prompt", "hi") })
        val (_, error) = nextMessage()
        assertEquals("error", error.str("t"))
        assertEquals(6L, error.num("id"))
        assertEquals("Aive provider kilo is not linked", error.str("error"))
        // Blank provider and model are passed on as "unspecified", never filled in here.
        assertEquals(listOf<Triple<String, String?, String?>>(Triple("hi", null, null)), actions.prompts.toList())
    }

    @Test
    fun promptsOverTheCapAreRefusedWithoutRunning() = runBlocking<Unit> {
        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 7); put("prompt", "a".repeat(RelayProtocol.MAX_PROMPT_BYTES + 1)) })
        val (_, error) = nextMessage()
        assertEquals("error", error.str("t"))
        assertEquals(7L, error.num("id"))
        assertTrue(error.str("error").contains("500000"))
        assertTrue(actions.prompts.isEmpty())

        // Multi-byte characters count as bytes, not characters.
        val wide = "é".repeat(RelayProtocol.MAX_PROMPT_BYTES / 2 + 1)
        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 8); put("prompt", wide) })
        assertEquals("error", nextMessage().second.str("t"))
        assertTrue(actions.prompts.isEmpty())

        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 9); put("prompt", "a".repeat(RelayProtocol.MAX_PROMPT_BYTES)) })
        assertEquals("result", nextMessage().second.str("t"))
        assertEquals(1, actions.prompts.size)
    }

    @Test
    fun blankPromptsAndUnknownTypesAreErrors() = runBlocking<Unit> {
        router.onFrame("c1", client.frame { put("t", "prompt"); put("id", 1); put("prompt", "  ") })
        assertEquals("Prompt is required", nextMessage().second.str("error"))
        router.onFrame("c1", client.frame { put("t", "format-disk"); put("id", 2) })
        val unknown = nextMessage().second
        assertEquals("error", unknown.str("t"))
        assertEquals(2L, unknown.num("id"))
        assertTrue(actions.prompts.isEmpty())
    }

    @Test
    fun launchesGoThroughTheSharedValidationAndQueue() = runBlocking<Unit> {
        router.onFrame("c1", client.frame {
            put("t", "launch"); put("id", 20); put("projectName", "P"); put("objective", "ship it"); put("repositoryUrl", "https://github.com/a/b")
        })
        val launched = nextMessage().second
        assertEquals("launched", launched.str("t"))
        assertEquals(20L, launched.num("id"))
        assertEquals(42L, launched.num("launchId"))
        assertEquals(listOf<Triple<String?, String?, String?>>(Triple("P", "ship it", "https://github.com/a/b")), actions.launches.toList())

        actions.launchFailure = IllegalArgumentException("Objective is required")
        router.onFrame("c1", client.frame { put("t", "launch"); put("id", 21); put("projectName", "P") })
        assertEquals("Objective is required", nextMessage().second.str("error"))

        actions.launchFailure = IllegalStateException("10 launches are already waiting for The Aive to open")
        router.onFrame("c1", client.frame { put("t", "launch"); put("id", 22); put("projectName", "P"); put("objective", "x") })
        assertEquals("10 launches are already waiting for The Aive to open", nextMessage().second.str("error"))
    }

    @Test
    fun framesThatFailAnyCheckAreDroppedSilently() = runBlocking<Unit> {
        val ping = client.frame { put("t", "ping"); put("id", 1) }
        router.onFrame("c1", ping)
        nextMessage()

        // Replay of the same frame, and of an older one.
        router.onFrame("c1", ping)
        assertNothingSent()
        val older = TestClient(sid = client.sid).frame { put("t", "ping"); put("id", 2) } // seq 1 again
        router.onFrame("c1", older)
        assertNothingSent()

        // Stale and future timestamps.
        router.onFrame("c1", client.frame(ts = now - 10 * 60 * 1000L - 1) { put("t", "ping"); put("id", 3) })
        router.onFrame("c1", client.frame(ts = now + 10 * 60 * 1000L + 1) { put("t", "ping"); put("id", 4) })
        assertNothingSent()

        // Wrong key, wrong room, wrong direction.
        router.onFrame("c1", TestClient(key = ByteArray(32)).frame { put("t", "ping"); put("id", 5) })
        router.onFrame("c1", TestClient(room = "room-other-0123456789").frame { put("t", "ping"); put("id", 6) })
        router.onFrame("c1", client.frame(direction = RelayProtocol.DIR_HOST_TO_CLIENT) { put("t", "ping"); put("id", 7) })
        assertNothingSent()

        // Garbage, a prefix that disagrees with the JSON seq, a missing sid, a bad sid.
        router.onFrame("c1", "garbage")
        router.onFrame("c1", client.rawFrame(prefixSeq = 50, body = buildJsonObject { put("sid", client.sid); put("seq", 99); put("ts", now); put("t", "ping") }))
        router.onFrame("c1", client.rawFrame(prefixSeq = 60, body = buildJsonObject { put("seq", 60); put("ts", now); put("t", "ping") }))
        router.onFrame("c1", client.rawFrame(prefixSeq = 61, body = buildJsonObject { put("sid", "NOT-HEX"); put("seq", 61); put("ts", now); put("t", "ping") }))
        router.onFrame("c1", client.rawFrame(prefixSeq = 62, body = JsonObject(emptyMap())))
        assertNothingSent()

        // A good frame still works afterwards.
        router.onFrame("c1", client.frame { put("t", "ping"); put("id", 8) })
        assertEquals(8L, nextMessage().second.num("id"))
    }

    @Test
    fun aNewClientSessionStartsOverAtSequenceOne() = runBlocking<Unit> {
        router.onFrame("c1", client.frame { put("t", "ping"); put("id", 1) })
        nextMessage()
        val reconnected = TestClient(sid = "fedcba9876543210")
        router.onFrame("c2", reconnected.frame { put("t", "ping"); put("id", 2) })
        val (to, pong) = nextMessage()
        assertEquals("c2", to)
        assertEquals(2L, pong.num("id"))
    }

    @Test
    fun onlyWatchersGetRunStatusAndLaunchOutcomes() = runBlocking<Unit> {
        router.start()
        outcomes.subscriptionCount.first { it > 0 }

        router.onFrame("watcher", client.frame { put("t", "watch"); put("id", 30) })
        // Nothing to report yet, and a second client that never asked sees nothing.
        router.onFrame("bystander", TestClient(sid = "1111111111111111").frame { put("t", "ping"); put("id", 31) })
        assertEquals("pong", nextMessage().second.str("t"))

        status.value = RemoteRunStatus("run-1", "build", "Running", tasksTotal = 4, tasksDone = 1, progressMessage = "compiling")
        val (to, run) = nextMessage()
        assertEquals("watcher", to)
        assertEquals("run_status", run.str("t"))
        assertEquals("run-1", run.str("runId"))
        assertEquals("build", run.str("objective"))
        assertEquals("Running", run.str("runStatus"))
        assertEquals(4L, run.num("tasksTotal"))
        assertEquals(1L, run.num("tasksDone"))
        assertEquals("compiling", run.str("progressMessage"))

        outcomes.emit(RemoteLaunchOutcome(42, started = false, error = "boom"))
        val (to2, outcome) = nextMessage()
        assertEquals("watcher", to2)
        assertEquals("launch_outcome", outcome.str("t"))
        assertEquals(42L, outcome.num("launchId"))
        assertEquals(false, (outcome["started"] as JsonPrimitive).content.toBoolean())
        assertEquals("boom", outcome.str("error"))

        outcomes.emit(RemoteLaunchOutcome(43, started = true, error = null))
        val started = nextMessage().second
        assertEquals(43L, started.num("launchId"))
        assertTrue(started["error"] == null)
        assertNothingSent()
    }

    @Test
    fun watchingReportsTheCurrentRunImmediately() = runBlocking<Unit> {
        status.value = RemoteRunStatus("run-2", "ship", "Running", tasksTotal = 2, tasksDone = 0, progressMessage = null)
        router.onFrame("c1", client.frame { put("t", "watch"); put("id", 40) })
        val (_, run) = nextMessage()
        assertEquals("run-2", run.str("runId"))
        assertNull(run["progressMessage"])
    }

    /** The reference client side of the protocol: seals client-to-host messages. */
    private inner class TestClient(
        val sid: String = "0123456789abcdef",
        key: ByteArray = this@RelayHostRouterTest.key,
        room: String = this@RelayHostRouterTest.room,
    ) {
        private val cipher = RelayFrameCipher(key, room)
        private var seq = 0L

        fun frame(
            ts: Long = now,
            direction: String = RelayProtocol.DIR_CLIENT_TO_HOST,
            build: JsonObjectBuilder.() -> Unit,
        ): String {
            seq += 1
            val body = buildJsonObject {
                put("sid", sid)
                put("seq", seq)
                put("ts", ts)
                build()
            }
            return cipher.seal(direction, seq, body.toString().encodeToByteArray())
        }

        fun rawFrame(prefixSeq: Long, body: JsonObject): String =
            cipher.seal(RelayProtocol.DIR_CLIENT_TO_HOST, prefixSeq, body.toString().encodeToByteArray())
    }

    private val hostSide = RelayFrameCipher(key, room)

    private suspend fun nextMessage(): Pair<String, JsonObject> {
        val (clientId, data) = withTimeout(5_000) { sent.receive() }
        val opened = hostSide.open(RelayProtocol.DIR_HOST_TO_CLIENT, data)
        val message = Json.parseToJsonElement(opened.plaintext.decodeToString()).jsonObject
        assertEquals(opened.seq, message.num("seq"), "the prefix and the JSON carry the same sequence number")
        return clientId to message
    }

    private fun assertNothingSent() {
        assertTrue(sent.tryReceive().isFailure, "no reply expected")
    }

    private fun JsonObject.str(name: String) = (getValue(name) as JsonPrimitive).content
    private fun JsonObject.num(name: String) = (getValue(name) as JsonPrimitive).content.toLong()

    private class FakeActions : RemoteActions {
        val prompts = mutableListOf<Triple<String, String?, String?>>()
        val launches = mutableListOf<Triple<String?, String?, String?>>()
        var promptFailure: Throwable? = null
        var launchFailure: RuntimeException? = null

        override suspend fun prompt(prompt: String, providerId: String?, model: String?): String {
            synchronized(prompts) { prompts += Triple(prompt, providerId, model) }
            promptFailure?.let { throw it }
            return "echo:" + prompt.take(8)
        }

        override fun launchWorkflow(projectName: String?, objective: String?, repositoryUrl: String?): Long {
            launchFailure?.let { throw it }
            launches += Triple(projectName, objective, repositoryUrl)
            return 42
        }
    }
}
