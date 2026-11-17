package com.hereliesaz.geministrator.providers

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException

/** A session that has already read a prompt head. Each [start] runs one tail on a copy of it. */
interface LiteRtLmPrimedPrefix {
    /** Runs [tail] on a copy of the primed state; the returned completion releases the copy. Throws if the copy or the stream cannot start. */
    fun start(tail: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion

    /** Frees the primed session. */
    fun close()
}

/** The native calls behind [LiteRtLmPrefixReuse]; the platforms implement it over `Session`, tests over a fake. */
interface LiteRtLmPrefixBackend {
    /** Creates a session and has it read [head]. Throws when it cannot. */
    fun prime(head: String): LiteRtLmPrimedPrefix

    /** The plain path: a fresh session runs the whole [prompt]. */
    fun completeWhole(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion
}

/**
 * Keeps one primed [LiteRtLmPrimedPrefix] for the most recent head, so a call whose head matches runs only
 * its tail. Not thread-safe by itself: [LiteRtLmTextGenerator] calls it only while holding its
 * single-generation lock, which also covers [close].
 *
 * A head under [minPrefixTokens] estimated tokens uses the plain path (it would not pay for the extra
 * session). If priming, copying or starting the stream throws, the call runs plain, the cache is turned
 * off on [switch] and the primed session is freed: a cache failure never fails a task. (A stream that
 * fails later, after it started, is the generation's own failure and is reported as one.)
 */
class LiteRtLmPrefixReuse(
    private val backend: LiteRtLmPrefixBackend,
    private val adapterId: () -> String? = { null },
    private val switch: PromptPrefixSwitch = PromptPrefixSwitch(),
    private val minPrefixTokens: Int = MIN_PREFIX_TOKENS,
) {
    private var primedKey: PromptPrefixKey? = null
    private var primed: LiteRtLmPrimedPrefix? = null
    private var primedTokens = 0

    @Volatile
    private var snapshot = PromptPrefixStats()

    fun stats(): PromptPrefixStats = snapshot.copy(switchedOff = switch.disabled)

    /** Runs [tail] after [head]; the text sent to the model is exactly head + tail either way. */
    fun complete(head: String, tail: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
        val headTokens = OnDeviceTextAgentProvider.estimateTokens(head.length)
        if (switch.disabled || headTokens < minPrefixTokens) return backend.completeWhole(head + tail, listener)
        val key = PromptPrefixKey.of(adapterId(), head)
        try {
            val current = primed
            val hit = current != null && primedKey == key
            val base = if (hit && current != null) {
                current
            } else {
                closePrimed()
                backend.prime(head).also {
                    primed = it
                    primedKey = key
                    primedTokens = headTokens
                }
            }
            val call = base.start(tail, listener)
            val before = snapshot
            snapshot = before.copy(
                hits = before.hits + if (hit) 1 else 0,
                misses = before.misses + if (hit) 0 else 1,
                prefixTokensEstimate = primedTokens,
            )
            return call
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            switch.disabled = true
            closePrimed()
            snapshot = snapshot.copy(fallbacks = snapshot.fallbacks + 1, prefixTokensEstimate = 0)
            return backend.completeWhole(head + tail, listener)
        }
    }

    /** Frees the primed session (when the model is closed or released). */
    fun close() {
        closePrimed()
        snapshot = snapshot.copy(prefixTokensEstimate = 0)
    }

    private fun closePrimed() {
        val current = primed
        primed = null
        primedKey = null
        primedTokens = 0
        if (current != null) runCatching { current.close() }
    }

    companion object {
        /** A head smaller than this (estimated tokens) is not worth a second session. */
        const val MIN_PREFIX_TOKENS: Int = 64
    }
}
