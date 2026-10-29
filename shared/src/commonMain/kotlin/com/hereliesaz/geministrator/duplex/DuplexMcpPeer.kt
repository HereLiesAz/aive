package com.hereliesaz.geministrator.duplex

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Aive's bidirectional MCP participant. [endpoint] is the peer-specific /mcp/a or /mcp/b URL.
 * The caller owns [client], its credential, and explicit pairing; no provider is chosen here.
 * Send and receive remain independent. SSE is a low-latency hint, not the acknowledged inbox.
 * This transport does not execute received instructions or grant any workflow permissions.
 */
class DuplexMcpPeer(
    client: HttpClient,
    private val endpoint: String,
    private val bearerToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val safeClient: HttpClient
    private val sequenceMutex = Mutex()
    private var nextRpcId = 1L

    init {
        require(bearerToken.isNotBlank()) { "A peer token is required" }
        val url = Url(endpoint)
        require(url.protocol == URLProtocol.HTTPS ||
            (url.protocol == URLProtocol.HTTP && url.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "Duplex endpoints must use HTTPS or loopback HTTP"
        }
        require(url.encodedPath == "/mcp/a" || url.encodedPath == "/mcp/b") {
            "Duplex endpoint must identify /mcp/a or /mcp/b"
        }
        require(url.parameters.isEmpty() && url.user == null && url.password == null) {
            "Duplex endpoint must not contain credentials or query parameters"
        }
        safeClient = client.config { followRedirects = false }
    }

    suspend fun send(kind: DuplexKind, body: String, replyTo: String? = null): DuplexEnvelope {
        require(body.encodeToByteArray().size <= 32_768) { "duplex message exceeds byte limit" }
        val arguments = buildJsonObject {
            put("kind", kind.name.lowercase())
            put("body", body)
            if (replyTo != null) put("replyTo", replyTo)
        }
        return decodeEnvelope(call("duplex_send", arguments).jsonObject)
    }

    suspend fun receive(after: Long? = null, limit: Int = 50): List<DuplexEnvelope> {
        val arguments = buildJsonObject {
            if (after != null) put("after", after)
            put("limit", limit)
        }
        return call("duplex_receive", arguments).jsonArray.map { decodeEnvelope(it.jsonObject) }
    }

    suspend fun acknowledge(through: Long): Int =
        call("duplex_ack", buildJsonObject { put("through", through) })
            .jsonObject.getValue("acknowledged").jsonPrimitive.int

    suspend fun status(): DuplexRemoteStatus {
        val body = call("duplex_status", buildJsonObject {}).jsonObject
        return DuplexRemoteStatus(
            queued = body.getValue("queued").jsonPrimitive.int,
            acknowledgedThrough = body.getValue("acknowledgedThrough").jsonPrimitive.long,
            latestSequence = body.getValue("latestSequence").jsonPrimitive.long,
            peerStreamOnline = body.getValue("peerStreamOnline").jsonPrimitive.content == "true",
        )
    }

    /**
     * Real-time server-initiated MCP logging notifications. Reconnect and call [receive] to recover
     * anything emitted before subscription or across disconnections. Consumers acknowledge only
     * after their own downstream handler accepted an item.
     */
    fun notifications(): Flow<DuplexEnvelope> = flow {
        safeClient.prepareGet(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            header(HttpHeaders.Accept, "text/event-stream")
        }.execute { response ->
            check(response.status.isSuccess()) { "duplex SSE unavailable: ${response.status.value}" }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val frame = json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                if (frame["method"]?.jsonPrimitive?.contentOrNull != "notifications/message") continue
                val data = frame["params"]?.jsonObject?.get("data")?.jsonObject ?: continue
                emit(decodeEnvelope(data))
            }
        }
    }

    private suspend fun call(name: String, arguments: JsonObject): JsonElement {
        val id = sequenceMutex.withLock { nextRpcId++ }
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        }
        val response = safeClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.Application.Json)
            setBody(request.toString())
        }
        check(response.status.isSuccess()) { "duplex MCP HTTP failure: ${response.status.value}" }
        val rpc = json.parseToJsonElement(response.bodyAsText()).jsonObject
        check(rpc["id"]?.jsonPrimitive?.long == id) { "duplex MCP response id mismatch" }
        check(rpc["error"] == null) { "duplex MCP RPC failure" }
        val result = rpc.getValue("result").jsonObject
        val text = result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
        if (result["isError"]?.jsonPrimitive?.content == "true") {
            val error = json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(error ?: "duplex tool rejected request")
        }
        return json.parseToJsonElement(text)
    }

    /** Close this peer-specific client without closing the caller-owned base client. */
    fun close() = safeClient.close()

    private fun decodeEnvelope(data: JsonObject): DuplexEnvelope = DuplexEnvelope(
        seq = data.getValue("seq").jsonPrimitive.long,
        id = data.getValue("id").jsonPrimitive.content,
        from = when (data.getValue("from").jsonPrimitive.content) {
            "a" -> DuplexPeer.A
            "b" -> DuplexPeer.B
            else -> error("invalid duplex sender")
        },
        kind = when (data.getValue("kind").jsonPrimitive.content) {
            "message" -> DuplexKind.Message
            "event" -> DuplexKind.Event
            "request" -> DuplexKind.Request
            "response" -> DuplexKind.Response
            "cancel" -> DuplexKind.Cancel
            else -> error("invalid duplex kind")
        },
        body = data.getValue("body").jsonPrimitive.content,
        replyTo = data["replyTo"]?.jsonPrimitive?.contentOrNull,
        time = data.getValue("time").jsonPrimitive.long,
    )
}

data class DuplexRemoteStatus(
    val queued: Int,
    val acknowledgedThrough: Long,
    val latestSequence: Long,
    val peerStreamOnline: Boolean,
)
