package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.mesh.platformSecureRandomBytesOrNull

/** Six digits from the platform's secure random source, or null when there is none (pairing then refuses; it never falls back to a weak one). */
internal fun secureSixDigitCode(): String? {
    val bytes = platformSecureRandomBytesOrNull(4) ?: return null
    var n = 0L
    for (b in bytes) n = (n shl 8) or (b.toLong() and 0xFF)
    val digits = (n % 1_000_000L).toString()
    return digits.padStart(6, '0')
}

/** Pairing result of a `/pair` message. */
sealed interface PairRequestOutcome {
    data object Started : PairRequestOutcome

    /** A different chat already has a code waiting; this one is ignored until it ends. */
    data object Busy : PairRequestOutcome
    data object AlreadyPaired : PairRequestOutcome
    data object NoRandomSource : PairRequestOutcome
}

sealed interface PairRedeemOutcome {
    data class Paired(val identity: ChatIdentity) : PairRedeemOutcome
    data object Wrong : PairRedeemOutcome
    data object Expired : PairRedeemOutcome

    /** No code is waiting, or it is waiting for a different chat: nothing happens and nothing is said. */
    data object NoChallenge : PairRedeemOutcome
}

/**
 * One platform's pairing (docs/Chat-Bots.md). `/pair` from a chat starts a challenge for that chat only; the app shows the
 * one-time code ([pending]); the same chat sends the code back within [VALID_MILLIS]; only then is that chat the keeper.
 * A code works once, ends after [MAX_WRONG] wrong tries, and a second chat cannot replace a waiting challenge.
 */
class ChatPairing(
    private val clock: () -> Long,
    private val newCode: () -> String? = ::secureSixDigitCode,
    initiallyPaired: ChatIdentity? = null,
) {
    companion object {
        const val VALID_MILLIS: Long = 5L * 60L * 1000L
        const val MAX_WRONG: Int = 3
    }

    private class Challenge(val code: String, val who: ChatIdentity, val expiresAt: Long, var wrong: Int = 0)

    /** The code the app shows and when it ends. */
    data class Pending(val code: String, val expiresAtEpochMillis: Long)

    private var challenge: Challenge? = null

    var paired: ChatIdentity? = initiallyPaired
        private set

    /** The code to show in the app, or null when none is waiting (or it ran out). */
    fun pending(): Pending? {
        val c = live() ?: return null
        return Pending(c.code, c.expiresAt)
    }

    private fun live(): Challenge? {
        val c = challenge ?: return null
        if (clock() >= c.expiresAt) {
            challenge = null
            return null
        }
        return c
    }

    fun request(from: ChatIdentity): PairRequestOutcome {
        if (paired != null) return PairRequestOutcome.AlreadyPaired
        val waiting = live()
        if (waiting != null && waiting.who != from) return PairRequestOutcome.Busy
        val code = newCode() ?: return PairRequestOutcome.NoRandomSource
        challenge = Challenge(code, from, clock() + VALID_MILLIS)
        return PairRequestOutcome.Started
    }

    fun redeem(from: ChatIdentity, code: String): PairRedeemOutcome {
        if (paired != null) return PairRedeemOutcome.NoChallenge
        val c = challenge ?: return PairRedeemOutcome.NoChallenge
        if (clock() >= c.expiresAt) {
            challenge = null
            return PairRedeemOutcome.Expired
        }
        if (c.who != from) return PairRedeemOutcome.NoChallenge
        if (!constantTimeEquals(c.code, code)) {
            c.wrong += 1
            if (c.wrong >= MAX_WRONG) challenge = null
            return PairRedeemOutcome.Wrong
        }
        challenge = null
        paired = from
        return PairRedeemOutcome.Paired(from)
    }

    /** The keeper unpairs in the app: no chat is the keeper afterwards, and any waiting code ends. */
    fun unpair() {
        paired = null
        challenge = null
    }

    fun cancelChallenge() {
        challenge = null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        var diff = a.length xor b.length
        val n = maxOf(a.length, b.length)
        for (i in 0 until n) {
            val x = if (i < a.length) a[i].code else 0
            val y = if (i < b.length) b[i].code else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }
}

/**
 * A sliding-window limit per key. Used for the keeper's messages and, silently, for strangers. The number of keys kept is
 * bounded so a flood of different senders cannot grow it.
 */
class ChatRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: () -> Long,
    private val maxKeys: Int = 256,
) {
    private val hits = LinkedHashMap<String, ArrayDeque<Long>>()

    fun allow(key: String): Boolean {
        val now = clock()
        val queue = hits.getOrPut(key) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
        val ok = queue.size < limit
        if (ok) queue.addLast(now)
        // Forget the oldest keys when full; an evicted sender simply starts a fresh window.
        while (hits.size > maxKeys) hits.remove(hits.keys.first())
        return ok
    }
}
