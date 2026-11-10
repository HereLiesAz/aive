package com.hereliesaz.geministrator.a2a

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * An independently hosted A2A agent suggested under Keeper's room › Connections (docs/A2A.md).
 * Its [name], [about] and operator are local catalog text. The card is fetched only after
 * the keeper chooses its address and presses Connect, never on rendering the suggestion.
 */
data class A2aDrifterSeed(
    /** The Agent Card URL, already normalized (https, explicit card path). */
    val cardUrl: String,
    val name: String,
    val about: String,
    /** Who runs it, as its card says. Seeds are third-party agents, not The Aive's. */
    val runBy: String,
    /** When and how it was checked: keyless card, JSON-RPC endpoint on the card's origin, a real reply. */
    val verified: String,
)

/**
 * Optional third-party A2A suggestions in Providers. Edit [SEEDS] to add or remove one; tests
 * require normalized HTTPS card URLs. Each must answer `SendMessage` without a key and serve a
 * JSON-RPC endpoint on its card's origin. The keeper can hide a suggestion on this device.
 */
object A2aDrifterSeeds {
    val SEEDS: List<A2aDrifterSeed> = listOf(
        A2aDrifterSeed(
            cardUrl = "https://agentbazaar.tech/a2a/connectors/worldbank/.well-known/agent.json",
            name = "World Bank Indicators",
            about = "Looks up World Development Indicators (GDP, population and the like) by country, from the World Bank's public data API.",
            runBy = "AgentBazaar (JNK Corp.), not affiliated with the World Bank",
            verified = "2026-11-02: card readable without a token, no security schemes, JSON-RPC 1.0 and 0.3 on the card's origin; " +
                "SendMessage \"GDP of France\" returned a completed task with a World Bank API recipe artifact.",
        ),
        A2aDrifterSeed(
            cardUrl = "https://mcp.moorish.cloud/.well-known/agent-card.json",
            name = "The Moorish Times",
            about = "Finds and reads articles and news briefs from The Moorish Times, a French and English magazine on Moroccan history, culture and current affairs.",
            runBy = "The Moorish Times",
            verified = "2026-11-02: card readable without a token, no security schemes, JSON-RPC 1.0 on the card's origin; " +
                "SendMessage \"latest news\" returned a message listing the latest news briefs with links.",
        ),
    )

    const val DISMISSED_KEY: String = "aive.a2a.drifters.dismissed-seeds.v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())
    private var settingsOverride: Settings? = null
    private val defaultSettings: Settings? by lazy { runCatching { Settings() }.getOrNull() }
    private val settings: Settings? get() = settingsOverride ?: defaultSettings
    private val state: MutableStateFlow<Set<String>> by lazy { MutableStateFlow(read()) }

    /** Card URLs of suggestions dismissed on this device. */
    val dismissed: StateFlow<Set<String>> get() = state

    fun isSeed(cardUrl: String): Boolean = SEEDS.any { it.cardUrl == cardUrl }

    fun seedFor(cardUrl: String): A2aDrifterSeed? = SEEDS.firstOrNull { it.cardUrl == cardUrl }

    /** Provider suggestions not dismissed and not already connected. */
    fun pending(connected: Collection<A2aAgentConnection> = A2aAgentConnections.all()): List<A2aDrifterSeed> {
        val connectedUrls = connected.mapTo(hashSetOf()) { it.cardUrl }
        return SEEDS.filter { it.cardUrl !in state.value && it.cardUrl !in connectedUrls }
    }

    /** Hide a suggested agent on this device. Unknown addresses are ignored. */
    fun dismiss(cardUrl: String) {
        if (!isSeed(cardUrl)) return
        state.update { it + cardUrl }
        settings?.putString(DISMISSED_KEY, json.encodeToString(serializer, state.value.sorted()))
    }

    /** Points the store at [settings] and reloads from it; for tests. */
    fun useSettings(settings: Settings) {
        settingsOverride = settings
        state.value = read()
    }

    private fun read(): Set<String> {
        val raw = runCatching { settings?.getStringOrNull(DISMISSED_KEY) }.getOrNull() ?: return emptySet()
        return runCatching { json.decodeFromString(serializer, raw).toSet() }.getOrDefault(emptySet())
    }
}
