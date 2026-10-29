package com.hereliesaz.geministrator

import java.util.prefs.Preferences

internal class DesktopProviderCredentialStore {
    // Preferences are used only for Windows DPAPI ciphertext and one-time migration of legacy
    // plaintext values written by older builds.
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun read(providerId: String): String? {
        if (!CredentialTool.isSafeAccountId(providerId)) return null
        val secure = readFromKeychain(providerId)
            ?: readWindowsDpapi(providerId)
        if (secure != null) return secure

        val legacy = fallback.get(providerId, null)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        fallback.remove(providerId)
        fallback.flush()
        // Migrate plaintext only when a protected backend is available; otherwise force the user
        // to reconnect rather than continuing to retain a bearer token in ordinary preferences.
        return if (writeSecure(providerId, legacy)) legacy else null
    }

    fun readAll(): Map<String, String> = buildMap {
        // Providers the app no longer supports leave no credential behind.
        ProviderCatalog.RETIRED_PROVIDER_IDS.forEach(::clear)
        ProviderCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(providerId: String, apiKey: String) {
        require(ProviderCatalog.entry(providerId) != null) { "Unknown provider $providerId" }
        require(CredentialTool.isSafeAccountId(providerId)) { "Unsupported provider id $providerId" }
        val clean = apiKey.trim()
        require(clean.isNotEmpty()) { "API key is required" }
        require(writeSecure(providerId, clean)) {
            "No protected credential backend is available for this desktop platform"
        }
        fallback.remove(providerId)
        fallback.flush()
    }

    fun clear(providerId: String) {
        // An id that could not have been stored is never handed to a credential tool.
        if (CredentialTool.isSafeAccountId(providerId)) deleteFromKeychain(providerId)
        if (os.contains("win")) fallback.remove(windowsDpapiKey(providerId))
        fallback.remove(providerId)
        fallback.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/provider-credentials"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive"
        const val DPAPI_PREFIX = "__dpapi__:"

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(providerId: String): String? {
            val command = when {
                os.contains("mac") -> listOf(
                    "security", "find-generic-password",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", providerId,
                    "-w",
                )
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> listOf(
                    "secret-tool", "lookup",
                    "service", KEYCHAIN_SERVICE,
                    "account", providerId,
                )
                else -> return null
            }
            return CredentialTool.run(command)?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }

        fun writeToKeychain(providerId: String, apiKey: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    // Delete first to avoid duplicate-item errors.
                    CredentialTool.run(
                        listOf(
                            "security", "delete-generic-password",
                            "-s", KEYCHAIN_SERVICE,
                            "-a", providerId,
                        ),
                    )
                    MacKeychain.addGenericPassword(KEYCHAIN_SERVICE, providerId, apiKey)
                }
                os.contains("win") -> false
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "store",
                        "--label", "Haive API key ($providerId)",
                        "service", KEYCHAIN_SERVICE,
                        "account", providerId,
                    ),
                    stdin = apiKey,
                )?.ok == true
                else -> false
            }
        }.getOrElse { false }

        fun writeSecure(providerId: String, apiKey: String): Boolean =
            if (os.contains("win")) writeWindowsDpapi(providerId, apiKey) else writeToKeychain(providerId, apiKey)

        fun windowsDpapiKey(providerId: String): String = DPAPI_PREFIX + providerId

        fun readWindowsDpapi(providerId: String): String? {
            if (!os.contains("win")) return null
            val encoded = Preferences.userRoot()
                .node(PREFERENCES_NODE)
                .get(windowsDpapiKey(providerId), null)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return null
            val script = """
                ${'$'}cipher = [Console]::In.ReadToEnd().Trim()
                ${'$'}bytes = [Convert]::FromBase64String(${'$'}cipher)
                ${'$'}plain = [Security.Cryptography.ProtectedData]::Unprotect(
                    ${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser
                )
                [Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}plain))
            """.trimIndent()
            return CredentialTool.run(
                listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
                stdin = encoded,
            )?.takeIf { it.ok }?.output?.takeIf(String::isNotBlank)
        }

        fun writeWindowsDpapi(providerId: String, apiKey: String): Boolean {
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
                    stdin = apiKey,
                )?.takeIf { it.ok }?.output?.trim().orEmpty()
                val ok = encoded.isNotEmpty()
                if (ok) {
                    val prefs = Preferences.userRoot().node(PREFERENCES_NODE)
                    prefs.put(windowsDpapiKey(providerId), encoded)
                    prefs.flush()
                }
                ok
            }.getOrElse { false }
        }

        fun deleteFromKeychain(providerId: String) = runCatching {
            when {
                os.contains("mac") -> CredentialTool.run(
                    listOf(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", providerId,
                    ),
                )
                // Earlier builds wrote a Credential Manager entry; `cmdkey` runs directly, never through a shell.
                os.contains("win") -> CredentialTool.run(
                    CredentialTool.windowsCredentialDeleteCommand(KEYCHAIN_SERVICE, providerId),
                )
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "clear",
                        "service", KEYCHAIN_SERVICE,
                        "account", providerId,
                    ),
                )
                else -> null
            }
        }
    }
}
