package com.hereliesaz.geministrator

/**
 * The Keeper's room switch for The Aive's A2A server (docs/A2A.md). Desktop only: on by default,
 * on loopback, every call needing the token. The keeper can turn it off, and that choice is kept.
 */
data class A2aServerSetting(
    val enabled: Boolean,
    /** Where other agents read The Aive's Agent Card; null while off. */
    val agentCardUrl: String?,
    val status: A2aServerStatus,
    /** The token cannot be kept in this computer's keychain, so it changes with every start. */
    val tokenIsTemporary: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit,
    /** Copies the bearer token to the clipboard; the token is never shown on screen. */
    val onCopyToken: () -> Unit,
    /** Replaces the token; clients holding the old one are refused from then on. */
    val onResetToken: () -> Unit,
)

sealed interface A2aServerStatus {
    data object Off : A2aServerStatus

    data class Listening(val address: String) : A2aServerStatus

    /** Another program holds [port]; the server is not running, and the rest of The Aive is unaffected. */
    data class PortInUse(val port: Int) : A2aServerStatus

    /** It could not start; [detail] is the technical reason. */
    data class Failed(val detail: String) : A2aServerStatus
}

/**
 * Whether the A2A server should run, from the keeper's stored choice. [storedChoice] is null when
 * the keeper never touched the switch: such a device gets the default (on), including existing
 * installs from when the default was off. An explicit choice, on or off, is always kept.
 */
fun a2aServerEnabled(storedChoice: Boolean?): Boolean = storedChoice ?: A2A_SERVER_ENABLED_BY_DEFAULT

/** The server runs unless the keeper turned it off; it is reachable only on this computer by default. */
const val A2A_SERVER_ENABLED_BY_DEFAULT: Boolean = true

/** Reads a stored switch value written as `true`/`false`; anything else counts as never chosen. */
fun parseA2aServerChoice(stored: String?): Boolean? = when (stored?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}
