package com.hereliesaz.geministrator

/**
 * Settings-screen hook for Remote access: letting the user's own IDEaz on another device reach
 * this app through an end-to-end encrypted relay. Only Android passes a non-null value; other
 * platforms hide the section. Off by default.
 */
data class RemoteAccessSetting(
    val enabled: Boolean,
    /** The relay to use; editable only while [enabled] is false. */
    val relayUrl: String,
    /** The relay used when [relayUrl] is left alone. */
    val defaultRelayUrl: String,
    /** The code to give IDEaz; null while off. It works like a password. */
    val pairingCode: String?,
    /** One line on the link ("Connected", "Offline: ..."), or a problem turning it on. */
    val status: String?,
    /** Raw technical text behind [status], shown under a Details toggle; null when none. */
    val statusDetails: String? = null,
    val onEnabledChange: (Boolean) -> Unit,
    val onRelayUrlChange: (String) -> Unit,
    /** Replaces the room, tokens and key; IDEaz must be paired again. */
    val onResetPairing: () -> Unit,
    val onCopyPairingCode: (String) -> Unit,
    val onSharePairingCode: (String) -> Unit,
)
