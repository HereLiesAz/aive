package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.net.ResponseTooLargeException
import com.hereliesaz.geministrator.net.boundedText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Search results are leads, not trusted identities. The repository's health and task-test flags
 * describe its observations, not guarantees, and never grant Aive execution authority.
 */
internal data class AgentDirectoryEntry(
    val cardUrl: String,
    val name: String,
    val description: String,
    val skills: List<String>,
    val source: String,
    val registryReportsHealthy: Boolean = false,
    val registryReportsTaskTested: Boolean = false,
    val connectedProviderId: String? = null,
    /** A SearchA2A match score is ranking metadata, never an identity or trust rating. */
    val matchScore: Double? = null,
    /** Unverified publisher name claim from SearchA2A, never a provider permission. */
    val ansName: String? = null,
    val ansHost: String? = null,
)

internal data class AgentDirectoryResults(
    val entries: List<AgentDirectoryEntry>,
    val unavailableSources: List<String>,
)

/** A registry adapter. New registries join the directory without changing the A2A runtime. */
internal interface AgentDirectorySource {
    val name: String
    suspend fun search(query: String): List<AgentDirectoryEntry>
}

/** Local connections and optional suggestions. Reading this source performs no network I/O. */
internal object LocalAgentDirectorySource : AgentDirectorySource {
    override val name: String = "On this device"

    override suspend fun search(query: String): List<AgentDirectoryEntry> = buildList {
        A2aAgentConnections.all().forEach { connection ->
            add(
                AgentDirectoryEntry(
                    cardUrl = connection.cardUrl,
                    name = connection.name,
                    description = "Connected remote A2A provider",
                    skills = emptyList(),
                    source = name,
                    connectedProviderId = connection.providerId,
                ),
            )
        }
        A2aDrifterSeeds.pending().forEach { seed ->
            add(
                AgentDirectoryEntry(
                    cardUrl = seed.cardUrl,
                    name = seed.name,
                    description = seed.about,
                    skills = emptyList(),
                    source = "Suggested",
                ),
            )
        }
    }.filter { directoryRelevance(query, it) > 0 }
}

/**
 * Aive's only external discovery backend is SearchA2A, which also publishes Azphalt listings.
 * SearchA2A ranks its indexed metadata with embeddings when available. If its public Worker
 * is down or rate-limited, use the published, read-only GitHub catalog with keyword matching.
 * Search never contacts advertised Agent Cards or enrolls providers.
 */
internal class SearchA2aDirectorySource(
    private val http: HttpClient = sharedDirectoryClient,
) : AgentDirectorySource {
    override val name: String = "SearchA2A"

    override suspend fun search(query: String): List<AgentDirectoryEntry> {
        try {
            val root = fetch(INDEXED_SEARCH_URL, query)
            require(root["schema"].valueText() == "1" && root["source"].valueText() == "searcha2a") {
                "Invalid SearchA2A search response"
            }
            val mode = root["match_mode"].valueText()
            require(mode == "semantic" || mode == "keyword-fallback") { "Unknown SearchA2A ranking mode" }
            val items = root["results"] as? JsonArray ?: error("Missing SearchA2A results")
            return entries(items, if (mode == "semantic") "SearchA2A · semantic" else "SearchA2A · keywords", true)
                .take(MAX_RESULTS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A static published snapshot is useful without granting a second discovery service
            // control over the connected providers. It does not provide semantic ranking.
        }
        val root = fetch(PUBLISHED_FEED_URL)
        require(root["schema"].valueText() == "1" && root["source"].valueText() == "searcha2a") {
            "Invalid SearchA2A published catalog"
        }
        val listings = root["listings"] as? JsonArray ?: error("Missing SearchA2A catalog listings")
        // The full response is byte-bounded, but its items are not query-filtered. Match
        // before applying the result cap so a later catalog entry is never silently lost.
        return entries(listings, "SearchA2A · cached catalog", false)
            .filter { directoryRelevance(query, it) > 0 }
            .take(MAX_RESULTS)
    }

    private suspend fun fetch(endpoint: String, query: String? = null): JsonObject {
        val response = http.get(endpoint) {
            if (query != null) url { parameters.append("q", query) }
        }
        if (response.status != HttpStatusCode.OK) error("Directory source unavailable")
        val body = try {
            response.boundedText(MAX_RESPONSE_BYTES)
        } catch (_: ResponseTooLargeException) {
            error("Directory response too large")
        }
        return Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid directory response")
    }

    private fun entries(items: JsonArray, sourceLabel: String, ranked: Boolean): List<AgentDirectoryEntry> =
        items.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            if (record["kind"].valueText() != "a2a-agent" ||
                record["installable"].valueText() != "false" ||
                record["verification"].valueText() != "card_read_only"
            ) return@mapNotNull null
            val cardUrl = directoryPublicCardUrl(record["source_url"].valueText().orEmpty())
                ?: return@mapNotNull null
            val skills = (record["skills"] as? JsonArray).orEmpty().mapNotNull { item ->
                val skill = item as? JsonObject ?: return@mapNotNull null
                A2aDrifters.cleanCardText(skill["name"].valueText().orEmpty(), 80)
                    .takeIf(String::isNotBlank)
            }.distinct().take(8)
            val match = if (ranked) record["match_score"].valueText()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..1.0 } else null
            AgentDirectoryEntry(
                cardUrl = cardUrl,
                name = A2aDrifters.cleanCardText(record["name"].valueText().orEmpty(), 60)
                    .ifBlank { A2aDrifters.hostOf(cardUrl) },
                description = A2aDrifters.cleanCardText(record["description"].valueText().orEmpty(), 240),
                skills = skills,
                source = sourceLabel,
                matchScore = match,
                ansName = directoryAnsClaim(record)?.first,
                ansHost = directoryAnsClaim(record)?.second,
            )
        }

    /** Parse SearchA2A's indexed `ans` and Azphalt's cached `ans_claim` without upgrading trust. */
    private fun directoryAnsClaim(record: JsonObject): Pair<String, String>? {
        val raw = (record["ans"] as? JsonObject) ?: (record["ans_claim"] as? JsonObject)
            ?: return null
        if (raw["verification"].valueText() != "unverified") return null
        val name = raw["name"].valueText() ?: return null
        val host = raw["agent_host"].valueText() ?: return null
        if (name.length > 259 || host.length > 237 || !name.startsWith("ans://")) return null
        val suffix = "." + host.lowercase()
        if (!name.lowercase().endsWith(suffix)) return null
        val version = name.substring(6, name.length - suffix.length)
        val validLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        val labels = host.split('.') + version.split('.')
        if (host.split('.').size < 2 || labels.any { !validLabel.matches(it) } ||
            host.endsWith(".localhost", true) || host.endsWith(".internal", true) ||
            host.endsWith(".local", true) || host.substringAfterLast('.').all(Char::isDigit)
        ) return null
        return name to host
    }

    private fun kotlinx.serialization.json.JsonElement?.valueText(): String? =
        (this as? JsonPrimitive)?.content

    private companion object {
        const val INDEXED_SEARCH_URL = "https://searcha2a-api.hereliesaz.workers.dev/api/v1/directory"
        const val PUBLISHED_FEED_URL =
            "https://raw.githubusercontent.com/HereLiesAz/searcha2a/agent-feed/site/api/v1/azphalt-listings.json"
        const val MAX_RESPONSE_BYTES = 900_000
        const val MAX_RESULTS = 70
    }
}

