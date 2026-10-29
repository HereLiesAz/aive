package com.hereliesaz.geministrator.net

/**
 * Where a bearer secret may travel over plain `http://`: this device and private networks, where
 * local model servers and development relays live. Anything else must use `https://`.
 */
object CleartextPolicy {
    /** True when a secret may be sent to [url]: it is https, or http to a loopback/private host. */
    fun allowsSecret(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.startsWith("https://")) return true
        if (!lower.startsWith("http://")) return false
        return isLocalOrPrivateHost(hostOf(lower))
    }

    /**
     * The host an HTTP client connects to for [lowerUrl]. The authority ends at the first `/`, `?`,
     * `#` or `\` (URL parsers treat `\` like `/`), and only then is userinfo (up to the last `@`)
     * dropped, so an `@` or `.local` in a fragment, query or path can never pose as the host.
     */
    internal fun hostOf(lowerUrl: String): String {
        val rest = lowerUrl.removePrefix("http://")
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val authority = (if (end < 0) rest else rest.substring(0, end)).substringAfterLast('@')
        return if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')
    }

    internal fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
