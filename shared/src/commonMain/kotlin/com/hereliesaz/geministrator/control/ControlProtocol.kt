package com.hereliesaz.geministrator.control

import kotlinx.serialization.json.JsonObject

/*
 * The control API's wire vocabulary (docs/MCP.md). The control API is how the standalone MCP
 * bridge (the `mcpBridge` module) reads and steers the running desktop app. It listens on loopback
 * only and every call carries a per-install bearer token. Nothing in it can approve or reject a
 * gate: approvals are read-only here and are decided in the app (THREAT_MODEL, Control API).
 */

/** Why a control call failed. [wire] is the stable string an MCP client sees as the error code. */
enum class ControlErrorCode(val http: Int, val wire: String) {
    Unauthorized(401, "unauthorized"),

    /** Not a loopback caller, a browser origin, or a Host that is not this computer. */
    Forbidden(403, "forbidden"),

    /** The request is well formed but a safety rule says no (an approval gate, a repository change). */
    PolicyDenied(403, "policy_denied"),
    NotFound(404, "not_found"),
    MethodNotAllowed(405, "method_not_allowed"),
    InvalidArgument(400, "invalid_argument"),

    /** The state of the app does not allow this right now (a live run, a hive from a package, a busy runtime). */
    Conflict(409, "conflict"),
    TooLarge(413, "too_large"),

    /** The app has no runtime yet, or no planner is linked. */
    Unavailable(503, "unavailable"),
    Internal(500, "internal"),
}

/** A typed failure; [message] is safe to show to an agent and never carries a credential. */
class ControlException(
    val code: ControlErrorCode,
    override val message: String,
    val details: JsonObject? = null,
) : RuntimeException(message)

internal fun controlError(code: ControlErrorCode, message: String, details: JsonObject? = null): Nothing =
    throw ControlException(code, message, details)

/** The remote address as the socket reported it; only a loopback caller is ever served. */
data class ControlCaller(val address: String, val isLoopback: Boolean) {
    companion object {
        val Loopback = ControlCaller("127.0.0.1", isLoopback = true)
    }
}

data class ControlHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
)

/** HTTP methods the control API uses. */
enum class ControlMethod { GET, POST, PATCH, PUT, DELETE }

/**
 * One entry of the control API's route table. [pattern] segments in braces are path parameters.
 * [mutating] routes change the app; [destructive] ones remove or stop something and are described
 * as such to MCP clients. The table is public so a test can enumerate everything the API offers.
 */
data class ControlRoute(
    val method: ControlMethod,
    val pattern: String,
    val name: String,
    val mutating: Boolean = false,
    val destructive: Boolean = false,
)
