package com.hereliesaz.geministrator

/**
 * Reads and retires secrets that older builds stored in the macOS Keychain or Linux Secret Service
 * under the pre-rename service names. Windows keeps its secrets in per-user DPAPI files, which
 * were never named after the old product, and in Preferences nodes migrated by [LegacyDesktopNames].
 */
internal object LegacyKeychain {
    private val os: String get() = System.getProperty("os.name", "").lowercase()

    /** The secret held under [legacyService] for [account], or null when there is none or no store. */
    fun read(legacyService: String, account: String): String? {
        val command = when {
            os.contains("mac") -> listOf("security", "find-generic-password", "-s", legacyService, "-a", account, "-w")
            os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                listOf("secret-tool", "lookup", "service", legacyService, "account", account)
            else -> return null
        }
        return runCatching {
            CredentialTool.run(command)?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    /** Removes the legacy entry once its value has been stored under the current service name. */
    fun delete(legacyService: String, account: String) {
        val command = when {
            os.contains("mac") -> listOf("security", "delete-generic-password", "-s", legacyService, "-a", account)
            os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                listOf("secret-tool", "clear", "service", legacyService, "account", account)
            else -> return
        }
        runCatching { CredentialTool.run(command) }
    }

    /**
     * Moves the secret for [account] from the legacy service name to [currentService]: [store] writes it
     * under the current name, and only a successful write retires the legacy entry. Returns the secret.
     */
    fun migrate(currentService: String, account: String, store: (String) -> Boolean): String? {
        val legacyService = LegacyDesktopNames.legacyService(currentService)
        val secret = read(legacyService, account) ?: return null
        if (store(secret)) delete(legacyService, account)
        return secret
    }
}
