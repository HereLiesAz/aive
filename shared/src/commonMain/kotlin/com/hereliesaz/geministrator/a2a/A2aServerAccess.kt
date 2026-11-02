package com.hereliesaz.geministrator.a2a

import kotlin.time.TimeSource

/**
 * Who sent a request to the A2A server: the remote address as the socket reported it. A caller on
 * this computer is [isLoopback]; any other caller only reaches the server while it is open to the
 * network, and then only from an address the keeper allowed (docs/A2A.md, Open to this network).
 */
data class A2aCaller(val address: String, val isLoopback: Boolean) {
    /** The rate limiter's key: every loopback caller is one client. */
    val clientKey: String get() = if (isLoopback) LOOPBACK_KEY else address

    companion object {
        private const val LOOPBACK_KEY = "loopback"
        val Loopback = A2aCaller("127.0.0.1", isLoopback = true)
    }
}

/**
 * At most [maxPerWindow] `SendMessage` calls per client in any [windowMillis]. Not thread-safe:
 * the server calls it under its own lock. Remembers at most [maxClients] clients, dropping the one
 * heard from least recently.
 */
class A2aRateLimiter(
    val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxClients: Int = 256,
    private val nowMillis: () -> Long = monotonicMillis(),
) {
    private val hits = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Counts one call for [client]; null when it may go ahead, else the milliseconds until it may. */
    fun tryAcquire(client: String): Long? {
        val now = nowMillis()
        val times = hits.remove(client) ?: ArrayDeque()
        while (times.isNotEmpty() && now - times.first() >= windowMillis) times.removeFirst()
        val wait = if (times.size >= maxPerWindow) {
            (windowMillis - (now - times.first())).coerceAtLeast(1)
        } else {
            times.addLast(now)
            null
        }
        hits[client] = times
        while (hits.size > maxClients) hits.remove(hits.keys.first())
        return wait
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 6
        const val DEFAULT_WINDOW_MILLIS = 60_000L

        private fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

/** The host part of a `Host` header (`[::1]:41241` → `::1`, `10.0.0.2:41241` → `10.0.0.2`), lowercased. */
fun a2aHostOf(hostHeader: String?): String? {
    val value = hostHeader?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    return if (value.startsWith("[")) value.substringBefore(']').removePrefix("[") else value.substringBefore(':')
}

/**
 * A keeper-entered or socket-reported client address, normalized for the allowlist: trimmed,
 * lowercased, without brackets or an IPv6 zone. Null when it is not a plain IPv4 or IPv6 literal,
 * so a host name can never be allowed (names can be re-pointed; addresses are what the socket sees).
 */
fun normalizeA2aClientAddress(raw: String?): String? {
    val value = raw?.trim()?.removePrefix("/")?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase()
        ?.takeIf(String::isNotEmpty) ?: return null
    val ipv4 = value.split('.').let { parts ->
        parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255 }
    }
    val ipv6 = !ipv4 && value.contains(':') && value.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
    return value.takeIf { ipv4 || ipv6 }
}

/** Raised inside the RPC handler when a client sent too many `SendMessage` calls; becomes HTTP 429. */
internal class A2aRateLimited(val retryAfterMillis: Long) : RuntimeException("rate limited")
