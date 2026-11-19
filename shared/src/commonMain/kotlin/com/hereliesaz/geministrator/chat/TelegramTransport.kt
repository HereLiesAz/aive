package com.hereliesaz.geministrator.chat

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a getUpdates answer holds: the messages, and the offset that confirms them. */
internal data class TelegramBatch(val inbound: List<ChatInbound>, val nextOffset: Long?)

/** Telegram's wire format, parsed totally: a malformed answer gives an empty batch, never an exception. */
internal object TelegramWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseUpdates(body: String): TelegramBatch {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return TelegramBatch(emptyList(), null)
        val result = root["result"] as? JsonArray ?: return TelegramBatch(emptyList(), null)
        var next: Long? = null
        val inbound = ArrayList<ChatInbound>()
        for (update in result) {
            val u = update as? JsonObject ?: continue
            val id = u.long("update_id")
            if (id != null && (next == null || id + 1 > next)) next = id + 1
            val message = u["message"] as? JsonObject ?: continue
            val chat = message["chat"] as? JsonObject ?: continue
            val from = message["from"] as? JsonObject
            val chatId = chat.idText("id") ?: continue
            val userId = from?.idText("id") ?: continue
            val text = (message["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            inbound += ChatInbound(
                platform = ChatPlatform.Telegram,
                identity = ChatIdentity(userId = userId, chatId = chatId),
                // Only a one-to-one chat counts. Groups, supergroups and channels are never direct.
                isDirect = (chat["type"] as? JsonPrimitive)?.contentOrNull == "private",
                fromBot = (from?.get("is_bot") as? JsonPrimitive)?.contentOrNull == "true",
                text = text,
            )
        }
        return TelegramBatch(inbound, next)
    }

    /** Seconds Telegram asks us to wait ("parameters.retry_after"), if the answer says so. */
    fun retryAfterSeconds(body: String): Int? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val parameters = root["parameters"] as? JsonObject ?: return null
        return (parameters["retry_after"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.idText(key: String): String? = this.long(key)?.toString()
}

/**
 * Telegram over the Bot API (https://core.telegram.org/bots/api): outbound HTTPS only, long polling with `getUpdates`
 * (no webhook, no open port) and `sendMessage` in plain text with link previews off. Only `message` updates are asked
 * for, and only one-to-one chats are passed on. The key is part of the request path (Telegram's design), so nothing here
 * logs a URL, and no error text from the engine is kept.
 */
class TelegramTransport(
    private val http: HttpClient,
    private val token: () -> String?,
    private val baseUrl: String = "https://api.telegram.org",
    private val pollSeconds: Int = 30,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ChatTransport {
    override val platform: ChatPlatform = ChatPlatform.Telegram
    private val mutableLink = MutableStateFlow(ChatLink.Off)
    override val link: StateFlow<ChatLink> = mutableLink

    private fun url(method: String, key: String) = "$baseUrl/bot$key/$method"

    private fun body(json: JsonObject) = TextContent(json.toString(), ContentType.Application.Json)

    override suspend fun run(onInbound: suspend (ChatInbound) -> Unit) {
        var offset: Long? = null
        var backoff = 2_000L
        mutableLink.value = ChatLink.Connecting
        while (true) {
            val key = token()
            if (key.isNullOrBlank()) {
                mutableLink.value = ChatLink.TokenRefused
                pause(5_000)
                continue
            }
            try {
                val response: HttpResponse = http.post(url("getUpdates", key)) {
                    setBody(
                        body(
                            buildJsonObject {
                                if (offset != null) put("offset", offset!!)
                                put("timeout", pollSeconds)
                                putJsonArray("allowed_updates") { add(JsonPrimitive("message")) }
                            },
                        ),
                    )
                }
                val text = response.bodyAsText()
                when {
                    response.status.isSuccess() -> {
                        mutableLink.value = ChatLink.Connected
                        backoff = 2_000L
                        val batch = TelegramWire.parseUpdates(text)
                        for (message in batch.inbound) {
                            try {
                                onInbound(message)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // One message that cannot be handled must not stop the poll or repeat forever.
                            }
                        }
                        batch.nextOffset?.let { offset = it }
                    }
                    response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.NotFound -> {
                        mutableLink.value = ChatLink.TokenRefused
                        pause(60_000)
                    }
                    response.status == HttpStatusCode.TooManyRequests -> {
                        mutableLink.value = ChatLink.Failed
                        pause(((TelegramWire.retryAfterSeconds(text) ?: 5).coerceIn(1, 120)) * 1_000L)
                    }
                    else -> {
                        // 409: another poller or a webhook holds this bot. Wait and look again.
                        mutableLink.value = ChatLink.Failed
                        pause(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableLink.value = ChatLink.Failed
                pause(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    override suspend fun send(chatId: String, text: String): Boolean {
        val key = token()?.takeIf { it.isNotBlank() } ?: return false
        val payload = buildJsonObject {
            put("chat_id", chatId)
            put("text", ChatText.clip(text, platform.maxChars))
            putJsonObject("link_preview_options") { put("is_disabled", true) }
        }
        repeat(2) { attempt ->
            val response = try {
                http.post(url("sendMessage", key)) { setBody(body(payload)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            if (response.status.isSuccess()) return true
            if (response.status == HttpStatusCode.TooManyRequests && attempt == 0) {
                val wait = TelegramWire.retryAfterSeconds(response.bodyAsText()) ?: 3
                pause(wait.coerceIn(1, 30) * 1_000L)
            } else {
                return false
            }
        }
        return false
    }
}
