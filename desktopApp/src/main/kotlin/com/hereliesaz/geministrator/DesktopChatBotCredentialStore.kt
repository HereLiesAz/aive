package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.chat.ChatPlatform
import com.hereliesaz.geministrator.chat.ChatSecretStore
import java.util.prefs.Preferences

/**
 * The chat bots' keys (docs/Chat-Bots.md), kept where the app keeps its other keys: the macOS Keychain, the Secret Service
 * (`secret-tool`) on Linux, and DPAPI ciphertext on Windows. There is no plaintext fallback: with no protected store the key
 * is refused, never written to a settings file. The key is never logged or printed.
 */
internal class DesktopChatBotCredentialStore : ChatSecretStore {
    // Preferences hold only Windows DPAPI ciphertext.
    private val prefs = Preferences.userRoot().node(PREFERENCES_NODE)

    override fun read(platform: ChatPlatform): String? = readFromKeychain(platform.id) ?: readWindowsDpapi(platform.id)

    override fun write(platform: ChatPlatform, secret: String): Boolean {
        val clean = secret.trim()
        if (clean.isEmpty()) return false
        val ok = if (os.contains("win")) writeWindowsDpapi(platform.id, clean) else writeToKeychain(platform.id, clean)
        return ok
    }

    override fun clear(platform: ChatPlatform) {
        deleteFromKeychain(platform.id)
        prefs.remove(DPAPI_PREFIX + platform.id)
        prefs.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/aive/chat-bots"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.aive.chat-bots"
        const val DPAPI_PREFIX = "__dpapi__:"

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(account: String): String? {
            val command = when {
                os.contains("mac") -> listOf("security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account, "-w")
                os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                    listOf("secret-tool", "lookup", "service", KEYCHAIN_SERVICE, "account", account)
                else -> return null
            }
            return CredentialTool.run(command)?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }

        fun writeToKeychain(account: String, value: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    CredentialTool.run(listOf("security", "delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account))
                    MacKeychain.addGenericPassword(KEYCHAIN_SERVICE, account, value)
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf("secret-tool", "store", "--label", "Aive chat bot ($account)", "service", KEYCHAIN_SERVICE, "account", account),
                    stdin = value,
                )?.ok == true
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain(account: String) {
            runCatching {
                when {
                    os.contains("mac") -> CredentialTool.run(listOf("security", "delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account))
                    os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                        CredentialTool.run(listOf("secret-tool", "clear", "service", KEYCHAIN_SERVICE, "account", account))
                    else -> null
                }
            }
        }

        fun readWindowsDpapi(account: String): String? {
            if (!os.contains("win")) return null
            val encoded = Preferences.userRoot().node(PREFERENCES_NODE).get(DPAPI_PREFIX + account, null)?.trim()?.takeIf(String::isNotEmpty)
                ?: return null
            val script = """
                ${'$'}cipher = [Console]::In.ReadToEnd().Trim()
                ${'$'}bytes = [Convert]::FromBase64String(${'$'}cipher)
                ${'$'}plain = [Security.Cryptography.ProtectedData]::Unprotect(
                    ${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser
                )
                [Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}plain))
            """.trimIndent()
            return CredentialTool.run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script), stdin = encoded)
                ?.takeIf { it.ok }?.output?.takeIf(String::isNotBlank)
        }

        fun writeWindowsDpapi(account: String, value: String): Boolean {
            if (!os.contains("win")) return false
            return runCatching {
                val script = """
                    ${'$'}plain = [Console]::In.ReadToEnd()
                    ${'$'}bytes = [Text.Encoding]::UTF8.GetBytes(${'$'}plain)
                    ${'$'}cipher = [Security.Cryptography.ProtectedData]::Protect(
                        ${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser
                    )
                    [Console]::Out.Write([Convert]::ToBase64String(${'$'}cipher))
                """.trimIndent()
                val encoded = CredentialTool.run(
                    listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
                    stdin = value,
                )?.takeIf { it.ok }?.output?.trim().orEmpty()
                if (encoded.isNotEmpty()) {
                    val node = Preferences.userRoot().node(PREFERENCES_NODE)
                    node.put(DPAPI_PREFIX + account, encoded)
                    node.flush()
                }
                encoded.isNotEmpty()
            }.getOrElse { false }
        }
    }
}
