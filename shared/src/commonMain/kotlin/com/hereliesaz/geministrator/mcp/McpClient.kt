package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a server is spoken to, decided once per connection (docs/architecture/MCP_HOST.md). */
sealed interface McpEra {
    val version: String

    /** Stateless: every request carries its version, identity and capabilities in `_meta`. */
    data class Modern(override val version: String) : McpEra

    /** Handshake era: `initialize` first; on HTTP, the server's session id on every request. */
    data class Legacy(override val version: String, val sessionId: String?) : McpEra
}

/** A tool a server offers, as `tools/list` described it. Descriptions and annotations are untrusted. */
data class McpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
    val annotations: JsonObject?,
)

/** One content item of a tool result. Only text is passed on verbatim. */
data class McpContent(
    val type: String,
    val text: String? = null,
    val mimeType: String? = null,
    val uri: String? = null,
    val name: String? = null,
)

data class McpToolResult(
    val content: List<McpContent>,
    val structuredContent: JsonElement?,
    val isError: Boolean,
) {
    /** The result as text for a model: text items as they are, other media named, not inlined. */
    fun renderText(maxChars: Int = 20_000): String {
        val parts = content.mapNotNull { item ->
            when (item.type) {
                "text" -> item.text
                "resource" -> item.text ?: "[embedded resource ${item.uri.orEmpty()} ${item.mimeType.orEmpty()}]".trim()
                "resource_link" -> "[resource link: ${item.name ?: item.uri.orEmpty()} ${item.uri.orEmpty()}]"
                "image", "audio" -> "[${item.type} ${item.mimeType.orEmpty()} omitted]"
                else -> "[${item.type} content omitted]"
            }
        }.ifEmpty { listOfNotNull(structuredContent?.toString()) }
        val text = parts.joinToString("\n").ifBlank { if (isError) "The tool failed without saying why." else "(no content)" }
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n[truncated at $maxChars characters]"
    }
}

/**
 * An MCP client for one server: detects its era, lists its tools and calls them. It declares no
 * client capabilities (no sampling, elicitation or roots), so a server cannot reach back into the
 * host. Not a security boundary on its own: [McpHost] owns consent.
 */
