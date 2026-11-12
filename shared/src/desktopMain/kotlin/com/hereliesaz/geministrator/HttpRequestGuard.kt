package com.hereliesaz.geministrator

import com.sun.net.httpserver.HttpExchange

/**
 * What JVM loopback control servers do before they trust a request's body: nothing is read for a caller
 * that would be refused anyway (the server asks its core first, from the headers alone), a declared length over
 * the cap is refused unread, and a body that dribbles in is cut off by the JDK server's own request timer.
 */
object HttpRequestGuard {
    /** How long, in seconds, one request may take to arrive before the server drops its connection. */
    const val MAX_REQUEST_SECONDS = 15

    private const val PROPERTY = "sun.net.httpserver.maxReqTime"

    /**
     * Sets the JDK HTTP server's request timer unless the process already chose one. The JDK reads it once, when
     * the first server in the process is created, so the servers call this just before they create theirs.
     */
    fun applyRequestTimeout(seconds: Int = MAX_REQUEST_SECONDS) {
        if (System.getProperty(PROPERTY).isNullOrBlank()) System.setProperty(PROPERTY, seconds.toString())
    }

    /** True when the request declares a body longer than [maxBytes]: it is refused without reading any of it. */
    fun declaresTooLarge(exchange: HttpExchange, maxBytes: Int): Boolean =
        exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()?.let { it > maxBytes } == true

    /** Up to [maxBytes] + 1 bytes of the body; the caller compares the size to [maxBytes]. */
    fun readBody(exchange: HttpExchange, maxBytes: Int): ByteArray = exchange.requestBody.readNBytes(maxBytes + 1)
}
