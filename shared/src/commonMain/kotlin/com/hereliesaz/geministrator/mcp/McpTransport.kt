package com.hereliesaz.geministrator.mcp

import com.hereliesaz.geministrator.net.CleartextPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import com.hereliesaz.geministrator.net.BoundedLineReader
import com.hereliesaz.geministrator.net.ResponseTooLargeException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull

enum class McpTransportKind { StreamableHttp, Stdio }

/** One JSON-RPC message to send, and the HTTP headers that mirror it (ignored on stdio). */
data class McpOutgoing(
    val message: JsonObject,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * What came back: the response to a request ([message] null for a notification, or an HTTP reply
 * with no JSON body), the HTTP status (null on stdio) and the response headers the client reads.
 */
data class McpIncoming(
    val status: Int?,
    val message: JsonObject?,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A message pipe to one MCP server. Stateless: protocol era, sessions and headers belong to
 * [McpClient]. A request returns its own response; server notifications in between are dropped.
 */
interface McpTransport {
    val kind: McpTransportKind

    suspend fun exchange(outgoing: McpOutgoing): McpIncoming

    suspend fun close()
}

/** Starts a local MCP server process (desktop only): fixed argv, never a shell. */
fun interface McpStdioLauncher {
    fun launch(command: List<String>): McpTransport
}

/**
 * Streamable HTTP: every message is its own POST; a request is answered with one JSON object or
 * an SSE stream scoped to it, whose final response is taken. The endpoint must be HTTPS, or HTTP
 * to this device or a private network (`CleartextPolicy`); it may not embed credentials, and
 * redirects are never followed, so a bearer token goes only where the keeper pointed it.
 */
class McpStreamableHttpTransport(
    client: HttpClient,
    val endpoint: String,
    private val bearerToken: String? = null,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) : McpTransport {
    override val kind: McpTransportKind = McpTransportKind.StreamableHttp
    private val client: HttpClient

    init {
        requireUsableEndpoint(endpoint)
        this.client = client.config { followRedirects = false }
    }

    override suspend fun exchange(outgoing: McpOutgoing): McpIncoming {
        val id = McpJsonRpc.idOf(outgoing.message)?.longOrNull
        return client.preparePost(endpoint) {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            bearerToken?.takeIf(String::isNotBlank)?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            outgoing.headers.forEach { (name, value) -> header(name, value) }
            setBody(TextContent(outgoing.message.toString(), ContentType.Application.Json))
        }.execute { response -> read(response, id) }
    }

    private suspend fun read(response: HttpResponse, id: Long?): McpIncoming {
        val status = response.status.value
        val headers = buildMap {
            response.headers[McpProtocol.HEADER_SESSION_ID]?.let { put(McpProtocol.HEADER_SESSION_ID, it) }
        }
        if (id == null || status == 202) return McpIncoming(status, null, headers)
        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim().lowercase()
        response.contentLength()?.let { if (it > maxResponseBytes) throw McpProtocolException("The MCP server's response is too large") }
        val lines = BoundedLineReader(response.bodyAsChannel(), maxResponseBytes.toLong())
        suspend fun nextLine(): String? = try {
            lines.readLine()
        } catch (_: ResponseTooLargeException) {
            throw McpProtocolException("The MCP server's response is too large")
        }
        if (type == "text/event-stream" && status in 200..299) {
            // Notifications about the request may precede its response; take the response.
            val data = StringBuilder()
            while (true) {
                val line = nextLine()
                if (line == null || line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        val message = McpJsonRpc.parse(data.toString())
                        data.clear()
                        if (message != null && McpJsonRpc.isResponseTo(message, id)) return McpIncoming(status, message, headers)
                    }
                    if (line == null) break
                    continue
                }
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
            throw McpProtocolException("The MCP server closed its stream without answering")
        }
        // A JSON body; JSON never needs a raw newline inside a string, so lines rejoin losslessly.
        val body = StringBuilder()
        while (true) {
            val line = nextLine() ?: break
            if (body.isNotEmpty()) body.append('\n')
            body.append(line)
        }
        return McpIncoming(status, McpJsonRpc.parse(body.toString()), headers)
    }

    /** Leaves the caller-owned base client open. */
    override suspend fun close() = client.close()

    companion object {
        /** The most bytes of one response read, counted on the wire while reading (not characters after the fact). */
        const val MAX_RESPONSE_BYTES = 4_000_000

        /** Throws [IllegalArgumentException] for an endpoint the host refuses to talk to. */
        fun requireUsableEndpoint(endpoint: String) {
            val url = runCatching { Url(endpoint.trim()) }.getOrNull()
            require(url != null && (url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP)) {
                "An MCP server address must start with https://"
            }
            require(CleartextPolicy.allowsSecret(endpoint.trim())) {
                "An MCP server address must use https://, unless it is on this device or a private network"
            }
            require(url.user == null && url.password == null && !endpoint.substringBefore('?').substringAfter("://").substringBefore('/').contains('@')) {
                "An MCP server address must not contain a user name or password"
            }
        }
    }
}