class McpClient(
    private val transport: McpTransport,
    private val clientInfo: McpImplementation = DEFAULT_CLIENT_INFO,
    private val requestTimeoutMillis: Long = 120_000,
    private val probeTimeoutMillis: Long = 15_000,
) {
    private val eraMutex = Mutex()
    private val idMutex = Mutex()
    private var nextId = 1L
    private var era: McpEra? = null
    private var tools: Map<String, McpTool> = emptyMap()

    /** The server's self-description, once connected. Display only. */
    var serverInfo: McpImplementation? = null
        private set

    val transportKind: McpTransportKind get() = transport.kind

    suspend fun connect(): McpEra = eraMutex.withLock { era ?: detectEra().also { era = it } }

    /** Every tool the server lists (following pagination), minus definitions the transport must reject. */
    suspend fun listTools(): List<McpTool> {
        val listed = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val result = call("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (result["tools"] as? JsonArray).orEmpty().forEach { element ->
                val tool = (element as? JsonObject)?.let(::decodeTool) ?: return@forEach
                if (transport.kind == McpTransportKind.StreamableHttp && McpParamHeaders.problem(tool.inputSchema) != null) return@forEach
                listed += tool
            }
            cursor = result.mcpString("nextCursor")?.takeIf(String::isNotEmpty) ?: return finish(listed)
        }
        return finish(listed)
    }

    private fun finish(listed: List<McpTool>): List<McpTool> {
        tools = listed.associateBy(McpTool::name)
        return listed
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val tool = tools[name]
        val extraHeaders = if (transport.kind == McpTransportKind.StreamableHttp && tool != null) {
            McpParamHeaders.headersFor(tool.inputSchema, arguments)
        } else {
            emptyMap()
        }
        val result = call(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            nameHeader = name,
            extraHeaders = extraHeaders,
        )
        val content = (result["content"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val resource = item["resource"] as? JsonObject
            McpContent(
                type = item.mcpString("type") ?: "unknown",
                text = item.mcpString("text") ?: resource?.mcpString("text"),
                mimeType = item.mcpString("mimeType") ?: resource?.mcpString("mimeType"),
                uri = item.mcpString("uri") ?: resource?.mcpString("uri"),
                name = item.mcpString("name"),
            )
        }
        return McpToolResult(content, result["structuredContent"], result["isError"].mcpIsTrue())
    }

    suspend fun close() = transport.close()

    private suspend fun detectEra(): McpEra {
        // Silence within the probe window marks a handshake-era server (stdio legacy servers may
        // not answer requests sent before initialize).
        val probe = withTimeoutOrNull(probeTimeoutMillis) {
            transport.exchange(modernOutgoing(nextRequestId(), "server/discover", JsonObject(emptyMap()), null, emptyMap()))
        } ?: return initialize(McpProtocol.LEGACY_VERSIONS.first())
        if (probe.status == 401 || probe.status == 403) {
            throw McpProtocolException("The MCP server refused the credentials (HTTP ${probe.status})")
        }
        val message = probe.message
        val error = message?.let(McpJsonRpc::errorOf)
        if (message != null && error == null && (probe.status == null || probe.status in 200..299)) {
            val result = message["result"] as? JsonObject ?: throw McpProtocolException("The MCP server sent a malformed discovery result")
            readServerInfo(result)
            val supported = (result["supportedVersions"] as? JsonArray)?.mapNotNull { it.mcpStringValue() }
            if (supported == null || McpProtocol.MODERN_VERSION in supported) return McpEra.Modern(McpProtocol.MODERN_VERSION)
            val legacy = McpProtocol.LEGACY_VERSIONS.firstOrNull { it in supported }
                ?: throw McpProtocolException("The MCP server speaks only ${supported.joinToString()}")
            return initialize(legacy)
        }
        if (error != null && error.code in McpProtocol.MODERN_ERROR_CODES) {
            if (error.code != McpProtocol.UNSUPPORTED_PROTOCOL_VERSION) throw McpProtocolException("The MCP server refused: ${error.message}")
            val supported = ((error.data as? JsonObject)?.get("supported") as? JsonArray)?.mapNotNull { it.mcpStringValue() }.orEmpty()
            val legacy = McpProtocol.LEGACY_VERSIONS.firstOrNull { it in supported }
                ?: throw McpProtocolException("The MCP server speaks only ${supported.joinToString().ifEmpty { "versions it did not name" }}")
            return initialize(legacy)
        }
        // Any other answer (a non-modern error, an empty 4xx, silence) marks a handshake-era server.
        return initialize(McpProtocol.LEGACY_VERSIONS.first())
    }

    private suspend fun initialize(offered: String): McpEra {
        val id = nextRequestId()
        val params = buildJsonObject {
            put("protocolVersion", offered)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", clientInfo.toJson())
        }
        val incoming = exchangeWithin(McpOutgoing(McpJsonRpc.request(id, "initialize", params)), "initialize")
        val message = incoming.message ?: throw McpProtocolException("The MCP server did not answer initialize (HTTP ${incoming.status})")
        McpJsonRpc.errorOf(message)?.let { throw McpProtocolException("The MCP server refused to start: ${it.message}", it) }
        if (incoming.status != null && incoming.status !in 200..299) throw McpProtocolException("The MCP server refused to start (HTTP ${incoming.status})")
        val result = message["result"] as? JsonObject ?: throw McpProtocolException("The MCP server sent a malformed initialize result")
        val version = result.mcpString("protocolVersion")
        if (version == null || version !in McpProtocol.LEGACY_VERSIONS) {
            throw McpProtocolException("The MCP server answered with protocol ${version ?: "nothing"}, which The Aive does not speak")
        }
        readServerInfo(result)
        val legacy = McpEra.Legacy(version, incoming.headers[McpProtocol.HEADER_SESSION_ID])
        transport.exchange(McpOutgoing(McpJsonRpc.notification("notifications/initialized"), legacyHeaders(legacy)))
        return legacy
    }

    private suspend fun call(
        method: String,
        params: JsonObject,
        nameHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retried: Boolean = false,
    ): JsonObject {
        val current = connect()
        val id = nextRequestId()
        val outgoing = when (current) {
            is McpEra.Modern -> modernOutgoing(id, method, params, nameHeader, extraHeaders)
            is McpEra.Legacy -> McpOutgoing(McpJsonRpc.request(id, method, params), legacyHeaders(current))
        }
        val incoming = exchangeWithin(outgoing, method)
        // A handshake-era HTTP session can expire (404): start a new one once.
        if (current is McpEra.Legacy && current.sessionId != null && incoming.status == 404 && !retried) {
            eraMutex.withLock { if (era == current) era = null }
            return call(method, params, nameHeader, extraHeaders, retried = true)
        }
        val message = incoming.message
            ?: throw McpProtocolException("The MCP server gave no answer to $method (HTTP ${incoming.status})")
        McpJsonRpc.errorOf(message)?.let { throw it }
        if (incoming.status != null && incoming.status !in 200..299) throw McpProtocolException("The MCP server failed $method (HTTP ${incoming.status})")
        val result = message["result"] as? JsonObject ?: throw McpProtocolException("The MCP server sent no result for $method")
        return when (result.mcpString("resultType") ?: "complete") {
            "complete" -> result
            "input_required" -> throw McpInputRequiredException(result)
            else -> throw McpProtocolException("The MCP server sent an unknown result type for $method")
        }
    }

    private fun modernOutgoing(
        id: Long,
        method: String,
        params: JsonObject,
        nameHeader: String?,
        extraHeaders: Map<String, String>,
    ): McpOutgoing {
        val meta = buildJsonObject {
            (params["_meta"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.MODERN_VERSION)
            put(McpProtocol.META_CLIENT_INFO, clientInfo.toJson())
            put(McpProtocol.META_CLIENT_CAPABILITIES, JsonObject(emptyMap()))
        }
        val body = buildJsonObject {
            params.forEach { (key, value) -> if (key != "_meta") put(key, value) }
            put("_meta", meta)
        }
        val headers = buildMap {
            put(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.MODERN_VERSION)
            put(McpProtocol.HEADER_METHOD, method)
            nameHeader?.let { put(McpProtocol.HEADER_NAME, McpJsonRpc.encodeHeaderValue(it)) }
            putAll(extraHeaders)
        }
        return McpOutgoing(McpJsonRpc.request(id, method, body), headers)
    }

    private fun legacyHeaders(era: McpEra.Legacy): Map<String, String> = buildMap {
        // The version header exists from 2025-06-18 on.
        if (era.version >= "2025-06-18") put(McpProtocol.HEADER_PROTOCOL_VERSION, era.version)
        era.sessionId?.let { put(McpProtocol.HEADER_SESSION_ID, it) }
    }

    private fun readServerInfo(result: JsonObject) {
        val info = ((result["_meta"] as? JsonObject)?.get(McpProtocol.META_SERVER_INFO) as? JsonObject)
            ?: result["serverInfo"] as? JsonObject
            ?: return
        serverInfo = McpImplementation(info.mcpString("name") ?: return, info.mcpString("version").orEmpty())
    }

    /** A timeout is the server's failure, not a cancellation of the caller. */
    private suspend fun exchangeWithin(outgoing: McpOutgoing, method: String): McpIncoming =
        withTimeoutOrNull(requestTimeoutMillis) { transport.exchange(outgoing) }
            ?: throw McpProtocolException("The MCP server did not answer $method within ${requestTimeoutMillis / 1000} seconds")

    private suspend fun nextRequestId(): Long = idMutex.withLock { nextId++ }

    private fun decodeTool(element: JsonObject): McpTool? {
        val name = element.mcpString("name")?.takeIf(String::isNotBlank) ?: return null
        return McpTool(
            name = name,
            title = element.mcpString("title"),
            description = element.mcpString("description"),
            inputSchema = element["inputSchema"] as? JsonObject ?: JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object"))),
            annotations = element["annotations"] as? JsonObject,
        )
    }

    companion object {
        val DEFAULT_CLIENT_INFO = McpImplementation("The Aive", "1")
        private const val MAX_LIST_PAGES = 20
    }
}

private fun McpImplementation.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("version", version)
}

private fun JsonElement.mcpStringValue(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
