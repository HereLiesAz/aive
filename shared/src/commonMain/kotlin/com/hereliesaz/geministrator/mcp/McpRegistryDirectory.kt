package com.hereliesaz.geministrator.mcp

import com.hereliesaz.geministrator.a2a.sharedExternalDiscoveryClient
import com.hereliesaz.geministrator.net.boundedText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A read-only catalog lead. Neither an installed MCP server nor a verified identity. */
internal data class McpRegistryLead(
    val name: String,
    val description: String,
    /** Publisher-controlled, opaque version. Never rewritten to SemVer or ANS format. */
    val version: String,
    val url: String,
)

/**
 * Search the Store's sanitized MCP Registry v0.1 adapter on explicit keeper request.
 * The catalog has no authority to connect servers or authorize tool calls.
 * Existing [McpHost.addHttpServer] and its consent gate remain the only execution path.
 */
internal class McpRegistryDirectory(
    private val http: HttpClient = sharedExternalDiscoveryClient,
) {
    suspend fun search(rawQuery: String): List<McpRegistryLead> {
        val query = rawQuery.trim()
        require(query.length in 3..120) { "Enter at least three characters to search MCP servers" }
        val response = http.get("https://azphalt.store/api/mcp/servers") {
            url { parameters.append("search", query) }
        }
        if (response.status != HttpStatusCode.OK) error("MCP directory unavailable")
        val document = Json.parseToJsonElement(response.boundedText(512_000)) as? JsonObject
            ?: error("Invalid MCP directory")
        if (document["schema"].primitiveText() != "1" ||
            document["source"].primitiveText() != "mcp-registry-v0.1") {
            error("Invalid MCP directory")
        }
        val servers = document["servers"] as? JsonArray ?: error("Invalid MCP directory")
        return servers.take(30).mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            if (row["kind"].primitiveText() != "mcp-server" ||
                row["verification"].primitiveText() != "registry_metadata_only" ||
                row["installable"].primitiveText() != "false") return@mapNotNull null
            val remote = (row["remotes"] as? JsonArray).orEmpty().firstNotNullOfOrNull { entry ->
                val endpoint = entry as? JsonObject ?: return@firstNotNullOfOrNull null
                if (endpoint["type"].primitiveText() != "streamable-http") return@firstNotNullOfOrNull null
                publicRegistryMcpEndpoint(endpoint["url"].primitiveText().orEmpty())
            } ?: return@mapNotNull null
            val name = row["title"].primitiveText()?.trim()?.take(80)?.ifBlank { null }
                ?: row["name"].primitiveText()?.trim()?.take(80)
                ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            McpRegistryLead(
                name = name,
                description = row["description"].primitiveText().orEmpty().take(240),
                version = row["version"].primitiveText().orEmpty().take(160),
                url = remote,
            )
        }.distinctBy { it.url }
    }

    private fun kotlinx.serialization.json.JsonElement?.primitiveText(): String? =
        (this as? JsonPrimitive)?.content
}

/**
 * Only public, credential-free HTTPS leads are accepted from remote registries.
 * This is lexical validation, not DNS identity verification or a network connection.
 */
internal fun publicRegistryMcpEndpoint(raw: String): String? {
    if (raw.length > 2048 || !raw.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(raw) }.getOrNull() ?: return null
    val host = url.host.lowercase()
    val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    if (url.protocol != URLProtocol.HTTPS || url.user != null || url.password != null ||
        url.port != 443 || url.parameters.names().isNotEmpty() || url.fragment.isNotEmpty() ||
        !host.contains('.') || host.split('.').any { !label.matches(it) } ||
        host.substringAfterLast('.').all(Char::isDigit) ||
        host.endsWith(".local") || host.endsWith(".internal") ||
        host.endsWith(".localhost")
    ) return null
    return raw
}
