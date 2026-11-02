package com.hereliesaz.geministrator.a2a

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Google's Agent2Agent protocol (a2a-protocol.org), JSON-RPC binding. The Aive writes the current
 * specification (1.0: `SendMessage`, ProtoJSON enums, no `kind` discriminators) and reads both 1.0
 * and the widely deployed 0.3 (`message/send`, lowercase states, `kind` members). See docs/A2A.md.
 */
enum class A2aVersion(val header: String) {
    V0_3("0.3"),
    V1_0("1.0"),
    ;

    companion object {
        /** The version an Agent Card interface's `protocolVersion` names; anything not 0.x is 1.0. */
        fun of(protocolVersion: String?): A2aVersion =
            if (protocolVersion?.trim()?.startsWith("0.") == true) V0_3 else V1_0
    }
}

enum class A2aTaskState(private val legacyWire: String, private val currentWire: String) {
    Submitted("submitted", "TASK_STATE_SUBMITTED"),
    Working("working", "TASK_STATE_WORKING"),
    InputRequired("input-required", "TASK_STATE_INPUT_REQUIRED"),
    AuthRequired("auth-required", "TASK_STATE_AUTH_REQUIRED"),
    Completed("completed", "TASK_STATE_COMPLETED"),
    Canceled("canceled", "TASK_STATE_CANCELED"),
    Failed("failed", "TASK_STATE_FAILED"),
    Rejected("rejected", "TASK_STATE_REJECTED"),
    Unknown("unknown", "TASK_STATE_UNSPECIFIED"),
    ;

    /** No further change will come: the task is over. */
    val isTerminal: Boolean get() = this == Completed || this == Canceled || this == Failed || this == Rejected

    /** The remote agent waits for the client (more input, or credentials). */
    val isInterrupted: Boolean get() = this == InputRequired || this == AuthRequired

    fun wire(version: A2aVersion): String = when (version) {
        A2aVersion.V0_3 -> legacyWire
        A2aVersion.V1_0 -> currentWire
    }

    companion object {
        /** Reads either spelling; an unrecognized state is [Unknown], never a guess at success. */
        fun parse(value: String?): A2aTaskState {
            val clean = value?.trim().orEmpty()
            return entries.firstOrNull { it.currentWire.equals(clean, ignoreCase = true) || it.legacyWire.equals(clean, ignoreCase = true) }
                ?: if (clean.equals("cancelled", ignoreCase = true)) Canceled else Unknown
        }
    }
}

enum class A2aRole(private val legacyWire: String, private val currentWire: String) {
    User("user", "ROLE_USER"),
    Agent("agent", "ROLE_AGENT"),
    ;

    fun wire(version: A2aVersion): String = when (version) {
        A2aVersion.V0_3 -> legacyWire
        A2aVersion.V1_0 -> currentWire
    }

    companion object {
        fun parse(value: String?): A2aRole =
            if (value.equals("user", ignoreCase = true) || value.equals("ROLE_USER", ignoreCase = true)) User else Agent
    }
}

sealed interface A2aPart {
    data class Text(val text: String, val mediaType: String? = null) : A2aPart

    /** A file by [url] or inline [bytesBase64]; exactly one is set. */
    data class File(
        val url: String? = null,
        val bytesBase64: String? = null,
        val mediaType: String? = null,
        val filename: String? = null,
    ) : A2aPart

    data class Data(val data: JsonElement) : A2aPart
}

data class A2aMessage(
    val messageId: String,
    val role: A2aRole,
    val parts: List<A2aPart>,
    val taskId: String? = null,
    val contextId: String? = null,
    val metadata: JsonObject? = null,
) {
    /** Every text part, joined; what The Aive treats as the message's words. */
    val text: String get() = parts.filterIsInstance<A2aPart.Text>().joinToString("\n\n") { it.text }.trim()
}

data class A2aArtifact(
    val artifactId: String,
    val parts: List<A2aPart>,
    val name: String? = null,
    val description: String? = null,
)

data class A2aTaskStatus(
    val state: A2aTaskState,
    val message: A2aMessage? = null,
    /** ISO 8601. */
    val timestamp: String? = null,
)

data class A2aTask(
    val id: String,
    val contextId: String,
    val status: A2aTaskStatus,
    val artifacts: List<A2aArtifact> = emptyList(),
    val history: List<A2aMessage> = emptyList(),
)

/** What `SendMessage` returns: a task to follow, or a direct reply that ends the exchange. */
sealed interface A2aSendResult {
    data class TaskResult(val task: A2aTask) : A2aSendResult
    data class MessageResult(val message: A2aMessage) : A2aSendResult
}

