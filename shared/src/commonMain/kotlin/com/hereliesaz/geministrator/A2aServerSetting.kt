package com.hereliesaz.geministrator

/**
 * The Keeper's room switch for The Aive's A2A server (docs/A2A.md). Desktop only: off by default,
 * on loopback, every call needing the token.
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

    /** It could not start; [detail] is the technical reason (a port in use, for example). */
    data class Failed(val detail: String) : A2aServerStatus
}
