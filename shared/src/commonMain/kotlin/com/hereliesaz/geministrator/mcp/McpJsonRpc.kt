package com.hereliesaz.geministrator.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * The protocol constants The Aive's MCP host speaks (docs/architecture/MCP_HOST.md). The host is
 * dual-era: it prefers the stateless [MODERN_VERSION], which carries version, identity and
 * capabilities in every request's `_meta`, and falls back to the `initialize` handshake of the
 * [LEGACY_VERSIONS] when a server does not speak it.
 */
object McpProtocol {
    const val MODERN_VERSION = "2026-07-28"

    /** Handshake-era revisions, newest first; the first is offered in `initialize`. */
    val LEGACY_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    const val HEADER_METHOD = "Mcp-Method"
    const val HEADER_NAME = "Mcp-Name"
    const val HEADER_SESSION_ID = "Mcp-Session-Id"
    const val HEADER_PARAM_PREFIX = "Mcp-Param-"

    /** The Tasks extension (SEP-2663): a `tools/call` may come back as a task to poll. */
    const val EXTENSION_TASKS = "io.modelcontextprotocol/tasks"

    const val RESULT_COMPLETE = "complete"
    const val RESULT_INPUT_REQUIRED = "input_required"
    const val RESULT_TASK = "task"

    const val HEADER_MISMATCH = -32020
    const val MISSING_REQUIRED_CLIENT_CAPABILITY = -32021
    const val UNSUPPORTED_PROTOCOL_VERSION = -32022

    /** Errors only a modern server sends: they identify its era, so no `initialize` fallback follows. */
    val MODERN_ERROR_CODES: Set<Int> = setOf(HEADER_MISMATCH, MISSING_REQUIRED_CLIENT_CAPABILITY, UNSUPPORTED_PROTOCOL_VERSION)
}

/** A client or server's self-reported name and version. Display only; never a security input. */
data class McpImplementation(val name: String, val version: String)

/** A JSON-RPC error the server returned. */
class McpRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/** The server broke the protocol, refused the connection, or could not be reached. */
open class McpProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The server asked for input The Aive cannot give: a sampling call or roots (never declared), an
 * elicitation on a call made without a keeper to ask, or one request too many. [result] is the
 * server's `InputRequiredResult` (or the task carrying the requests).
 */
class McpInputRequiredException(val result: JsonObject) :
    McpProtocolException("The MCP server asked for input The Aive cannot give")

/** A task-augmented call ended without a result: failed, cancelled, or not done in time. */
class McpTaskException(message: String) : McpProtocolException(message)

/** JSON-RPC 2.0 framing for MCP messages. */
object McpJsonRpc {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun request(id: Long, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    fun notification(method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }

    /** One message, or null when [text] is not a JSON object. */
    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** The id of a request or response; null for a notification. */
    fun idOf(message: JsonObject): JsonPrimitive? = message["id"] as? JsonPrimitive

    fun isRequest(message: JsonObject): Boolean = idOf(message) != null && message["method"] != null

    /** True when [message] is the response (result or error) to the request numbered [id]. */
    fun isResponseTo(message: JsonObject, id: Long): Boolean {
        val messageId = idOf(message) ?: return false
        if (message["method"] != null) return false
        if (message["result"] == null && message["error"] == null) return false
        return messageId.longOrNull == id || messageId.contentOrNull == id.toString()
    }

    /** The error carried by [message], if it is an error response. */
    fun errorOf(message: JsonObject): McpRpcException? {
        val error = message["error"] as? JsonObject ?: return null
        val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: return McpRpcException(0, "malformed JSON-RPC error")
        val text = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"
        return McpRpcException(code, text, error["data"])
    }

    /**
     * An HTTP header value: as-is when it is visible ASCII with no surrounding whitespace, else the
     * Base64 sentinel `=?base64?…?=` of its UTF-8 bytes (and always for a value that looks like one).
     */
    fun encodeHeaderValue(value: String): String {
        val plain = value.isNotEmpty() &&
            value.all { it == ' ' || it == '\t' || it.code in 0x21..0x7E } &&
            value.first() != ' ' && value.first() != '\t' &&
            value.last() != ' ' && value.last() != '\t' &&
            !(value.startsWith("=?base64?") && value.endsWith("?="))
        return if (plain) value else "=?base64?" + Base64.encode(value.encodeToByteArray()) + "?="
    }
}

/** The string value of a primitive field, or null. */
internal fun JsonObject.mcpString(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.mcpIsTrue(): Boolean = (this as? JsonPrimitive)?.contentOrNull == "true"