data class A2aSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String> = emptyList(),
)

data class A2aAgentInterface(
    val url: String,
    val protocolBinding: String = BINDING_JSONRPC,
    val protocolVersion: String = "1.0",
) {
    companion object {
        const val BINDING_JSONRPC = "JSONRPC"
    }
}

data class A2aAgentCard(
    val name: String,
    val description: String,
    val version: String,
    val interfaces: List<A2aAgentInterface>,
    val skills: List<A2aSkill>,
    val defaultInputModes: List<String> = listOf("text/plain"),
    val defaultOutputModes: List<String> = listOf("text/plain"),
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
    /** The JSON-RPC endpoint wants `Authorization: Bearer <token>`. */
    val bearerAuth: Boolean = false,
    val providerOrganization: String? = null,
    val providerUrl: String? = null,
    val documentationUrl: String? = null,
) {
    /** The first JSON-RPC interface (the card lists the preferred one first) and its version. */
    fun jsonRpcEndpoint(): Pair<String, A2aVersion>? = interfaces
        .firstOrNull { it.protocolBinding.equals(A2aAgentInterface.BINDING_JSONRPC, ignoreCase = true) && it.url.isNotBlank() }
        ?.let { it.url to A2aVersion.of(it.protocolVersion) }
}

/** A JSON-RPC error, as a server sends it and as a client reads it. */
class A2aRpcException(val code: Int, override val message: String) : RuntimeException(message)

object A2aErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val TASK_NOT_FOUND = -32001
    const val TASK_NOT_CANCELABLE = -32002
    const val PUSH_NOTIFICATION_NOT_SUPPORTED = -32003
    const val UNSUPPORTED_OPERATION = -32004
    const val CONTENT_TYPE_NOT_SUPPORTED = -32005
    const val INVALID_AGENT_RESPONSE = -32006
    const val VERSION_NOT_SUPPORTED = -32009
}

enum class A2aOperation {
    SendMessage,
    SendStreamingMessage,
    GetTask,
    ListTasks,
    CancelTask,
    SubscribeToTask,
    PushNotificationConfig,
    GetExtendedAgentCard,
}

/** JSON-RPC method names: 1.0 (`SendMessage`) and 0.3 (`message/send`). */
object A2aMethods {
    private val current = mapOf(
        "SendMessage" to A2aOperation.SendMessage,
        "SendStreamingMessage" to A2aOperation.SendStreamingMessage,
        "GetTask" to A2aOperation.GetTask,
        "ListTasks" to A2aOperation.ListTasks,
        "CancelTask" to A2aOperation.CancelTask,
        "SubscribeToTask" to A2aOperation.SubscribeToTask,
        "CreateTaskPushNotificationConfig" to A2aOperation.PushNotificationConfig,
        "GetTaskPushNotificationConfig" to A2aOperation.PushNotificationConfig,
        "ListTaskPushNotificationConfigs" to A2aOperation.PushNotificationConfig,
        "DeleteTaskPushNotificationConfig" to A2aOperation.PushNotificationConfig,
        "GetExtendedAgentCard" to A2aOperation.GetExtendedAgentCard,
    )
    private val legacy = mapOf(
        "message/send" to A2aOperation.SendMessage,
        "message/stream" to A2aOperation.SendStreamingMessage,
        "tasks/get" to A2aOperation.GetTask,
        "tasks/cancel" to A2aOperation.CancelTask,
        "tasks/resubscribe" to A2aOperation.SubscribeToTask,
        "tasks/pushNotificationConfig/set" to A2aOperation.PushNotificationConfig,
        "tasks/pushNotificationConfig/get" to A2aOperation.PushNotificationConfig,
        "tasks/pushNotificationConfig/list" to A2aOperation.PushNotificationConfig,
        "tasks/pushNotificationConfig/delete" to A2aOperation.PushNotificationConfig,
        "agent/getAuthenticatedExtendedCard" to A2aOperation.GetExtendedAgentCard,
    )

    fun resolve(method: String): Pair<A2aOperation, A2aVersion>? =
        current[method]?.let { it to A2aVersion.V1_0 } ?: legacy[method]?.let { it to A2aVersion.V0_3 }

    fun name(operation: A2aOperation, version: A2aVersion): String {
        val table = if (version == A2aVersion.V1_0) current else legacy
        return table.entries.first { it.value == operation }.key
    }
}

/**
 * JSON for the A2A objects. Writers take the version to speak; readers accept either version's
 * shape, so one client talks to 0.3 and 1.0 agents alike.
 */
object A2aCodec {
    // ---- Parts -----------------------------------------------------------------------------

