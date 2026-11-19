package com.hereliesaz.geministrator.chat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One Gateway frame: opcode, sequence number, event name and payload. */
internal data class DiscordFrame(val op: Int, val seq: Long?, val type: String?, val data: JsonElement?)

/** What to do after the Gateway closed the connection. */
internal enum class DiscordClose { Resume, ReidentifyFresh, Stop }

/** Discord's Gateway wire format (https://docs.discord.com/developers/events/gateway), as pure functions. */
internal object DiscordWire {
    const val GATEWAY_URL = "wss://gateway.discord.gg"
    const val API = "https://discord.com/api/v10"

    /** DIRECT_MESSAGES (1 shl 12) only. Without MESSAGE_CONTENT, Discord still sends the content of direct messages to the bot. */
    const val INTENTS_DIRECT_MESSAGES = 4096

    private val json = Json { ignoreUnknownKeys = true }

    fun parseFrame(text: String): DiscordFrame? {
        val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val op = (o["op"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val seq = (o["s"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val type = (o["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return DiscordFrame(op, seq, type, o["d"])
    }

    fun heartbeat(seq: Long?): String = buildJsonObject {
        put("op", 1)
        if (seq == null) put("d", JsonNull) else put("d", seq)
    }.toString()

    fun identify(key: String): String = buildJsonObject {
        put("op", 2)
        putJsonObject("d") {
            put("token", key)
            put("intents", INTENTS_DIRECT_MESSAGES)
            putJsonObject("properties") {
                put("os", "linux")
                put("browser", "aive")
                put("device", "aive")
            }
        }
    }.toString()

    fun resume(key: String, sessionId: String, seq: Long): String = buildJsonObject {
        put("op", 6)
        putJsonObject("d") {
            put("token", key)
            put("session_id", sessionId)
            put("seq", seq)
        }
    }.toString()

    fun heartbeatIntervalMillis(frame: DiscordFrame): Long? =
        ((frame.data as? JsonObject)?.get("heartbeat_interval") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it in 1_000L..120_000L }

    /** A direct message from a person. A message in a server has a guild id and is never direct. */
    fun message(d: JsonElement?): ChatInbound? {
        val o = d as? JsonObject ?: return null
        val author = o["author"] as? JsonObject ?: return null
        val userId = (author["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val channelId = (o["channel_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val content = (o["content"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
        return ChatInbound(
            platform = ChatPlatform.Discord,
            identity = ChatIdentity(userId = userId, chatId = channelId),
            isDirect = o["guild_id"] == null || o["guild_id"] is JsonNull,
            fromBot = (author["bot"] as? JsonPrimitive)?.contentOrNull == "true",
            text = content,
        )
    }

    /** 4004 means the key was refused and 4010 to 4014 mean this app's own setup is wrong: none is retried. */
    fun afterClose(code: Int?): DiscordClose = when (code) {
        4004, 4010, 4011, 4012, 4013, 4014 -> DiscordClose.Stop
        1000, 1001 -> DiscordClose.ReidentifyFresh
        4007, 4009 -> DiscordClose.ReidentifyFresh
        else -> DiscordClose.Resume
    }

    /** Seconds from a 429 answer's body (`retry_after`). */
    fun retryAfterSeconds(body: String): Double? {
        val o = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (o["retry_after"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    }

    fun isSnowflake(id: String): Boolean = id.length in 1..25 && id.all { it in '0'..'9' }
}

/**
 * Discord over the Gateway (outbound WebSocket) and the REST API. The app listens to direct messages only
 * ([DiscordWire.INTENTS_DIRECT_MESSAGES]; no privileged intent) and answers with `POST /channels/{id}/messages` in plain text
 * with `allowed_mentions.parse` empty, so nothing written to the keeper can ping anyone. It follows the Gateway rules:
 * a first heartbeat after interval times a random fraction, an acknowledgement before the next one or the connection is
 * dropped, Resume when the session is still good, a fresh Identify otherwise.
 */
class DiscordTransport(
    private val http: HttpClient,
    private val token: () -> String?,
    private val gatewayUrl: String = DiscordWire.GATEWAY_URL,
    private val apiUrl: String = DiscordWire.API,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ChatTransport {
    override val platform: ChatPlatform = ChatPlatform.Discord
    private val mutableLink = MutableStateFlow(ChatLink.Off)
    override val link: StateFlow<ChatLink> = mutableLink

    private class Session(var id: String? = null, var url: String? = null, var seq: Long? = null) {
        fun clear() {
            id = null
            url = null
            seq = null
        }
    }

    override suspend fun run(onInbound: suspend (ChatInbound) -> Unit) {
        val session = Session()
        var backoff = 2_000L
        mutableLink.value = ChatLink.Connecting
        while (true) {
            val key = token()
            if (key.isNullOrBlank()) {
                mutableLink.value = ChatLink.TokenRefused
                pause(5_000)
                continue
            }
            val close = try {
                connectOnce(key, session, onInbound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableLink.value = ChatLink.Failed
                DiscordClose.Resume
            }
            when (close) {
                DiscordClose.Stop -> {
                    mutableLink.value = ChatLink.TokenRefused
                    session.clear()
                    pause(60_000)
                }
                DiscordClose.ReidentifyFresh -> {
                    session.clear()
                    pause(backoff)
                }
                DiscordClose.Resume -> {
                    if (mutableLink.value != ChatLink.Connected) backoff = (backoff * 2).coerceAtMost(60_000L) else backoff = 2_000L
                    mutableLink.value = ChatLink.Failed
                    pause(backoff)
                }
            }
        }
    }

    private suspend fun connectOnce(key: String, session: Session, onInbound: suspend (ChatInbound) -> Unit): DiscordClose {
        mutableLink.value = ChatLink.Connecting
        val base = session.url ?: gatewayUrl
        val ws = http.webSocketSession("$base/?v=10&encoding=json")
        var heartbeatJob: Job? = null
        var acked = true
        var requested: DiscordClose? = null
        try {
            coroutineScope {
                try {
                    for (frame in ws.incoming) {
                        if (frame !is Frame.Text) continue
                        val f = DiscordWire.parseFrame(frame.readText()) ?: continue
                        f.seq?.let { session.seq = it }
                        when (f.op) {
                            10 -> {
                                val interval = DiscordWire.heartbeatIntervalMillis(f) ?: 41_250L
                                heartbeatJob?.cancel()
                                heartbeatJob = launch {
                                    delay((interval * Random.nextDouble()).toLong())
                                    while (true) {
                                        if (!acked) {
                                            // No acknowledgement since the last heartbeat: the connection is stale.
                                            ws.close(CloseReason(4000.toShort(), "stale"))
                                            return@launch
                                        }
                                        acked = false
                                        ws.send(Frame.Text(DiscordWire.heartbeat(session.seq)))
                                        delay(interval)
                                    }
                                }
                                val id = session.id
                                val seq = session.seq
                                ws.send(Frame.Text(if (id != null && seq != null) DiscordWire.resume(key, id, seq) else DiscordWire.identify(key)))
                            }
                            11 -> acked = true
                            1 -> ws.send(Frame.Text(DiscordWire.heartbeat(session.seq)))
                            7 -> {
                                requested = DiscordClose.Resume
                                ws.close(CloseReason(4000.toShort(), "reconnect"))
                            }
                            9 -> {
                                val resumable = (f.data as? JsonPrimitive)?.contentOrNull == "true"
                                if (!resumable) session.clear()
                                requested = if (resumable) DiscordClose.Resume else DiscordClose.ReidentifyFresh
                                ws.close(CloseReason(4000.toShort(), "invalid session"))
                            }
                            0 -> when (f.type) {
                                "READY" -> {
                                    val d = f.data as? JsonObject
                                    session.id = (d?.get("session_id") as? JsonPrimitive)?.contentOrNull
                                    session.url = (d?.get("resume_gateway_url") as? JsonPrimitive)?.contentOrNull
                                        ?.takeIf { it.startsWith("wss://") }
                                    mutableLink.value = ChatLink.Connected
                                }
                                "RESUMED" -> mutableLink.value = ChatLink.Connected
                                "MESSAGE_CREATE" -> DiscordWire.message(f.data)?.let { inbound ->
                                    try {
                                        onInbound(inbound)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // A message that cannot be handled must not drop the connection.
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                } finally {
                    heartbeatJob?.cancel()
                }
            }
        } finally {
            runCatching { ws.close() }
        }
        val code = runCatching { ws.closeReason.await()?.code?.toInt() }.getOrNull()
        return requested ?: DiscordWire.afterClose(code)
    }

    override suspend fun send(chatId: String, text: String): Boolean {
        val key = token()?.takeIf { it.isNotBlank() } ?: return false
        if (!DiscordWire.isSnowflake(chatId)) return false
        val payload = buildJsonObject {
            put("content", ChatText.clip(text, platform.maxChars))
            // No mention of anyone, whatever the text says.
            putJsonObject("allowed_mentions") { putJsonArray("parse") { } }
        }
        repeat(2) { attempt ->
            val response = try {
                withTimeoutOrNull(20_000L) {
                    http.post("$apiUrl/channels/$chatId/messages") {
                        header("Authorization", "Bot $key")
                        header("User-Agent", "DiscordBot (aive, 1)")
                        setBody(TextContent(payload.toString(), ContentType.Application.Json))
                    }
                } ?: return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            if (response.status.isSuccess()) return true
            if (response.status == HttpStatusCode.TooManyRequests && attempt == 0) {
                val seconds = DiscordWire.retryAfterSeconds(response.bodyAsText()) ?: 2.0
                pause((seconds.coerceIn(0.5, 30.0) * 1_000).toLong())
            } else {
                return false
            }
        }
        return false
    }
}
