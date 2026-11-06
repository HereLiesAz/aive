package com.hereliesaz.geministrator.mcpbridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the control API answered. */
data class ControlReply(val status: Int, val body: String)

/** Sends one request to the app's control API; throws [ControlUnreachable] when the app cannot be reached. */
fun interface ControlTransport {
    fun send(method: String, path: String, body: String?): ControlReply
}

class ControlUnreachable(message: String) : RuntimeException(message)

/**
 * A small Model Context Protocol server (revision 2025-06-18, tools and resources) over newline
 * delimited JSON-RPC, as the stdio transport defines. [handle] takes one message and returns the
 * reply line, or null for a notification.
 */
class McpServer(
    private val transport: ControlTransport,
    private val serverVersion: String = "dev",
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val supported = setOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val json = Json { prettyPrint = true }

        /** Fixed resources, each backed by one read-only route. */
        val resources: List<Triple<String, String, String>> = listOf(
            Triple("aive://status", "Status", "/status"),
            Triple("aive://hives", "Hives", "/hives"),
            Triple("aive://roles", "Roles", "/roles"),
            Triple("aive://runs", "Runs", "/runs"),
            Triple("aive://approvals", "Pending approvals (read-only)", "/approvals"),
            Triple("aive://providers", "Providers and connection state", "/providers"),
            Triple("aive://settings", "Settings (no keys)", "/settings"),
        )

        /** `aive://hives/{id}` and friends, mapped to the route they read. */
        val templates: List<Triple<String, String, String>> = listOf(
            Triple("aive://hives/{id}", "Hive", "/hives/{id}"),
            Triple("aive://roles/{id}", "Role", "/roles/{id}"),
            Triple("aive://runs/{id}", "Run", "/runs/{id}"),
            Triple("aive://runs/{id}/events", "Run log", "/runs/{id}/events"),
        )
    }

    fun handle(line: String): String? {
        val message = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return error(JsonNull, -32700, "Parse error")
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) return null // a response to us; we send no requests
        if (id == null) return null // a notification
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            result(id, dispatch(method, params))
        } catch (e: RpcException) {
            error(id, e.code, e.message)
        } catch (_: Exception) {
            error(id, -32603, "Internal error")
        }
    }

    private class RpcException(val code: Int, override val message: String) : RuntimeException(message)

    private fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "initialize" -> {
            val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            buildJsonObject {
                put("protocolVersion", if (asked in supported) asked!! else PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                    put("resources", buildJsonObject { put("subscribe", false); put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject { put("name", "aive"); put("title", "The Aive"); put("version", serverVersion) })
                put(
                    "instructions",
                    "Reads and steers a running The Aive desktop app. Approvals are read-only here: " +
                        "they are decided in the app, never through this server.",
                )
            }
        }
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    Tools.all.forEach { t ->
                        add(
                            buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("inputSchema", t.schema())
                                put(
                                    "annotations",
                                    buildJsonObject {
                                        put("readOnlyHint", t.readOnly)
                                        if (!t.readOnly) {
                                            put("destructiveHint", t.destructive)
                                            put("idempotentHint", t.idempotent)
                                        }
                                        put("openWorldHint", false)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        "tools/call" -> callTool(params)
        "resources/list" -> buildJsonObject {
            put(
                "resources",
                buildJsonArray {
                    resources.forEach { (uri, name, _) ->
                        add(buildJsonObject { put("uri", uri); put("name", name); put("mimeType", "application/json") })
                    }
                },
            )
        }
        "resources/templates/list" -> buildJsonObject {
            put(
                "resourceTemplates",
                buildJsonArray {
                    templates.forEach { (uri, name, _) ->
                        add(buildJsonObject { put("uriTemplate", uri); put("name", name); put("mimeType", "application/json") })
                    }
                },
            )
        }
        "resources/read" -> readResource(params)
        else -> throw RpcException(-32601, "Method not found: $method")
    }

    private fun callTool(params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: throw RpcException(-32602, "name is required")
        val tool = Tools.byName(name) ?: throw RpcException(-32602, "Unknown tool: $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val unknown = arguments.keys - tool.properties.keys
        if (unknown.isNotEmpty()) return toolError("invalid_argument", "Unknown argument(s): ${unknown.sorted().joinToString()}")
        val missing = tool.required.filter { it !in arguments || arguments[it] is JsonNull && it != "providerId" }
        if (missing.isNotEmpty()) return toolError("invalid_argument", "Missing required argument(s): ${missing.joinToString()}")
        val (method, path, body) = try {
            Tools.request(tool, arguments)
        } catch (e: BridgeArgumentException) {
            return toolError("invalid_argument", e.message ?: "invalid arguments")
        }
        return forward(method, path, body, asTool = true)
    }

    private fun readResource(params: JsonObject): JsonElement {
        val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull ?: throw RpcException(-32602, "uri is required")
        val path = resources.firstOrNull { it.first == uri }?.third
            ?: templates.firstNotNullOfOrNull { (template, _, route) -> expand(template, route, uri) }
            ?: throw RpcException(-32002, "Resource not found: $uri")
        val reply = try {
            transport.send("GET", path, null)
        } catch (e: ControlUnreachable) {
            throw RpcException(-32603, e.message ?: "The Aive is not reachable")
        }
        if (reply.status !in 200..299) throw RpcException(-32002, errorText(reply))
        val text = pretty(reply.body, unwrap = true)
        return buildJsonObject {
            put("contents", buildJsonArray { add(buildJsonObject { put("uri", uri); put("mimeType", "application/json"); put("text", text) }) })
        }
    }

    private fun expand(template: String, route: String, uri: String): String? {
        val prefix = template.substringBefore("{id}")
        val suffix = template.substringAfter("{id}")
        if (!uri.startsWith(prefix) || !uri.endsWith(suffix) || uri.length <= prefix.length + suffix.length) return null
        val id = uri.substring(prefix.length, uri.length - suffix.length)
        if (id.isEmpty() || '/' in id) return null
        return route.replace("{id}", java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20"))
    }

    private fun forward(method: String, path: String, body: String?, asTool: Boolean): JsonElement {
        val reply = try {
            transport.send(method, path, body)
        } catch (e: ControlUnreachable) {
            return toolError("app_unreachable", e.message ?: "The Aive is not reachable. Is it running with its control API on?")
        }
        if (reply.status !in 200..299) {
            val parsed = runCatching { (Json.parseToJsonElement(reply.body) as JsonObject)["error"] as JsonObject }.getOrNull()
            return toolError(
                (parsed?.get("code") as? JsonPrimitive)?.contentOrNull ?: "http_${reply.status}",
                (parsed?.get("message") as? JsonPrimitive)?.contentOrNull ?: "The app refused the request",
            )
        }
        return buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", pretty(reply.body, unwrap = true)) }) })
            put("isError", false)
        }
    }

    private fun toolError(code: String, message: String): JsonElement = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", json.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", code); put("message", message) }))
            })
        })
        put("isError", true)
    }

    private fun errorText(reply: ControlReply): String =
        runCatching {
            (((Json.parseToJsonElement(reply.body) as JsonObject)["error"] as JsonObject)["message"] as JsonPrimitive).content
        }.getOrDefault("The app refused the request")

    private fun pretty(body: String, unwrap: Boolean): String {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return body
        val shown = if (unwrap && element is JsonObject && element["ok"] != null) element["result"] ?: element else element
        return json.encodeToString(JsonElement.serializer(), shown)
    }

    private fun result(id: JsonElement, result: JsonElement) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()

    private fun error(id: JsonElement, code: Int, message: String) =
        buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }.toString()
}