/**
 * Federates local agents with public registry adapters. Discovery is strictly read-only: it never
 * adds connections, routes tasks, or fetches third-party Agent Cards. Results are deduplicated by
 * normalized card URL, retaining local connection approval if a registry also lists the agent.
 */
internal class AgentDirectory(
    private val sources: List<AgentDirectorySource> = listOf(LocalAgentDirectorySource, SearchA2aDirectorySource()),
) {
    suspend fun search(rawQuery: String): AgentDirectoryResults {
        val query = rawQuery.trim().take(MAX_QUERY_CHARS)
        if (query.length < 2) return AgentDirectoryResults(emptyList(), emptyList())
        val found = linkedMapOf<String, AgentDirectoryEntry>()
        // Keep connected status independently of search relevance: a registry may match an
        // already-connected agent by a skill that its saved local record does not contain.
        val connectedByUrl = A2aAgentConnections.all().associateBy { it.cardUrl }
        val unavailable = mutableListOf<String>()
        for (source in sources) {
            val entries = try {
                source.search(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable += source.name
                continue
            }
            for (entry in entries.take(MAX_ENTRIES_PER_SOURCE)) {
                // User-connected loopback agents are legitimate local entries; a public registry
                // must never supply such an address for the user to fetch.
                val url = if (source === LocalAgentDirectorySource) {
                    runCatching { A2aAgentConnections.normalizeCardUrl(entry.cardUrl) }.getOrNull()
                } else {
                    directoryPublicCardUrl(entry.cardUrl)
                } ?: continue
                val existing = found[url]
                found[url] = entry.copy(
                    cardUrl = url,
                    connectedProviderId = connectedByUrl[url]?.providerId
                        ?: existing?.connectedProviderId ?: entry.connectedProviderId,
                )
            }
        }
        return AgentDirectoryResults(
            entries = found.values.sortedWith(
                compareByDescending<AgentDirectoryEntry> { it.connectedProviderId != null }
                    .thenByDescending { it.matchScore ?: -1.0 }
                    .thenByDescending { directoryRelevance(query, it) }
                    .thenByDescending { it.registryReportsTaskTested }
                    .thenBy { it.name.lowercase() },
            ).take(MAX_ENTRIES),
            unavailableSources = unavailable,
        )
    }

    private companion object {
        const val MAX_QUERY_CHARS = 120
        const val MAX_ENTRIES_PER_SOURCE = 30
        const val MAX_ENTRIES = 30
    }
}

/** Reject registry-supplied private, credential-bearing, or ambiguous Agent Card origins. */
internal fun directoryPublicCardUrl(raw: String): String? {
    if (raw.length > 2048 || !raw.startsWith("https://", ignoreCase = true)) return null
    if (raw.any(Char::isWhitespace) || raw.any { it in "@?#\\%" }) return null
    val authority = raw.substringAfter("://").substringBefore('/')
    val host = authority.substringBefore(':').lowercase()
    val port = authority.substringAfter(':', "")
    if (port.isNotEmpty() && (port.toIntOrNull() ?: -1) !in 1..65535) return null
    if (host.length > 253 || !host.contains('.') ||
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".internal") || host.split('.').any { it.isBlank() || !it.matches(Regex("[a-z0-9-]+")) } ||
        host.split('.').all { it.toIntOrNull() != null }
    ) return null
    return runCatching { A2aAgentConnections.normalizeCardUrl(raw) }.getOrNull()
}

/** Deterministic skill/name/description relevance, not an embedding or an authorization decision. */
internal fun directoryRelevance(query: String, entry: AgentDirectoryEntry): Int {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
    if (words.isEmpty()) return 0
    val name = entry.name.lowercase()
    val skills = entry.skills.joinToString(" ").lowercase()
    val description = entry.description.lowercase()
    return words.sumOf { word ->
        when {
            word in name -> 5
            word in skills -> 4
            word in description -> 1
            else -> 0
        }
    }
}

private val sharedDirectoryClient: HttpClient by lazy {
    HttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 8_000L
        }
    }
}