    fun encodePart(part: A2aPart, version: A2aVersion): JsonObject = when (version) {
        A2aVersion.V1_0 -> buildJsonObject {
            when (part) {
                is A2aPart.Text -> {
                    put("text", part.text)
                    part.mediaType?.let { put("mediaType", it) }
                }
                is A2aPart.File -> {
                    if (part.url != null) put("url", part.url) else put("raw", part.bytesBase64.orEmpty())
                    part.mediaType?.let { put("mediaType", it) }
                    part.filename?.let { put("filename", it) }
                }
                is A2aPart.Data -> put("data", part.data)
            }
        }
        A2aVersion.V0_3 -> buildJsonObject {
            when (part) {
                is A2aPart.Text -> {
                    put("kind", "text")
                    put("text", part.text)
                }
                is A2aPart.File -> {
                    put("kind", "file")
                    put(
                        "file",
                        buildJsonObject {
                            if (part.url != null) put("uri", part.url) else put("bytes", part.bytesBase64.orEmpty())
                            part.mediaType?.let { put("mimeType", it) }
                            part.filename?.let { put("name", it) }
                        },
                    )
                }
                is A2aPart.Data -> {
                    put("kind", "data")
                    put("data", part.data)
                }
            }
        }
    }

    /** Null for a part this reader does not understand; callers skip it. */
    fun decodePart(element: JsonElement): A2aPart? {
        val obj = element as? JsonObject ?: return null
        val kind = obj.string("kind")
        return when {
            kind == "text" || (kind == null && obj["text"] is JsonPrimitive) ->
                obj.string("text")?.let { A2aPart.Text(it, obj.string("mediaType") ?: obj.string("mimeType")) }
            kind == "file" -> {
                val file = obj["file"] as? JsonObject ?: return null
                val uri = file.string("uri")
                val bytes = file.string("bytes")
                if (uri == null && bytes == null) null else A2aPart.File(uri, bytes.takeIf { uri == null }, file.string("mimeType"), file.string("name"))
            }
            kind == null && (obj["url"] is JsonPrimitive || obj["raw"] is JsonPrimitive) -> {
                val url = obj.string("url")
                A2aPart.File(url, obj.string("raw").takeIf { url == null }, obj.string("mediaType"), obj.string("filename"))
            }
            kind == "data" || (kind == null && obj.containsKey("data")) -> obj["data"]?.let { A2aPart.Data(it) }
            else -> null
        }
    }

    // ---- Messages, artifacts, tasks --------------------------------------------------------

    fun encodeMessage(message: A2aMessage, version: A2aVersion): JsonObject = buildJsonObject {
        if (version == A2aVersion.V0_3) put("kind", "message")
        put("messageId", message.messageId)
        put("role", message.role.wire(version))
        put("parts", JsonArray(message.parts.map { encodePart(it, version) }))
        message.taskId?.let { put("taskId", it) }
        message.contextId?.let { put("contextId", it) }
        message.metadata?.let { put("metadata", it) }
    }

    fun decodeMessage(element: JsonElement?): A2aMessage? {
        val obj = element as? JsonObject ?: return null
        val parts = (obj["parts"] as? JsonArray)?.mapNotNull(::decodePart) ?: return null
        return A2aMessage(
            messageId = obj.string("messageId").orEmpty(),
            role = A2aRole.parse(obj.string("role")),
            parts = parts,
            taskId = obj.string("taskId"),
            contextId = obj.string("contextId"),
            metadata = obj["metadata"] as? JsonObject,
        )
    }

    fun encodeArtifact(artifact: A2aArtifact, version: A2aVersion): JsonObject = buildJsonObject {
        put("artifactId", artifact.artifactId)
        artifact.name?.let { put("name", it) }
        artifact.description?.let { put("description", it) }
        put("parts", JsonArray(artifact.parts.map { encodePart(it, version) }))
    }

    fun decodeArtifact(element: JsonElement): A2aArtifact? {
        val obj = element as? JsonObject ?: return null
        return A2aArtifact(
            artifactId = obj.string("artifactId").orEmpty(),
            parts = (obj["parts"] as? JsonArray)?.mapNotNull(::decodePart).orEmpty(),
            name = obj.string("name"),
            description = obj.string("description"),
        )
    }

