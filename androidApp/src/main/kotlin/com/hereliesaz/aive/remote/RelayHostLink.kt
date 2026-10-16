package com.hereliesaz.aive.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The host WebSocket to the relay for one pairing. [run] creates the room on first use, then
 * connects, serves the sealed protocol through a [RelayHostRouter], and reconnects with capped
 * exponential backoff until cancelled. It deletes nothing: a dropped link or a stopped service
 * leaves the room (and its pairing) in place.
 */
internal class RelayHostLink(
    private val pairing: RemoteRelayPairing,
    private val actions: RemoteActions,
    private val httpClient: HttpClient,
    private val onStatus: (String) -> Unit,
) {
    suspend fun run() {
        var backoff = INITIAL_BACKOFF_MILLIS
        // Creating a room that exists rotates its client token and drops every client, so it is
        // done when the service starts and afterwards only when the relay says the room is gone.
        var createRoom = true
        while (true) {
            var connected = false
            var replaced = false
            try {
                if (createRoom) {
                    onStatus("Creating the room")
                    createRoom()
                    createRoom = false
                }
                onStatus("Connecting")
                val closeCode = serve { connected = true }
                if (closeCode == CLOSE_ROOM_DELETED) createRoom = true
                // Another device holds the host socket; trying again at full speed would only evict it back.
                replaced = closeCode == CLOSE_REPLACED
                onStatus(if (replaced) "Another copy of this host connected" else "Disconnected, reconnecting")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isMissingRoom(failure)) createRoom = true
                onStatus("Offline: ${failure.message ?: failure::class.simpleName}")
            }
            backoff = when {
                replaced -> MAX_BACKOFF_MILLIS
                connected -> INITIAL_BACKOFF_MILLIS
                else -> backoff
            }
            delay(backoff + Random.nextLong(JITTER_MILLIS))
            backoff = minOf(backoff * 2, MAX_BACKOFF_MILLIS)
        }
    }

    private suspend fun createRoom() {
        val response = httpClient.post("${pairing.relay}/v1/rooms/${pairing.room}") {
            header(HttpHeaders.Authorization, "Bearer ${pairing.hostToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("clientToken", pairing.clientToken) }.toString())
        }
        when (response.status.value) {
            201, 204 -> Unit
            403 -> throw IllegalStateException("The relay says this room belongs to another host; reset the pairing")
            else -> throw IllegalStateException("The relay answered ${response.status.value} to creating the room")
        }
    }

    /** Serves one connection until it closes; returns the close code the relay sent, if any. */
    private suspend fun serve(onConnected: () -> Unit): Int? {
        val socket = httpClient.webSocketSession(urlString = hostWebSocketUrl(pairing)) {
            header(HttpHeaders.Authorization, "Bearer ${pairing.hostToken}")
        }
        onConnected()
        onStatus("Connected")
        val job = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + job)
        try {
            val router = RelayHostRouter(
                room = pairing.room,
                key = pairing.key,
                actions = actions,
                scope = scope,
                send = { clientId, data -> socket.outgoing.send(Frame.Text(relayFrameJson(clientId, data))) },
                runStatus = AiveRemoteBridge.runStatus,
                outcomes = AiveRemoteBridge.outcomes,
            )
            router.start()
            for (frame in socket.incoming) {
                if (frame !is Frame.Text) continue
                val (from, data) = parseRelayFrame(frame.readText()) ?: continue
                router.onFrame(from, data)
            }
            return withTimeoutOrNull(CLOSE_REASON_WAIT_MILLIS) { socket.closeReason.await() }?.code?.toInt()
        } finally {
            job.cancel()
            withContext(NonCancellable) { runCatching { socket.close() } }
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val JITTER_MILLIS = 500L
        const val CLOSE_REASON_WAIT_MILLIS = 1_000L

        /** Close codes the relay sends (relay/src/worker.js). */
        const val CLOSE_REPLACED = 4000
        const val CLOSE_ROOM_DELETED = 4002
    }
}

/** `wss://` (or `ws://` for loopback) URL of the host socket for [pairing]. */
internal fun hostWebSocketUrl(pairing: RemoteRelayPairing): String =
    pairing.relay.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
        "/v1/rooms/${pairing.room}?role=host"

/** The relay frame that carries [data] to the client with relay id [clientId]. */
internal fun relayFrameJson(clientId: String, data: String): String =
    buildJsonObject {
        put("to", clientId)
        put("data", data)
    }.toString()

/** `(from, data)` of a frame the relay forwarded from a client, or null for anything else. */
internal fun parseRelayFrame(text: String): Pair<String, String>? {
    val frame = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: IllegalArgumentException) {
        return null
    }
    val from = (frame["from"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val data = (frame["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (from != null && data != null) from to data else null
}

/** True when [failure] is the relay answering 404 to the host socket: the room does not exist. */
internal fun isMissingRoom(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(5).any { cause ->
        cause.message?.let { Regex("""\b404\b""").containsMatchIn(it) || "No such room" in it } == true
    }
