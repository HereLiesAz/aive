package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
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
 * Answers one `elicitation/create` request (its `params`) with an `ElicitResult`. The client
 * declares elicitation only on calls that carry one. Never throws for the keeper's refusal.
 */
fun interface McpElicitor {
    suspend fun elicit(params: JsonObject): JsonObject
}

/**
 * An MCP client for one server: detects its era, lists its tools and calls them.
 *
 * On the modern revision every request declares the Tasks extension, and a call made with an
 * [McpElicitor] also declares elicitation (form and URL). Sampling and roots are never declared,
 * so a server cannot borrow the host's model or read its folders. A handshake-era server is
 * offered no capabilities at all. Not a security boundary on its own: [McpHost] owns consent.
 */
class McpClient(
    private val transport: McpTransport,
    private val clientInfo: McpImplementation = DEFAULT_CLIENT_INFO,
    private val requestTimeoutMillis: Long = 120_000,
    private val probeTimeoutMillis: Long = 15_000,
    /** The longest a task-augmented call is waited on, keeper input included. */
    private val taskTimeoutMillis: Long = 30 * 60_000L,
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

    /**
     * Calls [name]. A modern server may answer with an `InputRequiredResult` (multi round-trip
     * request): its elicitations go to [elicitor] and the call is retried with the answers and
     * the server's `requestState`, at most [MAX_INPUT_ROUNDS] times. It may also answer with a
     * task, which is polled to its end ([awaitTask]). Without an [elicitor], or for a request
     * type other than elicitation, the call ends with [McpInputRequiredException].
     */
    suspend fun callTool(name: String, arguments: JsonObject, elicitor: McpElicitor? = null): McpToolResult {
        val tool = tools[name]
        val extraHeaders = if (transport.kind == McpTransportKind.StreamableHttp && tool != null) {
            McpParamHeaders.headersFor(tool.inputSchema, arguments)
        } else {
            emptyMap()
        }
        val capabilities = clientCapabilities(elicitation = elicitor != null)
        var inputResponses: JsonObject? = null
        var requestState: String? = null
        var round = 0
        while (true) {
            val result = call(
                method = "tools/call",
                params = buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                    inputResponses?.let { put("inputResponses", it) }
                    requestState?.let { put("requestState", it) }
                },
                nameHeader = name,
                extraHeaders = extraHeaders,
                capabilities = capabilities,
                accepted = TOOL_CALL_RESULTS,
            )
            val modern = connect() is McpEra.Modern
            when (result.mcpString("resultType") ?: McpProtocol.RESULT_COMPLETE) {
                McpProtocol.RESULT_TASK -> {
                    if (!modern) throw McpProtocolException("The MCP server answered with a task it was not offered")
                    return decodeToolResult(awaitTask(result, elicitor, capabilities))
                }
                McpProtocol.RESULT_INPUT_REQUIRED -> {
                    if (!modern || round >= MAX_INPUT_ROUNDS) throw McpInputRequiredException(result)
                    round++
                    val requests = (result["inputRequests"] as? JsonObject)?.takeIf { it.isNotEmpty() }
                    val state = result.mcpString("requestState")
                    if (requests == null && state == null) throw McpProtocolException("The MCP server asked for input without saying what")
                    // Each retry carries only this round's answers and state, never a stale one.
                    inputResponses = requests?.let { answerInputs(it, elicitor) ?: throw McpInputRequiredException(result) }
                    requestState = state
                }
                else -> return decodeToolResult(result)
            }
        }
    }

    /**
     * Answers [requests] (an `InputRequests` map) through [elicitor], one at a time. Null when any
     * of them is something other than an elicitation, when there are too many, or when there is
     * no one to ask: the caller ends the call rather than answer part of it.
     */
    private suspend fun answerInputs(requests: JsonObject, elicitor: McpElicitor?): JsonObject? {
        if (elicitor == null || requests.size > MAX_INPUT_REQUESTS) return null
        val elicitations = requests.mapValues { (_, request) ->
            val asked = request as? JsonObject ?: return null
            if (asked.mcpString("method") != "elicitation/create") return null
            asked["params"] as? JsonObject ?: JsonObject(emptyMap())
        }
        return buildJsonObject { elicitations.forEach { (key, params) -> put(key, elicitor.elicit(params)) } }
    }

    /**
     * Follows a task (`CreateTaskResult`) to its end and returns its `CallToolResult`: `tasks/get`
     * at the server's `pollIntervalMs` or a backoff from [TASK_FIRST_POLL_MILLIS] to
     * [TASK_MAX_POLL_MILLIS], whichever is longer; input it asks for goes through [elicitor] and
     * `tasks/update`, each request key once. The whole wait is capped at [taskTimeoutMillis] and
     * [MAX_TASK_POLLS] polls. If the wait ends any other way than the task ending (the run is
     * cancelled, the cap is reached, input cannot be given), the task is cancelled with
     * `tasks/cancel`, best effort.
     */
    private suspend fun awaitTask(created: JsonObject, elicitor: McpElicitor?, capabilities: JsonObject): JsonObject {
        val taskId = created.mcpString("taskId")?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException("The MCP server started a task without an id")
        val progress = TaskProgress()
        try {
            return withTimeoutOrNull(taskTimeoutMillis) { pollTask(taskId, created, elicitor, capabilities, progress) }
                ?: throw McpTaskException("The MCP server's task did not finish within ${taskTimeoutMillis / 60_000} minutes")
        } finally {
            if (!progress.settled) {
                // Cooperative and best effort: the server may still finish the work.
                withContext(NonCancellable) {
                    runCatching {
                        withTimeoutOrNull(TASK_CANCEL_TIMEOUT_MILLIS) {
                            call("tasks/cancel", buildJsonObject { put("taskId", taskId) }, nameHeader = taskId, capabilities = capabilities)
                        }
                    }
                }
            }
        }
    }

    /** Set once the server has ended the task itself, so it is not cancelled on the way out. */
    private class TaskProgress {
        var settled = false
    }

    private suspend fun pollTask(
        taskId: String,
        created: JsonObject,
        elicitor: McpElicitor?,
        capabilities: JsonObject,
        progress: TaskProgress,
    ): JsonObject {
        var task = created
        var backoff = TASK_FIRST_POLL_MILLIS
        var polls = 0
        val answered = mutableSetOf<String>()
        while (true) {
            when (task.mcpString("status")) {
                "completed" -> {
                    progress.settled = true
                    return task["result"] as? JsonObject
                        ?: throw McpProtocolException("The MCP server finished a task without a result")
                }
                "failed" -> {
                    progress.settled = true
                    throw (task["error"] as? JsonObject)?.let { McpJsonRpc.errorOf(buildJsonObject { put("error", it) }) }
                        ?: McpTaskException("The MCP server's task failed: ${task.mcpString("statusMessage") ?: "no reason given"}")
                }
                "cancelled" -> {
                    progress.settled = true
                    throw McpTaskException("The MCP server cancelled the task")
                }
                "input_required" -> {
                    // A poll repeats requests not yet answered; each key is put to the keeper once.
                    val fresh = (task["inputRequests"] as? JsonObject)?.filterKeys { it !in answered }.orEmpty()
                    if (fresh.isNotEmpty()) {
                        val responses = answerInputs(JsonObject(fresh), elicitor) ?: throw McpInputRequiredException(task)
                        call(
                            "tasks/update",
                            buildJsonObject {
                                put("taskId", taskId)
                                put("inputResponses", responses)
                            },
                            nameHeader = taskId,
                            capabilities = capabilities,
                        )
                        answered += fresh.keys
                        backoff = TASK_FIRST_POLL_MILLIS
                    }
                }
                // "working", or a status this client does not know: keep polling.
                else -> Unit
            }
            if (++polls > MAX_TASK_POLLS) throw McpTaskException("The MCP server's task did not finish after $MAX_TASK_POLLS checks")
            val suggested = ((task["pollIntervalMs"] as? JsonPrimitive)?.longOrNull ?: 0L)
                .coerceIn(TASK_MIN_POLL_MILLIS, TASK_MAX_SUGGESTED_POLL_MILLIS)
            delay(maxOf(suggested, backoff))
            backoff = (backoff * 2).coerceAtMost(TASK_MAX_POLL_MILLIS)
            task = call("tasks/get", buildJsonObject { put("taskId", taskId) }, nameHeader = taskId, capabilities = capabilities)
            task.mcpString("taskId")?.let { if (it != taskId) throw McpProtocolException("The MCP server answered about another task") }
        }
    }

    private fun decodeToolResult(result: JsonObject): McpToolResult {
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
            transport.exchange(modernOutgoing(nextRequestId(), "server/discover", JsonObject(emptyMap()), null, emptyMap(), BASE_CAPABILITIES))
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
        capabilities: JsonObject = BASE_CAPABILITIES,
        accepted: Set<String> = COMPLETE_ONLY,
        retried: Boolean = false,
    ): JsonObject {
        val current = connect()
        val id = nextRequestId()
        val outgoing = when (current) {
            is McpEra.Modern -> modernOutgoing(id, method, params, nameHeader, extraHeaders, capabilities)
            is McpEra.Legacy -> McpOutgoing(McpJsonRpc.request(id, method, params), legacyHeaders(current))
        }
        val incoming = exchangeWithin(outgoing, method)
        // A handshake-era HTTP session can expire (404): start a new one once.
        if (current is McpEra.Legacy && current.sessionId != null && incoming.status == 404 && !retried) {
            eraMutex.withLock { if (era == current) era = null }
            return call(method, params, nameHeader, extraHeaders, capabilities, accepted, retried = true)
        }
        val message = incoming.message
            ?: throw McpProtocolException("The MCP server gave no answer to $method (HTTP ${incoming.status})")
        McpJsonRpc.errorOf(message)?.let { throw it }
        if (incoming.status != null && incoming.status !in 200..299) throw McpProtocolException("The MCP server failed $method (HTTP ${incoming.status})")
        val result = message["result"] as? JsonObject ?: throw McpProtocolException("The MCP server sent no result for $method")
        val type = result.mcpString("resultType") ?: McpProtocol.RESULT_COMPLETE
        return when {
            type in accepted -> result
            type == McpProtocol.RESULT_INPUT_REQUIRED -> throw McpInputRequiredException(result)
            else -> throw McpProtocolException("The MCP server sent an unexpected result type for $method")
        }
    }

    private fun modernOutgoing(
        id: Long,
        method: String,
        params: JsonObject,
        nameHeader: String?,
        extraHeaders: Map<String, String>,
        capabilities: JsonObject,
    ): McpOutgoing {
        val meta = buildJsonObject {
            (params["_meta"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.MODERN_VERSION)
            put(McpProtocol.META_CLIENT_INFO, clientInfo.toJson())
            put(McpProtocol.META_CLIENT_CAPABILITIES, capabilities)
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

        /** Retries of one call with keeper input before it is given up. */
        const val MAX_INPUT_ROUNDS = 4

        /** Input requests answered from one result. */
        const val MAX_INPUT_REQUESTS = 8
        const val MAX_TASK_POLLS = 1_000
        const val TASK_FIRST_POLL_MILLIS = 1_000L
        const val TASK_MAX_POLL_MILLIS = 15_000L
        private const val TASK_MIN_POLL_MILLIS = 500L
        private const val TASK_MAX_SUGGESTED_POLL_MILLIS = 60_000L
        private const val TASK_CANCEL_TIMEOUT_MILLIS = 5_000L

        private val COMPLETE_ONLY = setOf(McpProtocol.RESULT_COMPLETE)
        private val TOOL_CALL_RESULTS = setOf(McpProtocol.RESULT_COMPLETE, McpProtocol.RESULT_INPUT_REQUIRED, McpProtocol.RESULT_TASK)

        /**
         * The per-request `clientCapabilities`: the Tasks extension always; elicitation (form and
         * URL) only for a call that has a keeper to ask. Never sampling or roots.
         */
        fun clientCapabilities(elicitation: Boolean): JsonObject = buildJsonObject {
            if (elicitation) {
                put("elicitation", buildJsonObject {
                    put("form", JsonObject(emptyMap()))
                    put("url", JsonObject(emptyMap()))
                })
            }
            put("extensions", buildJsonObject { put(McpProtocol.EXTENSION_TASKS, JsonObject(emptyMap())) })
        }

        private val BASE_CAPABILITIES = clientCapabilities(elicitation = false)
    }
}

private fun McpImplementation.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("version", version)
}

private fun JsonElement.mcpStringValue(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
