package com.hereliesaz.geministrator

import java.util.prefs.Preferences

internal class DesktopProviderCredentialStore {
    // Fallback to java.util.prefs.Preferences when OS keychain is unavailable.
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun read(providerId: String): String? =
        readFromKeychain(providerId)
            ?: fallback.get(providerId, null)?.trim()?.takeIf(String::isNotEmpty)

    fun readAll(): Map<String, String> = buildMap {
        ProviderCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(providerId: String, apiKey: String) {
        require(ProviderCatalog.entry(providerId) != null) { "Unknown provider $providerId" }
        val clean = apiKey.trim()
        require(clean.isNotEmpty()) { "API key is required" }
        if (!writeToKeychain(providerId, clean)) {
            fallback.put(providerId, clean)
            fallback.flush()
        } else {
            // Remove from plaintext fallback if keychain write succeeded.
            fallback.remove(providerId)
            fallback.flush()
        }
    }

    fun clear(providerId: String) {
        deleteFromKeychain(providerId)
        fallback.remove(providerId)
        fallback.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/provider-credentials"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive"

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(providerId: String): String? = runCatching {
            when {
                os.contains("mac") -> {
                    val result = ProcessBuilder(
                        "security", "find-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", providerId,
                        "-w",
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                os.contains("win") -> {
                    // PowerShell cmdkey + Windows Credential Manager
                    val result = ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "[System.Net.NetworkCredential]::new('', " +
                            "(Get-StoredCredential -Target '$KEYCHAIN_SERVICE/$providerId').Password" +
                            ").Password",
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    val result = ProcessBuilder(
                        "secret-tool", "lookup",
                        "service", KEYCHAIN_SERVICE,
                        "account", providerId,
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                else -> null
            }
        }.getOrNull()

        fun writeToKeychain(providerId: String, apiKey: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    // Delete first to avoid duplicate-item errors.
                    ProcessBuilder(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", providerId,
                    ).start().waitFor()
                    val result = ProcessBuilder(
                        "security", "add-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", providerId,
                        "-w", apiKey,
                        "-U",
                    ).start()
                    result.waitFor() == 0
                }
                os.contains("win") -> {
                    val result = ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "cmdkey /generic:'$KEYCHAIN_SERVICE/$providerId' /user:'$providerId' /pass:'$apiKey'",
                    ).start()
                    result.waitFor() == 0
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    val proc = ProcessBuilder(
                        "secret-tool", "store",
                        "--label", "Haive API key ($providerId)",
                        "service", KEYCHAIN_SERVICE,
                        "account", providerId,
                    ).start()
                    proc.outputStream.bufferedWriter().use { it.write(apiKey) }
                    proc.waitFor() == 0
                }
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain(providerId: String) = runCatching {
            when {
                os.contains("mac") -> {
                    ProcessBuilder(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", providerId,
                    ).start().waitFor()
                }
                os.contains("win") -> {
                    ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "cmdkey /delete:'$KEYCHAIN_SERVICE/$providerId'",
                    ).start().waitFor()
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    ProcessBuilder(
                        "secret-tool", "clear",
                        "service", KEYCHAIN_SERVICE,
                        "account", providerId,
                    ).start().waitFor()
                }
            }
        }
    }
}
