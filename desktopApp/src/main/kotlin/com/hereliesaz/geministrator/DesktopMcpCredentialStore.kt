package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.mcp.McpCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MCP server bearer tokens in the desktop's protected store, as the other connection secrets:
 * macOS Keychain, Linux Secret Service, or a per-user DPAPI file on Windows. One entry per server
 * id; there is no plaintext fallback, so saving fails where no protected store exists.
 */
internal class DesktopMcpCredentialStore : McpCredentialVault {
    override suspend fun read(serverId: String): String? = withContext(Dispatchers.IO) {
        if (!CredentialTool.isSafeAccountId(serverId)) return@withContext null
        val result = when {
            os.contains("mac") -> CredentialTool.run(listOf("security", "find-generic-password", "-s", SERVICE, "-a", serverId, "-w"))
            os.contains("win") -> windowsFile(serverId).takeIf(File::isFile)?.let { file ->
                CredentialTool.run(DesktopDistributedComputeCredentialStore.windowsPowerShell(DesktopDistributedComputeCredentialStore.WINDOWS_READ_SCRIPT, file))
            }
            isUnixLike -> CredentialTool.run(listOf("secret-tool", "lookup", "service", SERVICE, "account", serverId))
            else -> null
        }
        result?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
    }

    override suspend fun write(serverId: String, token: String) = withContext(Dispatchers.IO) {
        require(CredentialTool.isSafeAccountId(serverId)) { "Unsupported MCP server id" }
        val clean = token.trim()
        require(clean.isNotEmpty()) { "The token is empty" }
        val stored = when {
            os.contains("mac") -> {
                CredentialTool.run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", serverId))
                MacKeychain.addGenericPassword(SERVICE, serverId, clean)
            }
            os.contains("win") -> CredentialTool.run(
                DesktopDistributedComputeCredentialStore.windowsPowerShell(
                    DesktopDistributedComputeCredentialStore.WINDOWS_WRITE_SCRIPT,
                    windowsFile(serverId),
                ),
                stdin = clean,
            )?.ok == true
            isUnixLike -> CredentialTool.run(
                listOf("secret-tool", "store", "--label", "Aive MCP server token ($serverId)", "service", SERVICE, "account", serverId),
                stdin = clean,
            )?.ok == true
            else -> false
        }
        require(stored) { "No protected credential store is available on this computer for the token" }
    }

    override suspend fun clear(serverId: String) = withContext(Dispatchers.IO) {
        if (!CredentialTool.isSafeAccountId(serverId)) return@withContext
        when {
            os.contains("mac") -> CredentialTool.run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", serverId))
            os.contains("win") -> {
                windowsFile(serverId).delete()
                null
            }
            isUnixLike -> CredentialTool.run(listOf("secret-tool", "clear", "service", SERVICE, "account", serverId))
            else -> null
        }
        Unit
    }

    private companion object {
        const val SERVICE = "com.hereliesaz.aive.mcp"

        val os: String get() = System.getProperty("os.name", "").lowercase()
        val isUnixLike: Boolean get() = os.contains("nux") || os.contains("nix") || os.contains("bsd")

        fun windowsFile(serverId: String): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: File(System.getProperty("user.home"), "AppData/Local").path
            return File(File(File(base, "Aive"), "mcp"), "$serverId.dpapi")
        }
    }
}
