package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.mcp.McpClient
import com.hereliesaz.geministrator.mcp.McpConsentDecision
import com.hereliesaz.geministrator.mcp.McpConsentGate
import com.hereliesaz.geministrator.mcp.McpConsentRequest
import com.hereliesaz.geministrator.mcp.McpHost
import com.hereliesaz.geministrator.mcp.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A pass over what is already on the device. Sends nothing anywhere. */
fun interface LocalResearchSource {
    suspend fun leads(objective: String): List<ResearchLead>
}

/** A server that cannot be asked right now, which is normal for an optional directory. */
class ResearchUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One outside lookup. Sends [query] (already minimal) and returns leads; never executes what it finds. */
fun interface ResearchLookup {
    suspend fun search(query: String): List<ResearchLead>
}

/** A lookup the keeper is asked about before it runs. */
class ResearchCandidate(
    val source: ResearchSource,
    val serverId: String,
    val serverName: String,
    val serverLocation: String,
    val toolName: String,
    val lookup: ResearchLookup,
)

/**
 * The queen's one research phase before she drafts a plan (docs/Keepers-Room.md). A deterministic local pass, then at
 * most [ResearchBudget.maxLookups] outside lookups, each asked of the keeper first through the MCP consent gate with
 * the exact words shown. No model is called, so there is nothing to price. Every failure ends in "plan without it":
 * [run] never throws except for cancellation, and the [ResearchRecord] says what was and was not consulted.
 */
class ResearchStep(
    private val local: List<Pair<ResearchSource, LocalResearchSource>> = emptyList(),
    private val candidates: List<ResearchCandidate> = emptyList(),
    private val consent: McpConsentGate = McpConsentGate.DenyUnasked,
    private val budget: ResearchBudget = ResearchBudget(),
) {
    /** True when there is something to consult, for [researchWanted]. */
    val lookupConfigured: Boolean get() = candidates.isNotEmpty()

    suspend fun run(objective: String, context: ResearchContext, runKey: String): ResearchOutcome {
        if (!researchWanted(objective, context.copy(lookupConfigured = context.lookupConfigured || lookupConfigured))) return ResearchOutcome.None
        val leads = mutableListOf<ResearchLead>()
        val consulted = mutableListOf<ResearchSource>()
        val skipped = mutableListOf<ResearchSkip>()
        var sent: String? = null
        // The clock never runs while the keeper decides: the local pass and each lookup have their own allowance.
        try {
            withTimeout(budget.wallClockMillis) {
                for ((source, pass) in local) {
                    try {
                        leads += pass.leads(objective)
                        consulted += source
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        skipped += ResearchSkip(source, ResearchSkipReason.Failed)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            val reached = consulted.toSet() + skipped.map { it.source }
            local.map { it.first }.filter { it !in reached }.forEach { skipped += ResearchSkip(it, ResearchSkipReason.TimedOut) }
        }
        var lookups = 0
        for (candidate in candidates) {
            if (lookups >= budget.maxLookups) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.Budget)
                continue
            }
            val query = ResearchQuery.minimal(objective)
            if (query == null) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.NoQuery)
                continue
            }
            val decision = consent.decide(
                McpConsentRequest(
                    serverId = candidate.serverId,
                    serverName = candidate.serverName,
                    serverLocation = candidate.serverLocation,
                    toolName = candidate.toolName,
                    argumentsPreview = "Look for agents by these words only: $query",
                    runKey = runKey,
                ),
            )
            if (decision == McpConsentDecision.Deny) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.Declined)
                continue
            }
            lookups++
            try {
                leads += withTimeout(budget.wallClockMillis) { candidate.lookup.search(query) }
                consulted += candidate.source
                sent = query
            } catch (_: TimeoutCancellationException) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.TimedOut)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ResearchUnavailable) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.Unavailable)
            } catch (_: Exception) {
                skipped += ResearchSkip(candidate.source, ResearchSkipReason.Failed)
            }
        }
        val kept = leads.toList()
        val offered = kept.toAvailableTools(budget).size
        return ResearchOutcome(kept, ResearchRecord(consulted, skipped, offered, sent))
    }
}