    fun encodeTask(task: A2aTask, version: A2aVersion): JsonObject = buildJsonObject {
        if (version == A2aVersion.V0_3) put("kind", "task")
        put("id", task.id)
        put("contextId", task.contextId)
        put(
            "status",
            buildJsonObject {
                put("state", task.status.state.wire(version))
                task.status.message?.let { put("message", encodeMessage(it, version)) }
                task.status.timestamp?.let { put("timestamp", it) }
            },
        )
        if (task.artifacts.isNotEmpty()) put("artifacts", JsonArray(task.artifacts.map { encodeArtifact(it, version) }))
        if (task.history.isNotEmpty()) put("history", JsonArray(task.history.map { encodeMessage(it, version) }))
    }

    fun decodeTask(element: JsonElement?): A2aTask? {
        val raw = element as? JsonObject ?: return null
        // 1.0 wraps the task as {"task": {...}} in SendMessage results; accept that here too.
        val obj = raw["task"] as? JsonObject ?: raw
        val id = obj.string("id") ?: return null
        val status = obj["status"] as? JsonObject
        return A2aTask(
            id = id,
            contextId = obj.string("contextId").orEmpty(),
            status = A2aTaskStatus(
                state = A2aTaskState.parse(status?.string("state")),
                message = decodeMessage(status?.get("message")),
                timestamp = status?.string("timestamp"),
            ),
            artifacts = (obj["artifacts"] as? JsonArray)?.mapNotNull(::decodeArtifact).orEmpty(),
            history = (obj["history"] as? JsonArray)?.mapNotNull(::decodeMessage).orEmpty(),
        )
    }

    fun encodeSendResult(result: A2aSendResult, version: A2aVersion): JsonObject = when (version) {
        A2aVersion.V1_0 -> when (result) {
            is A2aSendResult.TaskResult -> buildJsonObject { put("task", encodeTask(result.task, version)) }
            is A2aSendResult.MessageResult -> buildJsonObject { put("message", encodeMessage(result.message, version)) }
        }
        A2aVersion.V0_3 -> when (result) {
            is A2aSendResult.TaskResult -> encodeTask(result.task, version)
            is A2aSendResult.MessageResult -> encodeMessage(result.message, version)
        }
    }

    /** A `SendMessage` result in either version's shape; null when it is neither a task nor a message. */
    fun decodeSendResult(element: JsonElement?): A2aSendResult? {
        val obj = element as? JsonObject ?: return null
        (obj["task"] as? JsonObject)?.let { task -> return decodeTask(task)?.let { A2aSendResult.TaskResult(it) } }
        (obj["message"] as? JsonObject)?.takeIf { !obj.containsKey("id") }?.let { message ->
            return decodeMessage(message)?.let { A2aSendResult.MessageResult(it) }
        }
        val isMessage = obj.string("kind") == "message" ||
            (obj.string("kind") == null && obj.containsKey("messageId") && !obj.containsKey("status"))
        return if (isMessage) {
            decodeMessage(obj)?.let { A2aSendResult.MessageResult(it) }
        } else {
            decodeTask(obj)?.let { A2aSendResult.TaskResult(it) }
        }
    }

    // ---- Agent Card ------------------------------------------------------------------------

    /** The card in the current (1.0) shape. */
    fun encodeCard(card: A2aAgentCard): JsonObject = buildJsonObject {
        put("name", card.name)
        put("description", card.description)
        put(
            "supportedInterfaces",
            JsonArray(
                card.interfaces.map { endpoint ->
                    buildJsonObject {
                        put("url", endpoint.url)
                        put("protocolBinding", endpoint.protocolBinding)
                        put("protocolVersion", endpoint.protocolVersion)
                    }
                },
            ),
        )
        if (card.providerOrganization != null && card.providerUrl != null) {
            put(
                "provider",
                buildJsonObject {
                    put("organization", card.providerOrganization)
                    put("url", card.providerUrl)
                },
            )
        }
        put("version", card.version)
        card.documentationUrl?.let { put("documentationUrl", it) }
        put(
            "capabilities",
            buildJsonObject {
                put("streaming", card.streaming)
                put("pushNotifications", card.pushNotifications)
                put("extendedAgentCard", false)
            },
        )
        if (card.bearerAuth) {
            put(
                "securitySchemes",
                buildJsonObject {
                    put(BEARER_SCHEME, buildJsonObject { put("httpAuthSecurityScheme", buildJsonObject { put("scheme", "Bearer") }) })
                },
            )
            put(
                "securityRequirements",
                buildJsonArray {
                    add(buildJsonObject { put("schemes", buildJsonObject { put(BEARER_SCHEME, buildJsonObject { put("list", JsonArray(emptyList())) }) }) })
                },
            )
        }
        put("defaultInputModes", JsonArray(card.defaultInputModes.map(::JsonPrimitive)))
        put("defaultOutputModes", JsonArray(card.defaultOutputModes.map(::JsonPrimitive)))
        put(
            "skills",
            JsonArray(
                card.skills.map { skill ->
                    buildJsonObject {
                        put("id", skill.id)
                        put("name", skill.name)
                        put("description", skill.description)
                        put("tags", JsonArray(skill.tags.map(::JsonPrimitive)))
                        if (skill.examples.isNotEmpty()) put("examples", JsonArray(skill.examples.map(::JsonPrimitive)))
                    }
                },
            ),
        )
    }

