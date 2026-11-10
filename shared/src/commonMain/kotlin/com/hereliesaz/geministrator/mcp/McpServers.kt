package com.hereliesaz.geministrator.mcp

import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * One MCP server the keeper connected in the Keeper's room (Connections › MCP servers). Exactly
 * one of [url] (Streamable HTTP) and [command] (a local process, desktop only) is set. A bearer
 * token, when there is one, lives in the platform credential store under [id], never here.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String? = null,
    val command: List<String> = emptyList(),
    val hasToken: Boolean = false,
    /** The keeper allowed every tool call to this server without asking each run. */
    val alwaysAllowed: Boolean = false,
    /** The Azphalt package that registered it, when one did (not yet: see MCP_HOST.md). */
    val packageId: String? = null,
) {
    val transportKind: McpTransportKind
        get() = if (url != null) McpTransportKind.StreamableHttp else McpTransportKind.Stdio

    /** What the keeper sees under the name: the address, or the command. */
    val location: String
        get() = url ?: command.joinToString(" ")

    fun validate() {
        require(ID.matches(id)) { "Invalid MCP server id" }
        require(name.isNotBlank() && name.length <= 80) { "Name the MCP server (at most 80 characters)" }
        require((url == null) != command.isEmpty()) { "An MCP server has either an address or a command" }
        url?.let { McpStreamableHttpTransport.requireUsableEndpoint(it) }
        if (command.isNotEmpty()) McpCommandLine.requireUsable(command)
    }

    companion object {
        private val ID = Regex("mcp-[a-z0-9]{8,32}")

        fun newId(random: Random = Random.Default): String =
            "mcp-" + (1..16).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")
    }
}

/** The configured servers, as JSON in the platform's general settings (no secrets). */
class McpServerStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(McpServerConfig.serializer())

    fun read(): List<McpServerConfig> {
        return (settings.readQuarantining(storageKey) { json.decodeFromString(serializer, it) } ?: emptyList())
            .filter { runCatching { it.validate() }.isSuccess }
    }

    fun write(servers: List<McpServerConfig>) {
        settings.refuseIfUnreadable(storageKey) { json.decodeFromString(serializer, it) }
        settings.putString(storageKey, json.encodeToString(serializer, servers))
    }

    companion object {
        const val DEFAULT_STORAGE_KEY = "aive.mcp.servers.v1"
    }
}

/**
 * MCP bearer tokens, stored like the platform's other connection secrets: the Android Keystore,
 * the desktop keychain (macOS Keychain, Secret Service, Windows DPAPI) or the web credential
 * vault. Keyed by [McpServerConfig.id].
 */
interface McpCredentialVault {
    suspend fun read(serverId: String): String?

    suspend fun write(serverId: String, token: String)

    suspend fun clear(serverId: String)
}

/** Holds tokens in memory only: tests, and platforms with no protected store. */
class InMemoryMcpCredentialVault : McpCredentialVault {
    private val tokens = mutableMapOf<String, String>()

    override suspend fun read(serverId: String): String? = tokens[serverId]

    override suspend fun write(serverId: String, token: String) {
        tokens[serverId] = token
    }

    override suspend fun clear(serverId: String) {
        tokens.remove(serverId)
    }
}

/**
 * A local server's command line, split into a fixed argument list the way a shell would quote it,
 * but with no shell: no variables, globs, pipes or redirections are ever interpreted.
 */
object McpCommandLine {
    fun split(line: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                quote != null && c == quote -> quote = null
                quote == '"' && c == '\\' && index + 1 < line.length && line[index + 1] in "\"\\" -> {
                    current.append(line[index + 1])
                    index++
                }
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    started = true
                }
                c.isWhitespace() -> if (started) {
                    arguments += current.toString()
                    current.clear()
                    started = false
                }
                else -> {
                    current.append(c)
                    started = true
                }
            }
            if (quote != null || !c.isWhitespace()) started = true
            index++
        }
        require(quote == null) { "The command has an unclosed quote" }
        if (started) arguments += current.toString()
        return arguments
    }

    fun requireUsable(command: List<String>) {
        require(command.isNotEmpty() && command.first().isNotBlank()) { "Give the command that starts the MCP server" }
        require(command.size <= 64 && command.all { it.length <= 4_096 }) { "The command is too long" }
        require(command.none { argument -> argument.any { it == '\u0000' || it == '\n' || it == '\r' } }) {
            "The command may not contain line breaks"
        }
    }
}
