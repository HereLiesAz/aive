package com.hereliesaz.geministrator.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The control API (docs/MCP.md): a token-protected JSON API over [ControlHost], platform-neutral;
 * the desktop serves it on loopback. Every request must come from a loopback address, name this
 * computer in `Host`, carry no browser `Origin`, and present `Authorization: Bearer <token>`.
 * Answers are `{"ok":true,"result":...}` or `{"ok":false,"error":{"code","message"}}`, and every
 * answer is scrubbed of the token and of key shapes ([ControlRedaction]).
 *
 * There is no route that approves, rejects or releases a gate, and [routes] lists everything.
 */
class ControlApi(
    private val host: ControlHost,
    /** The bearer token; null or blank refuses every call. */
    private val token: () -> String?,
) {
    companion object {
        const val PREFIX = "/control/v1"
        const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_LIMIT = 500

        /** Every route the API serves. Nothing else is reachable. */
        val routes: List<ControlRoute> = listOf(
            ControlRoute(ControlMethod.GET, "/status", "status"),
            ControlRoute(ControlMethod.GET, "/hives", "list_hives"),
            ControlRoute(ControlMethod.GET, "/hives/{id}", "get_hive"),
            ControlRoute(ControlMethod.POST, "/hives", "create_hive", mutating = true),
            ControlRoute(ControlMethod.POST, "/hives/draft", "draft_hive"),
            ControlRoute(ControlMethod.PATCH, "/hives/{id}", "update_hive", mutating = true),
            ControlRoute(ControlMethod.DELETE, "/hives/{id}", "delete_hive", mutating = true, destructive = true),
            ControlRoute(ControlMethod.POST, "/hives/{id}/creatures", "add_creature", mutating = true),
            ControlRoute(ControlMethod.PATCH, "/hives/{id}/creatures/{cid}", "update_creature", mutating = true),
            ControlRoute(ControlMethod.DELETE, "/hives/{id}/creatures/{cid}", "remove_creature", mutating = true, destructive = true),
            ControlRoute(ControlMethod.GET, "/roles", "list_roles"),
            ControlRoute(ControlMethod.GET, "/roles/{id}", "get_role"),
            ControlRoute(ControlMethod.PUT, "/roles/{id}/provider", "set_role_provider", mutating = true),
            ControlRoute(ControlMethod.GET, "/runs", "list_runs"),
            ControlRoute(ControlMethod.GET, "/runs/{id}", "get_run"),
            ControlRoute(ControlMethod.GET, "/runs/{id}/events", "list_events"),
            ControlRoute(ControlMethod.POST, "/runs", "start_run", mutating = true),
            ControlRoute(ControlMethod.POST, "/runs/{id}/stop", "stop_run", mutating = true, destructive = true),
            ControlRoute(ControlMethod.GET, "/approvals", "list_approvals"),
            ControlRoute(ControlMethod.GET, "/providers", "list_providers"),
            ControlRoute(ControlMethod.GET, "/settings", "get_settings"),
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val allowedHosts = setOf("127.0.0.1", "localhost", "[::1]")
    }

    suspend fun handle(
        method: String,
        rawPath: String,
        headers: Map<String, String>,
        body: String,
        caller: ControlCaller,
    ): ControlHttpResponse {
        val secret = token()
        val response = try {
            check(caller, headers, secret)
            if (body.length > MAX_BODY_BYTES) controlError(ControlErrorCode.TooLarge, "Request body is too large")
            val result = dispatch(method.uppercase(), rawPath, body)
            ControlHttpResponse(200, envelope(ok = true, payload = result))
        } catch (failure: ControlException) {
            fail(failure.code, failure.message, failure.details)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail(ControlErrorCode.Internal, "The app could not complete this request", null)
        }
        return finish(response, secret)
    }

    /**
     * The refusal this request earns from who is asking alone (not this computer, a browser, the wrong Host, no
     * valid token), or null. A server calls it **before it reads the body**, so an unauthenticated caller costs
     * nothing but the headers; [handle] makes the same checks again.
     */
    fun refusalBeforeBody(headers: Map<String, String>, caller: ControlCaller): ControlHttpResponse? {
        val secret = token()
        return try {
            check(caller, headers, secret)
            null
        } catch (failure: ControlException) {
            finish(fail(failure.code, failure.message, failure.details), secret)
        }
    }

    private fun finish(response: ControlHttpResponse, secret: String?): ControlHttpResponse {
        val scrubbed = ControlRedaction.scrub(response.body, listOfNotNull(secret))
        return response.copy(
            body = scrubbed,
            headers = response.headers + if (response.status == 401) mapOf("WWW-Authenticate" to "Bearer") else emptyMap(),
        )
    }

    private fun check(caller: ControlCaller, headers: Map<String, String>, secret: String?) {
        if (!caller.isLoopback) controlError(ControlErrorCode.Forbidden, "Only this computer may use the control API")
        val lower = headers.mapKeys { it.key.lowercase() }
        if (lower["origin"] != null) controlError(ControlErrorCode.Forbidden, "Browser requests are refused")
        val hostName = lower["host"]?.trim().orEmpty().let { h ->
            if (h.startsWith("[")) h.substringBefore("]") + "]" else h.substringBefore(':')
        }
        if (hostName.lowercase() !in allowedHosts) controlError(ControlErrorCode.Forbidden, "Host is not this computer")
        val presented = lower["authorization"]?.trim()?.let { v ->
            if (v.length > 7 && v.substring(0, 7).equals("Bearer ", ignoreCase = true)) v.substring(7).trim() else null
        }
        if (secret.isNullOrBlank() || presented == null || !constantTimeEquals(secret, presented)) {
            controlError(ControlErrorCode.Unauthorized, "A valid bearer token is required")
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        var diff = a.length xor b.length
        for (i in 0 until maxOf(a.length, b.length)) {
            diff = diff or ((a.getOrElse(i) { '\u0000' }.code) xor (b.getOrElse(i) { '\u0001' }.code))
        }
        return diff == 0
    }

    private suspend fun dispatch(method: String, rawPath: String, body: String): JsonElement {
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        if (path != PREFIX && !path.startsWith("$PREFIX/")) controlError(ControlErrorCode.NotFound, "Unknown path")
        val segments = path.removePrefix(PREFIX).trim('/').split('/').filter { it.isNotEmpty() }.map(::decode)
        val matched = routes.filter { match(it.pattern, segments) != null }
        if (matched.isEmpty()) controlError(ControlErrorCode.NotFound, "Unknown path")
        val route = matched.firstOrNull { it.method.name == method }
            ?: controlError(ControlErrorCode.MethodNotAllowed, "Method not allowed here")
        val p = match(route.pattern, segments)!!
        fun obj(): JsonObject = parseObject(body)
        fun limit(default: Int) = (query["limit"]?.toIntOrNull() ?: default).coerceIn(1, MAX_LIMIT)
        return when (route.name) {
            "status" -> host.status()
            "list_hives" -> host.listHives()
            "get_hive" -> host.getHive(p.getValue("id"))
            "create_hive" -> host.createHive(obj())
            "draft_hive" -> host.draftHive(obj().string("prompt") ?: invalid("prompt is required"))
            "update_hive" -> host.updateHive(p.getValue("id"), obj())
            "delete_hive" -> host.deleteHive(p.getValue("id"))
            "add_creature" -> host.addCreature(p.getValue("id"), obj())
            "update_creature" -> host.updateCreature(p.getValue("id"), p.getValue("cid"), obj())
            "remove_creature" -> host.removeCreature(p.getValue("id"), p.getValue("cid"))
            "list_roles" -> host.listRoles()
            "get_role" -> host.getRole(p.getValue("id"))
            "set_role_provider" -> {
                val o = obj()
                if ("providerId" !in o) invalid("providerId is required (a provider id, or null to leave the choice to the app)")
                val value = o["providerId"]
                host.setRoleProvider(p.getValue("id"), if (value is JsonNull) null else (value as? JsonPrimitive)?.contentOrNull ?: invalid("providerId must be a string or null"))
            }
            "list_runs" -> host.listRuns(query["hive"], limit(50))
            "get_run" -> host.getRun(p.getValue("id"))
            "list_events" -> host.listEvents(p.getValue("id"), query["after"]?.toLongOrNull(), limit(200))
            "start_run" -> host.startRun(obj())
            "stop_run" -> host.stopRun(p.getValue("id"))
            "list_approvals" -> host.listApprovals()
            "list_providers" -> host.listProviders()
            "get_settings" -> host.settings()
            else -> controlError(ControlErrorCode.NotFound, "Unknown path")
        }
    }

    private fun invalid(message: String): Nothing = controlError(ControlErrorCode.InvalidArgument, message)

    private fun match(pattern: String, segments: List<String>): Map<String, String>? {
        val parts = pattern.trim('/').split('/')
        if (parts.size != segments.size) return null
        val out = HashMap<String, String>()
        for ((i, part) in parts.withIndex()) {
            if (part.startsWith("{")) out[part.trim('{', '}')] = segments[i]
            else if (part != segments[i]) return null
        }
        return out
    }

    private fun parseObject(body: String): JsonObject {
        if (body.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(body) as? JsonObject ?: invalid("The body must be a JSON object")
        } catch (e: ControlException) {
            throw e
        } catch (_: Exception) {
            invalid("The body is not valid JSON")
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private fun decode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
            }
            flush()
            out.append(if (c == '+') ' ' else c)
            i++
        }
        flush()
        return out.toString()
    }

    private fun envelope(ok: Boolean, payload: JsonElement): String =
        buildJsonObject {
            put("ok", ok)
            put("result", payload)
        }.toString()

    private fun fail(code: ControlErrorCode, message: String, details: JsonObject?): ControlHttpResponse =
        ControlHttpResponse(
            code.http,
            buildJsonObject {
                put("ok", false)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code.wire)
                        put("message", message)
                        if (details != null) put("details", details)
                    },
                )
            }.toString(),
        )
}

internal fun jsonArrayOf(items: List<JsonElement>): JsonArray = JsonArray(items)
