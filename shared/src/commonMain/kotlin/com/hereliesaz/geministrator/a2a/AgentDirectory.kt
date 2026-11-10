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
 * Public A2A Registry adapter, GET /api/agents?search=... .
 * See https://github.com/prassanna-ravishankar/a2a-registry (PaginatedAgents).
 * Aive never sends credentials or task contents to the registry and never contacts returned cards
 * while searching. A keeper must select a result and explicitly connect it in Providers.
 */
internal class PublicA2aRegistrySource(
    private val http: HttpClient = sharedDirectoryClient,
) : AgentDirectorySource {
    override val name: String = "A2A Registry"

    override suspend fun search(query: String): List<AgentDirectoryEntry> {
        val response = http.get("https://a2aregistry.org/api/agents") {
            url {
                parameters.append("search", query)
                parameters.append("limit", "20")
                parameters.append("offset", "0")
            }
        }
        if (response.status != HttpStatusCode.OK) error("Registry unavailable: HTTP ${response.status.value}")
        val body = try {
            response.boundedText(MAX_REGISTRY_BYTES)
        } catch (_: ResponseTooLargeException) {
            error("Registry response exceeds the directory size limit")
        }
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid registry response")
        val agents = root["agents"] as? JsonArray ?: error("Missing registry agent list")
        return agents.take(MAX_RESULTS).mapNotNull { entry ->
            val record = entry as? JsonObject ?: return@mapNotNull null
            val cardUrl = directoryPublicCardUrl(record.string("wellKnownURI").orEmpty()) ?: return@mapNotNull null
            val skills = (record["skills"] as? JsonArray).orEmpty().mapNotNull { skill ->
                val item = skill as? JsonObject ?: return@mapNotNull null
                val label = item.string("name") ?: item.string("id") ?: return@mapNotNull null
                A2aDrifters.cleanCardText(label, 80).takeIf(String::isNotBlank)
            }.distinct().take(8)
            AgentDirectoryEntry(
                cardUrl = cardUrl,
                name = A2aDrifters.cleanCardText(record.string("name").orEmpty(), 60)
                    .ifBlank { A2aDrifters.hostOf(cardUrl) },
                description = A2aDrifters.cleanCardText(record.string("description").orEmpty(), 240),
                skills = skills,
                source = name,
                // These are observations claimed by the registry, not Aive's own health or trust checks.
                registryReportsHealthy = (record["is_healthy"] as? JsonPrimitive)?.content == "true",
                registryReportsTaskTested =
                    ((record["task_conformance"] as? JsonObject)?.get("passed") as? JsonPrimitive)?.content == "true",
            )
        }
    }

    private companion object {
        const val MAX_RESULTS = 20
        const val MAX_REGISTRY_BYTES = 384 * 1024
    }
}

/**
 * Federates local agents with public registry adapters. Discovery is strictly read-only: it never
 * adds connections, routes tasks, or fetches third-party Agent Cards. Results are deduplicated by
 * normalized card URL, retaining local connection approval if a registry also lists the agent.
 */
internal class AgentDirectory(
    private val sources: List<AgentDirectorySource> = listOf(LocalAgentDirectorySource, PublicA2aRegistrySource()),
) {
    suspend fun search(rawQuery: String): AgentDirectoryResults {
        val query = rawQuery.trim().take(MAX_QUERY_CHARS)
        if (query.length < 2) return AgentDirectoryResults(emptyList(), emptyList())
        val found = linkedMapOf<String, AgentDirectoryEntry>()
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
                    connectedProviderId = existing?.connectedProviderId ?: entry.connectedProviderId,
                )
            }
        }
        return AgentDirectoryResults(
            entries = found.values.sortedWith(
                compareByDescending<AgentDirectoryEntry> { it.connectedProviderId != null }
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

/** Explicitly reject registry-supplied localhost, IP literals, credentials and disguised origins. */
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
