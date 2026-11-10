package com.hereliesaz.geministrator.net

/**
 * The environment a program the app starts is given: an allowlist, so what the app was launched with (API keys,
 * repository tokens, `GIT_*`) is not handed to a CLI agent or an MCP server process just because it was there.
 * Names match without regard to case (Windows). One list serves both, so they cannot drift apart.
 */
object ChildProcessEnvironment {
    val BASE: List<String> = listOf(
        "PATH", "HOME", "USER", "USERNAME", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP",
        "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME",
        "SYSTEMROOT", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "WINDIR", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
    )

    /** The entries of [inherited] whose names are in [BASE] or [extra]. */
    fun allowed(inherited: Map<String, String>, extra: List<String> = emptyList()): Map<String, String> {
        val names = BASE + extra
        return inherited.filterKeys { key -> names.any { it.equals(key, ignoreCase = true) } }
    }
}
