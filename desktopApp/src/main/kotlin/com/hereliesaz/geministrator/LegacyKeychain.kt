package com.hereliesaz.geministrator

/**
 * Reads and retires secrets that older builds stored in the macOS Keychain or Linux Secret Service
 * under the pre-rename service names. Windows keeps its secrets in per-user DPAPI files, which
 * were never named after the old product, and in Preferences nodes migrated by [LegacyDesktopNames].
 */
internal object LegacyKeychain {
    private val os: String get() = System.getProperty("os.name", "").lowercase()

    // Entries found absent this session. A legacy entry is never created while the app runs, so asking again would only
    // spawn another subprocess per absent provider on every read.
    private val knownAbsent: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The secret held under [legacyService] for [account], or null when there is none or no store. Windows has no legacy
     * entries to read (its secrets were never named after the old product), so it always answers null without a lookup.
     */
    fun read(legacyService: String, account: String): String? {
        val cacheKey = "$legacyService|$account"
        if (cacheKey in knownAbsent) return null
        val command = when {
            os.contains("mac") -> listOf("security", "find-generic-password", "-s", legacyService, "-a", account, "-w")
            os.contains("nux") || os.contains("nix") || os.contains("bsd") ->
                listOf("secret-tool", "lookup", "service", legacyService, "account", account)
            else -> return null
        }
        val found = runCatching {
            CredentialTool.run(command)?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()
        if (found == null) knownAbsent += cacheKey
        return found
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
        knownAbsent += "$legacyService|$account"
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
