package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.net.CleartextPolicy
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A remote A2A agent the user connected in the Keeper's room by its Agent Card URL. Only the URL
 * and a name are kept here (not secret); an optional bearer token lives in the platform credential
 * store under [providerId], like any provider key.
 */
@Serializable
data class A2aAgentConnection(
    val slug: String,
    val cardUrl: String,
    val name: String,
) {
    val providerId: String get() = providerIdFor(slug)

    companion object {
        /** Prefixed so a connection can never take a built-in or store provider's id. */
        fun providerIdFor(slug: String): String = "$PROVIDER_ID_PREFIX$slug"
        const val PROVIDER_ID_PREFIX = "a2a:"
    }
}

/**
 * The connected A2A agents, persisted in [Settings]. Provider catalogs read [all] on every access,
 * so adding or removing one shows up in Connections, credential stores and provider selection.
 */
object A2aAgentConnections {
    const val STORAGE_KEY: String = "aive.a2a.agents.v1"
    const val MAX_CONNECTIONS: Int = 32

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(A2aAgentConnection.serializer())
    private var settingsOverride: Settings? = null
    private val defaultSettings: Settings? by lazy { runCatching { Settings() }.getOrNull() }
    private val settings: Settings? get() = settingsOverride ?: defaultSettings
    private val state: MutableStateFlow<List<A2aAgentConnection>> by lazy { MutableStateFlow(read()) }

    val connections: StateFlow<List<A2aAgentConnection>> get() = state

    fun all(): List<A2aAgentConnection> = state.value

    fun byProviderId(providerId: String): A2aAgentConnection? = all().firstOrNull { it.providerId == providerId }

    /**
     * Adds the agent whose card is at [rawUrl] and returns it. A bare origin
     * (`https://agent.example`) means its well-known card. The URL must be HTTPS, or HTTP to a
     * loopback or private-network host, since a bearer token may be sent to it.
     */
    fun add(rawUrl: String): A2aAgentConnection {
        val cardUrl = normalizeCardUrl(rawUrl)
        all().firstOrNull { it.cardUrl == cardUrl }?.let { return it }
        require(all().size < MAX_CONNECTIONS) { "At most $MAX_CONNECTIONS A2A agents can be connected" }
        val host = hostOf(cardUrl)
        val base = slugOf(host)
        var slug = base
        var n = 2
        while (all().any { it.slug == slug }) slug = "$base-${n++}"
        val connection = A2aAgentConnection(slug = slug, cardUrl = cardUrl, name = "A2A · $host")
        state.update { it + connection }
        write(state.value)
        return connection
    }

    /** Names the connection after its Agent Card, once the card has been read. */
    fun rename(providerId: String, name: String) {
        val clean = name.trim().take(80).ifBlank { return }
        state.update { current -> current.map { if (it.providerId == providerId) it.copy(name = clean) else it } }
        write(state.value)
    }

    fun remove(providerId: String) {
        state.update { current -> current.filterNot { it.providerId == providerId } }
        write(state.value)
    }

    /** Points the registry at [settings] and reloads from it; for tests. */
    fun useSettings(settings: Settings) {
        settingsOverride = settings
        state.value = read()
    }

    fun normalizeCardUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val lower = trimmed.lowercase()
        require(lower.startsWith("https://") || lower.startsWith("http://")) { "Enter the agent's https:// address" }
        require(CleartextPolicy.allowsSecret(trimmed)) { "Use https:// (plain http:// only for this computer or the local network)" }
        require(trimmed.none { it.isWhitespace() }) { "The address cannot contain spaces" }
        val afterScheme = trimmed.substringAfter("://")
        require(afterScheme.substringBefore('/').isNotBlank()) { "The address has no host" }
        val hasPath = afterScheme.contains('/') && afterScheme.substringAfter('/').isNotBlank()
        return if (hasPath) trimmed else trimmed.trimEnd('/') + A2aCodec.WELL_KNOWN_CARD_PATH
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase()

    private fun slugOf(host: String): String =
        host.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '-' }
            .joinToString("")
            .trim('-', '.')
            .take(48)
            .ifBlank { "agent" }

    private fun read(): List<A2aAgentConnection> {
        val raw = runCatching { settings?.getStringOrNull(STORAGE_KEY) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(values: List<A2aAgentConnection>) {
        settings?.putString(STORAGE_KEY, json.encodeToString(serializer, values))
    }
}
