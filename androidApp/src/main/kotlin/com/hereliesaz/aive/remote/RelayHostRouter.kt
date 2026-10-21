package com.hereliesaz.aive.remote

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The host end of the sealed relay protocol for one connection: opens each client frame, drops it
 * unless it is authentic, fresh and not a replay, runs the request through [actions] (the same
 * [RemoteActions] the bound service uses) and seals the reply. [send] hands a sealed `data` string
 * for one relay client id to the socket. Frames that fail any check are dropped without a reply,
 * so a stranger learns nothing. Protocol: docs/Remote-API.md, "Sealed frames and messages".
 */
internal class RelayHostRouter(
    private val room: String,
    key: ByteArray,
    private val actions: RemoteActions,
    private val scope: CoroutineScope,
    private val send: suspend (clientId: String, data: String) -> Unit,
    private val runStatus: StateFlow<RemoteRunStatus?>,
    private val outcomes: Flow<RemoteLaunchOutcome>,
    private val clock: () -> Long = System::currentTimeMillis,
    random: SecureRandom = SecureRandom(),
    private val guard: RelayReplayGuard = RelayReplayGuard(clock = clock),
) {
    private val cipher = RelayFrameCipher(key, room, random)
    private val sessionId = randomSessionId(random)
    private val sendLock = Mutex()
    private var nextSeq = 1L
    private val watchers = LinkedHashSet<String>()
    private val promptsRunning = AtomicInteger(0)

    /** Starts fanning run status and launch outcomes out to the clients that sent `watch`. */
    fun start() {
        scope.launch {
            runStatus.filterNotNull().collect { status -> fanOut { putRunStatus(status) } }
        }
        scope.launch {
            outcomes.collect { outcome ->
                fanOut {
                    put("t", "launch_outcome")
                    put("launchId", outcome.requestId)
                    put("started", outcome.started)
                    outcome.error?.let { put("error", it) }
                }
            }
        }
    }

    /** Handles one relay frame from [clientId]; never throws for a bad frame. */
    suspend fun onFrame(clientId: String, data: String) {
        val message = authenticate(data) ?: return
        val id = message.number("id") ?: 0L
        when (message.text("t")) {
            "ping" -> deliver(clientId) {
                put("t", "pong")
                put("id", id)
            }
            "watch" -> watch(clientId)
            "launch" -> launch(clientId, id, message)
            "prompt" -> prompt(clientId, id, message)
            else -> sendError(clientId, id, "Unknown message type")
        }
    }

    /** The decrypted, fresh, in-order message in [data], or null when it must be dropped. */
    private fun authenticate(data: String): JsonObject? {
        val opened = try {
            cipher.open(RelayProtocol.DIR_CLIENT_TO_HOST, data)
        } catch (_: RelayProtocolException) {
            return null
        }
        val message = try {
            Json.parseToJsonElement(opened.plaintext.decodeToString()).jsonObject
        } catch (_: IllegalArgumentException) {
            return null
        }
        val sid = message.text("sid")
        val seq = message.number("seq")
        val ts = message.number("ts")
        if (sid == null || seq == null || ts == null || seq != opened.seq) return null
        try {
            guard.accept(sid, seq, ts)
        } catch (_: RelayProtocolException) {
            return null
        }
        return message
    }

    private suspend fun watch(clientId: String) {
        synchronized(watchers) {
            watchers.remove(clientId)
            if (watchers.size >= MAX_WATCHERS) watchers.remove(watchers.first())
            watchers.add(clientId)
        }
        runStatus.value?.let { status -> deliver(clientId) { putRunStatus(status) } }
    }

    private suspend fun launch(clientId: String, id: Long, message: JsonObject) {
        val launchId = try {
            actions.launchWorkflow(
                projectName = message.text("projectName"),
                objective = message.text("objective"),
                repositoryUrl = message.text("repositoryUrl"),
            )
        } catch (problem: IllegalArgumentException) {
            sendError(clientId, id, problem.message ?: "Invalid launch request")
            return
        } catch (problem: IllegalStateException) {
            sendError(clientId, id, problem.message ?: "Could not queue the launch")
            return
        }
        deliver(clientId) {
            put("t", "launched")
            put("id", id)
            put("launchId", launchId)
        }
    }

    private suspend fun prompt(clientId: String, id: Long, message: JsonObject) {
        val prompt = message.text("prompt")
        when {
            prompt.isNullOrBlank() -> sendError(clientId, id, "Prompt is required")
            prompt.encodeToByteArray().size > RelayProtocol.MAX_PROMPT_BYTES ->
                sendError(clientId, id, "Prompt exceeds ${RelayProtocol.MAX_PROMPT_BYTES} bytes")
            promptsRunning.incrementAndGet() > MAX_CONCURRENT_PROMPTS -> {
                promptsRunning.decrementAndGet()
                sendError(clientId, id, "Too many prompts are running; try again shortly")
            }
            else -> {
                val providerId = message.text("providerId")?.takeIf(String::isNotBlank)
                val model = message.text("model")?.takeIf(String::isNotBlank)
                scope.launch {
                    try {
                        val text = actions.prompt(prompt, providerId, model)
                        val sent = deliver(clientId) {
                            put("t", "result")
                            put("id", id)
                            put("text", text)
                        }
                        if (!sent) sendError(clientId, id, "The reply is too large to send")
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        sendError(clientId, id, failure.message ?: failure::class.simpleName ?: "Prompt failed")
                    } finally {
                        promptsRunning.decrementAndGet()
                    }
                }
            }
        }
    }

    private suspend fun sendError(clientId: String, id: Long, error: String) {
        deliver(clientId) {
            put("t", "error")
            put("id", id)
            put("error", error)
        }
    }

    private suspend fun fanOut(fill: JsonObjectBuilder.() -> Unit) {
        val targets = synchronized(watchers) { watchers.toList() }
        targets.forEach { clientId -> deliver(clientId, fill) }
    }

    private fun JsonObjectBuilder.putRunStatus(status: RemoteRunStatus) {
        put("t", "run_status")
        put("runId", status.runId)
        put("objective", status.objective)
        put("runStatus", status.status)
        put("tasksTotal", status.tasksTotal)
        put("tasksDone", status.tasksDone)
        status.progressMessage?.let { put("progressMessage", it) }
    }

    /**
     * Seals and sends one message to [clientId]. Returns false when the message is too large to
     * send; a socket that has gone away is not an error here, the reconnect loop notices it.
     */
    private suspend fun deliver(clientId: String, fill: JsonObjectBuilder.() -> Unit): Boolean =
        sendLock.withLock {
            val seq = nextSeq
            val plaintext = buildJsonObject {
                put("sid", sessionId)
                put("seq", seq)
                put("ts", clock())
                fill()
            }.toString().encodeToByteArray()
            if (plaintext.size > RelayProtocol.MAX_PLAINTEXT_BYTES) return@withLock false
            nextSeq = seq + 1
            try {
                send(clientId, cipher.seal(RelayProtocol.DIR_HOST_TO_CLIENT, seq, plaintext))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The socket is closing; the link's read loop ends and reconnects.
            }
            true
        }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private companion object {
        const val MAX_WATCHERS = 16
        const val MAX_CONCURRENT_PROMPTS = 4
    }
}
