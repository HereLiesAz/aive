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
    /** **Open to this network**; null where the platform cannot offer it. */
    val lan: A2aLanSetting? = null,
    /** **Describe my providers in the card**; null where the platform cannot offer it. */
    val providerCard: A2aProviderCardSetting? = null,
)

/**
 * **Describe my providers in the card** (docs/A2A.md): whether the public Agent Card lists which
 * kinds of provider are linked (name and capabilities only) and each role's provider. [described]
 * is what the card does now, from [a2aDescribeProviders]; [explicit] is whether the keeper ever
 * pressed the switch. Until then it follows the network: on while loopback only, off while open.
 */
data class A2aProviderCardSetting(
    val described: Boolean,
    val explicit: Boolean,
    val onChange: (Boolean) -> Unit,
)

/**
 * **Open to this network** (docs/A2A.md): off by default even while the server is on. While on,
 * other computers can reach the server, each needing the token and the keeper's allowance.
 * Traffic is plain HTTP, so the warning beside the switch is always shown.
 */
data class A2aLanSetting(
    val enabled: Boolean,
    /** Agent Card URLs on this computer's network addresses; empty while off or with no network. */
    val agentCardUrls: List<String>,
    /** Addresses the keeper allowed. */
    val allowedClients: List<String>,
    /** Addresses that presented the token but are not allowed yet, newest first. */
    val waitingClients: List<String>,
    val onEnabledChange: (Boolean) -> Unit,
    val onAllowClient: (String) -> Unit,
    val onForgetClient: (String) -> Unit,
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

/**
 * Whether the server is open to the local network, from the keeper's stored choice: only an
 * explicit on opens it. Turning the server on never opens it to the network.
 */
fun a2aServerOpenToNetwork(storedChoice: Boolean?): Boolean = storedChoice ?: false

/**
 * Whether the Agent Card describes the linked providers (docs/A2A.md, "Describing providers").
 * [storedChoice] is the keeper's explicit switch, null when never pressed. Never pressed: described
 * while the server is on this computer only, and left out as soon as it is **Open to this
 * network** (the card is then readable by the whole network, so which providers are linked is not
 * offered unless the keeper said so). An explicit choice, on or off, always wins.
 */
fun a2aDescribeProviders(storedChoice: Boolean?, openToNetwork: Boolean): Boolean = storedChoice ?: !openToNetwork

/** Reads a stored switch value written as `true`/`false`; anything else counts as never chosen. */
fun parseA2aServerChoice(stored: String?): Boolean? = when (stored?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}
