package com.hereliesaz.geministrator

import java.util.prefs.Preferences

internal class DesktopRepositoryCredentialStore {
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun read(serviceId: String): String? =
        readFromKeychain(serviceId)
            ?: fallback.get(serviceId, null)?.trim()?.takeIf(String::isNotEmpty)

    fun readAll(): Map<String, String> = buildMap {
        RepositoryServiceCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(serviceId: String, credential: String) {
        require(RepositoryServiceCatalog.entry(serviceId) != null) { "Unknown repository service $serviceId" }
        val clean = credential.trim()
        require(clean.isNotEmpty()) { "Repository credential is required" }
        if (!writeToKeychain(serviceId, clean)) {
            fallback.put(serviceId, clean)
            fallback.flush()
        } else {
            fallback.remove(serviceId)
            fallback.flush()
        }
    }

    fun clear(serviceId: String) {
        deleteFromKeychain(serviceId)
        fallback.remove(serviceId)
        fallback.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/repository-credentials"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive.repository"

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(serviceId: String): String? = runCatching {
            when {
                os.contains("mac") -> {
                    val result = ProcessBuilder(
                        "security", "find-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", serviceId,
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
                            "(Get-StoredCredential -Target '$KEYCHAIN_SERVICE/$serviceId').Password" +
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
                        "account", serviceId,
                    ).start()
                    val output = result.inputStream.bufferedReader().readText().trim()
                    result.waitFor()
                    output.takeIf { it.isNotEmpty() && result.exitValue() == 0 }
                }
                else -> null
            }
        }.getOrNull()

        fun writeToKeychain(serviceId: String, credential: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    ProcessBuilder(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", serviceId,
                    ).start().waitFor()
                    val result = ProcessBuilder(
                        "security", "add-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", serviceId,
                        "-w", credential,
                        "-U",
                    ).start()
                    result.waitFor() == 0
                }
                os.contains("win") -> {
                    val result = ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "cmdkey /generic:'$KEYCHAIN_SERVICE/$serviceId' /user:'$serviceId' /pass:'$credential'",
                    ).start()
                    result.waitFor() == 0
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    val proc = ProcessBuilder(
                        "secret-tool", "store",
                        "--label", "Haive repository credential ($serviceId)",
                        "service", KEYCHAIN_SERVICE,
                        "account", serviceId,
                    ).start()
                    proc.outputStream.bufferedWriter().use { it.write(credential) }
                    proc.waitFor() == 0
                }
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain(serviceId: String) = runCatching {
            when {
                os.contains("mac") -> {
                    ProcessBuilder(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", serviceId,
                    ).start().waitFor()
                }
                os.contains("win") -> {
                    ProcessBuilder(
                        "powershell", "-NonInteractive", "-Command",
                        "cmdkey /delete:'$KEYCHAIN_SERVICE/$serviceId'",
                    ).start().waitFor()
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> {
                    ProcessBuilder(
                        "secret-tool", "clear",
                        "service", KEYCHAIN_SERVICE,
                        "account", serviceId,
                    ).start().waitFor()
                }
            }
        }
    }
}