/** Tools of servers the keeper already connected. Listing is not consent (docs/architecture/MCP_HOST.md). */
class ConnectedServersResearch(private val host: McpHost, private val maxServers: Int = 6) : LocalResearchSource {
    override suspend fun leads(objective: String): List<ResearchLead> =
        host.servers.value.take(maxServers).mapNotNull { server ->
            val tools = host.check(server.id).getOrNull() ?: return@mapNotNull null
            if (tools.isEmpty()) return@mapNotNull null
            ResearchLead(
                name = server.name,
                source = ResearchSource.ConnectedServer,
                trust = "connected by the keeper",
                why = "tools: " + tools.take(12).joinToString(", ") { ResearchText.clean(it.name, 40) },
                connected = true,
            )
        }
}

/** The app's own control interface, when the keeper turned it on: what it can show, by name. */
class AppBridgeResearch(private val enabled: () -> Boolean, private val capabilities: List<String>) : LocalResearchSource {
    override suspend fun leads(objective: String): List<ResearchLead> {
        if (!enabled() || capabilities.isEmpty()) return emptyList()
        return listOf(
            ResearchLead(
                name = "This app's control interface",
                source = ResearchSource.AppBridge,
                trust = "this app, turned on by the keeper",
                why = "shows live " + capabilities.take(12).joinToString(", "),
                connected = true,
            ),
        )
    }
}

/** A linked repository's own README and docs, by name, from [docNames] (the existing retrieval). */
class RepositoryDocsResearch(private val docNames: suspend () -> List<String>) : LocalResearchSource {
    override suspend fun leads(objective: String): List<ResearchLead> {
        val names = docNames().take(8)
        if (names.isEmpty()) return emptyList()
        return listOf(
            ResearchLead(
                name = "Linked repository docs",
                source = ResearchSource.Repository,
                trust = "the keeper's own repository",
                why = names.joinToString(", ") { ResearchText.clean(it, 60) },
                connected = true,
            ),
        )
    }
}

/**
 * SearchA2A's read-only MCP server (`search_agents`). Off until its operator turns it on, so any failure to connect is
 * "unavailable". Names and descriptions in its answer are publishers' words: only the id-free name and numeric level
 * are used, cleaned, and the lead is never marked connected.
 */
class SearchA2aMcpLookup(
    private val openTransport: () -> McpTransport,
    private val limit: Int = 5,
    private val requestTimeoutMillis: Long = 15_000,
) : ResearchLookup {
    override suspend fun search(query: String): List<ResearchLead> {
        val client = McpClient(openTransport(), requestTimeoutMillis = requestTimeoutMillis, probeTimeoutMillis = 5_000)
        try {
            val result = try {
                client.callTool("search_agents", buildJsonObject { put("query", query); put("limit", limit) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw ResearchUnavailable("The SearchA2A MCP server did not answer", failure)
            }
            if (result.isError) throw ResearchUnavailable("The SearchA2A MCP server refused the search")
            val rows = ((result.structuredContent as? JsonObject)?.get("results") as? JsonArray) ?: return emptyList()
            return rows.take(limit).mapNotNull { row ->
                val item = row as? JsonObject ?: return@mapNotNull null
                val untrusted = item["untrusted"] as? JsonObject
                val name = (untrusted?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val level = (item["level"] as? JsonPrimitive)?.intOrNull
                ResearchLead(
                    name = ResearchText.clean(name, 60),
                    source = ResearchSource.SearchA2A,
                    trust = (level?.let { "directory level $it of 3" } ?: "directory level unknown") + ", nobody tested what it does",
                    why = "an agent the directory lists for these words; connect it in Connections to use it",
                    connected = false,
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        const val ENDPOINT = "https://searcha2a-api.hereliesaz.workers.dev/mcp"
    }
}
