package com.hereliesaz.geministrator

import java.util.prefs.Preferences

internal class DesktopRepositoryCredentialStore {
    // Preferences are used only for Windows DPAPI ciphertext and one-time migration of legacy
    // plaintext values written by older builds.
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun read(serviceId: String): String? {
        val secure = readFromKeychain(serviceId)
            ?: readWindowsDpapi(serviceId)
        if (secure != null) return secure

        val legacy = fallback.get(serviceId, null)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        fallback.remove(serviceId)
        fallback.flush()
        // Migrate plaintext only when a protected backend is available; otherwise force the user
        // to reconnect rather than continuing to retain a bearer token in ordinary preferences.
        return if (writeSecure(serviceId, legacy)) legacy else null
    }

    fun readAll(): Map<String, String> = buildMap {
        RepositoryServiceCatalog.entries.forEach { entry ->
            read(entry.id)?.let { put(entry.id, it) }
        }
    }

    fun write(serviceId: String, credential: String) {
        require(RepositoryServiceCatalog.entry(serviceId) != null) { "Unknown repository service $serviceId" }
        val clean = credential.trim()
        require(clean.isNotEmpty()) { "Repository credential is required" }
        require(writeSecure(serviceId, clean)) {
            "No protected credential backend is available for this desktop platform"
        }
        fallback.remove(serviceId)
        fallback.flush()
    }

    fun clear(serviceId: String) {
        deleteFromKeychain(serviceId)
        if (os.contains("win")) fallback.remove(windowsDpapiKey(serviceId))
        fallback.remove(serviceId)
        fallback.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/repository-credentials"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive.repository"
        const val DPAPI_PREFIX = "__dpapi__:"

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
                os.contains("win") -> null
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
                    // Delete first to avoid duplicate-item errors.
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
                os.contains("win") -> false
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

        fun writeSecure(serviceId: String, credential: String): Boolean =
            if (os.contains("win")) writeWindowsDpapi(serviceId, credential) else writeToKeychain(serviceId, credential)

        fun windowsDpapiKey(serviceId: String): String = DPAPI_PREFIX + serviceId

        fun readWindowsDpapi(serviceId: String): String? {
            if (!os.contains("win")) return null
            val encoded = Preferences.userRoot()
                .node(PREFERENCES_NODE)
                .get(windowsDpapiKey(serviceId), null)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return null
            return runCatching {
                val script = """
                    ${'$'}cipher = [Console]::In.ReadToEnd().Trim()
                    ${'$'}bytes = [Convert]::FromBase64String(${'$'}cipher)
                    ${'$'}plain = [Security.Cryptography.ProtectedData]::Unprotect(
                        ${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser
                    )
                    [Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}plain))
                """.trimIndent()
                val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start()
                process.outputStream.bufferedWriter().use { it.write(encoded) }
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
            }.getOrNull()
        }

        fun writeWindowsDpapi(serviceId: String, credential: String): Boolean {
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
                val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start()
                process.outputStream.bufferedWriter().use { it.write(credential) }
                val encoded = process.inputStream.bufferedReader().readText().trim()
                val ok = process.waitFor() == 0 && encoded.isNotEmpty()
                if (ok) {
                    val prefs = Preferences.userRoot().node(PREFERENCES_NODE)
                    prefs.put(windowsDpapiKey(serviceId), encoded)
                    prefs.flush()
                }
                ok
            }.getOrElse { false }
        }

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
