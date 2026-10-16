package com.hereliesaz.geministrator

import java.util.prefs.Preferences

internal class DesktopDistributedComputeCredentialStore {
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun readToken(): String? =
        System.getenv("AIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getenv("HAIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)
            ?: readFromKeychain()
            ?: fallback.get(ACCOUNT, null)?.trim()?.takeIf(String::isNotEmpty)

    fun writeToken(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Relay token is required" }
        if (!writeToKeychain(clean)) {
            fallback.put(ACCOUNT, clean)
            fallback.flush()
        } else {
            fallback.remove(ACCOUNT)
            fallback.flush()
        }
    }

    fun clear() {
        deleteFromKeychain()
        fallback.remove(ACCOUNT)
        fallback.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/distributed-compute"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive.distributed-compute"
        const val ACCOUNT = "relay-token"

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(): String? = runCatching {
            when {
                os.contains("mac") -> {
                    val result = ProcessBuilder(
                        "security", "find-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", ACCOUNT,
                        "-w",
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                os.contains("win") -> {
                    val result = ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "[System.Net.NetworkCredential]::new('', " +
                            "(Get-StoredCredential -Target '$KEYCHAIN_SERVICE/$ACCOUNT').Password" +
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
                        "account", ACCOUNT,
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                else -> null
            }
        }.getOrNull()

        fun writeToKeychain(token: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    ProcessBuilder(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", ACCOUNT,
                    ).start().waitFor()
                    ProcessBuilder(
                        "security", "add-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", ACCOUNT,
                        "-w", token,
                        "-U",
                    ).start().waitFor() == 0
                }
                os.contains("win") -> {
                    ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "cmdkey /generic:'$KEYCHAIN_SERVICE/$ACCOUNT' /user:'$ACCOUNT' /pass:'$token'",
                    ).start().waitFor() == 0
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    val process = ProcessBuilder(
                        "secret-tool", "store",
                        "--label", "Aive distributed compute relay",
                        "service", KEYCHAIN_SERVICE,
                        "account", ACCOUNT,
                    ).start()
                    process.outputStream.bufferedWriter().use { it.write(token) }
                    process.waitFor() == 0
                }
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain() = runCatching {
            when {
                os.contains("mac") -> ProcessBuilder(
                    "security", "delete-generic-password",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", ACCOUNT,
                ).start().waitFor()
                os.contains("win") -> ProcessBuilder(
                    "powershell", "-NonInteractive", "-Command",
                    "cmdkey /delete:'$KEYCHAIN_SERVICE/$ACCOUNT'",
                ).start().waitFor()
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> ProcessBuilder(
                    "secret-tool", "clear",
                    "service", KEYCHAIN_SERVICE,
                    "account", ACCOUNT,
                ).start().waitFor()
            }
        }
    }
}
