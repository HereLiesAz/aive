package com.hereliesaz.geministrator.net

/** A failure that knows how long the remote asked the caller to wait before trying again (its `Retry-After`). */
interface RetryAfterHint {
    val retryAfterMillis: Long?
}

/** A refused HTTP request: its [status] and, when the server sent one, how long it asked to be left alone. */
class HttpStatusFailure(
    val status: Int,
    message: String,
    override val retryAfterMillis: Long? = null,
) : IllegalStateException(message), RetryAfterHint

/** The longest wait a server's `Retry-After` is allowed to ask for: a clerk is not parked for an hour by one header. */
const val MAX_RETRY_AFTER_MILLIS: Long = 5 * 60 * 1000L

/**
 * The wait in a `Retry-After` header given as delta-seconds, in milliseconds and capped at [MAX_RETRY_AFTER_MILLIS];
 * null when there is none or it is an HTTP date (a date needs a clock the caller may not have, so it falls back to
 * the caller's own backoff).
 */
fun parseRetryAfterMillis(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return minOf(seconds, MAX_RETRY_AFTER_MILLIS / 1000L) * 1000L
}

/** The [RetryAfterHint] on this failure or its cause chain, in milliseconds, or null. */
fun Throwable.retryAfterHintMillis(): Long? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is RetryAfterHint) current.retryAfterMillis?.let { return it }
        current = current.cause
        depth++
    }
    return null
}

/**
 * How long to wait before the next attempt after [consecutiveFailures] failures in a row (1 for the first): exponential from
 * [baseMillis], capped at [capMillis], never shorter than the server's [retryAfterMillis] when it gave one (itself capped at
 * [MAX_RETRY_AFTER_MILLIS]).
 */
fun backoffMillis(
    consecutiveFailures: Int,
    retryAfterMillis: Long? = null,
    baseMillis: Long = 1_000L,
    capMillis: Long = 60_000L,
): Long {
    val exponent = (consecutiveFailures - 1).coerceIn(0, 20)
    val exponential = minOf(baseMillis shl exponent, capMillis)
    val asked = retryAfterMillis?.coerceIn(0L, MAX_RETRY_AFTER_MILLIS) ?: 0L
    return maxOf(exponential, asked)
}
