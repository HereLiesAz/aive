package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A linked provider as the Agent Card may describe it (docs/A2A.md, "Describing providers"): its
 * id, the name the app shows for it, and the capabilities it reports. Nothing else about a provider
 * is ever put on a card: no key, token, base URL, account, model or balance. Built from the live
 * provider registry, which holds only providers that are connected here.
 */
data class A2aProviderInfo(
    val id: String,
    val displayName: String,
    val capabilities: Set<AgentCapability>,
)

/**
 * Provider skills for the Agent Card. Deterministic and bounded: providers are sorted by id,
 * de-duplicated and capped, every text is one sanitized line of bounded length, and the card's
 * wording comes from the capability names, never from a provider.
 */
object A2aProviderCards {
    const val MAX_PROVIDER_SKILLS = 24
    const val MAX_NAME_CHARS = 60
    const val AUTO_TAG = "provider:auto"
    const val SKILL_PREFIX = "provider:"

    /** The providers to describe: sorted, one per card key, at most [MAX_PROVIDER_SKILLS]. */
    fun listed(providers: Collection<A2aProviderInfo>): List<A2aProviderInfo> =
        providers.sortedBy { it.id }.distinctBy { cardKey(it.id) }.take(MAX_PROVIDER_SKILLS)

    /** One `provider:<key>` skill per listed provider. */
    fun skills(providers: Collection<A2aProviderInfo>): List<A2aSkill> = listed(providers).map { provider ->
        val key = cardKey(provider.id)
        A2aSkill(
            id = SKILL_PREFIX + key,
            name = nameOf(provider),
            description = describe(provider.capabilities),
            tags = listOf("provider") + provider.capabilities.map { it.name.lowercase() }.sorted(),
        )
    }

    /**
     * The role's provider assignment as card text: the tag, and a short clause for its description.
     * A preferred provider shows only while it is linked; otherwise the role runs on "auto".
     */
    fun roleAssignment(role: RoleDefinition, providers: Collection<A2aProviderInfo>): Pair<String, String> {
        val preferred = role.preferredProviderId?.value
        val provider = preferred?.let { id -> listed(providers).firstOrNull { it.id == id } }
        return if (provider == null) {
            AUTO_TAG to "Provider: chosen automatically."
        } else {
            (SKILL_PREFIX + cardKey(provider.id)) to "Provider: ${nameOf(provider)}."
        }
    }

    private fun nameOf(provider: A2aProviderInfo): String =
        if (provider.id.startsWith(REMOTE_AGENT_PREFIX)) {
            "Remote A2A agent"
        } else {
            a2aOneLine(provider.displayName, MAX_NAME_CHARS).ifBlank { cardKey(provider.id) }
        }

    private fun describe(capabilities: Set<AgentCapability>): String {
        val names = capabilities.map { words(it.name) }.sorted()
        return if (names.isEmpty()) "Linked provider." else "Linked provider. Reports: ${names.joinToString(", ")}."
    }

    /** "PlanGeneration" as "plan generation". */
    private fun words(name: String): String = buildString {
        name.forEachIndexed { index, c ->
            if (c.isUpperCase() && index > 0) append(' ')
            append(c.lowercaseChar())
        }
    }

    private const val REMOTE_AGENT_PREFIX = "a2a:"

    /**
     * The id a provider has on a card. A remote A2A agent's id carries its host name, which a card
     * does not need to reveal, so it becomes a stable hash; every other id is cut to a safe word.
     */
    fun cardKey(providerId: String): String {
        if (providerId.startsWith(REMOTE_AGENT_PREFIX)) return "a2a-" + stableHash(providerId)
        val safe = providerId.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '-' }
            .joinToString("").trim('-').take(48)
        return safe.ifBlank { "provider" }
    }

    /** FNV-1a over the id's characters, as 8 hex digits: the same input gives the same key everywhere. */
    private fun stableHash(text: String): String {
        var hash = 0x811c9dc5L
        for (c in text) {
            hash = (hash xor c.code.toLong()) and 0xffffffffL
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        return hash.toString(16).padStart(8, '0')
    }
}

/**
 * Remote or user text as one short line for a card: control characters become spaces, runs of
 * whitespace collapse, and the result is cut to [max] characters without splitting a surrogate pair.
 */
fun a2aOneLine(text: String?, max: Int): String {
    if (text == null || max <= 0) return ""
    val out = StringBuilder()
    var pendingSpace = false
    for (c in text) {
        if (c.isWhitespace() || c.isISOControl()) {
            pendingSpace = out.isNotEmpty()
            continue
        }
        if (pendingSpace) {
            out.append(' ')
            pendingSpace = false
        }
        out.append(c)
        if (out.length >= max) break
    }
    if (out.isNotEmpty() && out.last().isHighSurrogate()) out.setLength(out.length - 1)
    return out.toString().trim()
}

private const val CAPABILITIES_TIMEOUT_MILLIS = 1_500L

/**
 * The providers linked here (the registry holds only those), as [A2aProviderInfo]: each one's id,
 * the name the app shows for it and the capabilities it reports. Nothing else is read from a
 * provider, so no credential, base URL, account or model can reach a card through here. A provider
 * that cannot report its capabilities in time is left out.
 */
suspend fun AgentProviderRegistry.a2aProviderInfos(): List<A2aProviderInfo> =
    providerIds.sortedBy { it.value }.mapNotNull { id ->
        val provider = provider(id) ?: return@mapNotNull null
        val capabilities = withTimeoutOrNull(CAPABILITIES_TIMEOUT_MILLIS) {
            runCatching { provider.capabilities().supported }.getOrNull()
        } ?: return@mapNotNull null
        A2aProviderInfo(id = id.value, displayName = displayName(id), capabilities = capabilities)
    }