    /** Reads a 1.0 (`supportedInterfaces`) or 0.3 (`url` + `preferredTransport`) card. */
    fun decodeCard(element: JsonElement): A2aAgentCard {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("Agent Card is not a JSON object")
        val interfaces = (obj["supportedInterfaces"] as? JsonArray)?.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val url = item.string("url") ?: return@mapNotNull null
            A2aAgentInterface(
                url = url,
                protocolBinding = item.string("protocolBinding") ?: item.string("transport") ?: A2aAgentInterface.BINDING_JSONRPC,
                protocolVersion = item.string("protocolVersion") ?: obj.string("protocolVersion") ?: "1.0",
            )
        } ?: buildList {
            val legacyVersion = obj.string("protocolVersion") ?: "0.3.0"
            obj.string("url")?.let { url ->
                add(A2aAgentInterface(url, obj.string("preferredTransport") ?: A2aAgentInterface.BINDING_JSONRPC, legacyVersion))
            }
            (obj["additionalInterfaces"] as? JsonArray)?.forEach { entry ->
                val item = entry as? JsonObject ?: return@forEach
                val url = item.string("url") ?: return@forEach
                add(A2aAgentInterface(url, item.string("transport") ?: A2aAgentInterface.BINDING_JSONRPC, legacyVersion))
            }
        }
        val capabilities = obj["capabilities"] as? JsonObject
        val schemes = (obj["securitySchemes"] as? JsonObject)?.values.orEmpty().filterIsInstance<JsonObject>()
        val bearer = schemes.any { scheme ->
            val http = scheme["httpAuthSecurityScheme"] as? JsonObject
            http?.string("scheme").equals("bearer", ignoreCase = true) ||
                (scheme.string("type") == "http" && scheme.string("scheme").equals("bearer", ignoreCase = true))
        }
        val provider = obj["provider"] as? JsonObject
        return A2aAgentCard(
            name = obj.string("name").orEmpty(),
            description = obj.string("description").orEmpty(),
            version = obj.string("version").orEmpty(),
            interfaces = interfaces,
            skills = (obj["skills"] as? JsonArray)?.mapNotNull { entry ->
                val skill = entry as? JsonObject ?: return@mapNotNull null
                A2aSkill(
                    id = skill.string("id") ?: return@mapNotNull null,
                    name = skill.string("name").orEmpty(),
                    description = skill.string("description").orEmpty(),
                    tags = (skill["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty(),
                    examples = (skill["examples"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty(),
                )
            }.orEmpty(),
            defaultInputModes = obj.strings("defaultInputModes"),
            defaultOutputModes = obj.strings("defaultOutputModes"),
            streaming = (capabilities?.get("streaming") as? JsonPrimitive)?.content == "true",
            pushNotifications = (capabilities?.get("pushNotifications") as? JsonPrimitive)?.content == "true",
            bearerAuth = bearer,
            providerOrganization = provider?.string("organization"),
            providerUrl = provider?.string("url"),
            documentationUrl = obj.string("documentationUrl"),
        )
    }

    // ---- JSON-RPC envelopes ----------------------------------------------------------------

    fun rpcRequest(id: JsonElement, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    /** The `result` of a JSON-RPC response, or [A2aRpcException] for its `error`. */
    fun rpcResultOf(response: JsonElement): JsonElement {
        val obj = response as? JsonObject ?: throw A2aRpcException(A2aErrorCodes.INVALID_AGENT_RESPONSE, "The agent's reply is not a JSON-RPC object")
        (obj["error"] as? JsonObject)?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: A2aErrorCodes.INTERNAL_ERROR
            throw A2aRpcException(code, error.string("message") ?: "A2A error $code")
        }
        return obj["result"] ?: throw A2aRpcException(A2aErrorCodes.INVALID_AGENT_RESPONSE, "The agent's reply has no result")
    }

    const val BEARER_SCHEME = "bearer"
    const val WELL_KNOWN_CARD_PATH = "/.well-known/agent-card.json"

    /** The 0.2 path, still requested by some clients. */
    const val LEGACY_WELL_KNOWN_CARD_PATH = "/.well-known/agent.json"
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()
