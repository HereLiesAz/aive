package com.hereliesaz.geministrator.providers

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How often a prompt-prefix cache (LiteRT-LM or ONNX Runtime) helped. [hits] reused a primed prefix, [misses] primed a
 * new one, [fallbacks] gave up on the cache and ran the whole prompt. [prefixTokensEstimate] is the
 * estimated size of the prefix primed now (0 when none), so `hits * prefixTokensEstimate` is roughly the
 * prompt work saved. Exposed on [OnDeviceTextGenerator.prefixStats]; the Keeper's room shows it under This device.
 */
data class PromptPrefixStats(
    val hits: Long = 0,
    val misses: Long = 0,
    val fallbacks: Long = 0,
    val prefixTokensEstimate: Int = 0,
    /** The once-per-process switch is off: a cache failure turned reuse off, so every call runs the whole prompt. */
    val switchedOff: Boolean = false,
) {
    /** Calls that went through the cache path (it served or tried to serve them). */
    val tasks: Long get() = hits + misses + fallbacks
}

/**
 * What a primed prefix is valid for: the adapter the engine ran under ([NO_ADAPTER] for the plain base)
 * and the exact head text. A prefix primed under one adapter is never reused under another, because the
 * adapter changes the keys and values the model stores for the same text.
 */
data class PromptPrefixKey(val adapterId: String, val head: String) {
    companion object {
        const val NO_ADAPTER: String = "none"

        fun of(adapterId: String?, head: String): PromptPrefixKey =
            PromptPrefixKey(adapterId?.takeIf(String::isNotBlank) ?: NO_ADAPTER, head)
    }
}

/**
 * Remembers across engine reloads that the prefix path failed once, so a broken cache is tried once per
 * process, not once per load.
 */
class PromptPrefixSwitch {
    @Volatile
    var disabled: Boolean = false
}

/** An ONNX head already run once: its `present.*` tensors are held (platform side) to feed later tails. */
interface OrtPrimedHead {
    /** Runs [tail] after the held head and returns the answer. May throw; the caller then falls back. */
    fun generate(tail: String, maxNewTokens: Int): OnDeviceTextGeneration

    /** Frees the held tensors. */
    fun close()
}

/** The native and tokenizer calls behind [OrtPrefixReuse]; platforms implement it over ONNX Runtime, tests over a fake. */
interface OrtPrefixBackend {
    /** Token ids of [text] as the app's tokenizer reads it on its own. */
    fun encode(text: String): LongArray

    /** Runs [head] once and keeps its `present.*` tensors alive. Throws when it cannot. */
    fun prime(head: String): OrtPrimedHead

    /** The plain path: the whole [prompt] from empty caches. */
    fun generateWhole(prompt: String, maxNewTokens: Int): OnDeviceTextGeneration
}

/**
 * Prompt-prefix reuse for the ONNX Runtime small-task generators, with no tensor types in it. Keeps one
 * primed head (the most recent), keyed by adapter and head text. A call whose head matches runs only its
 * tail on the held head; a different head or adapter primes anew.
 *
 * A head under [minPrefixTokens] estimated tokens uses the plain path. Head and tail are tokenised
 * separately; unless their tokens joined equal the tokens of the whole prompt, the call runs plain and
 * counts a fallback (this does not turn the cache off, it is that prompt's property). If priming or the
 * tail run throws, the call runs plain, the cache is turned off on [switch] and the held head is freed:
 * a cache failure never fails a task. Calls are serialised by an internal lock, which also covers [close].
 */
class OrtPrefixReuse(
    private val backend: OrtPrefixBackend,
    private val adapterId: () -> String? = { null },
    private val switch: PromptPrefixSwitch = PromptPrefixSwitch(),
    private val minPrefixTokens: Int = MIN_PREFIX_TOKENS,
) {
    private val lock = Mutex()
    private var primedKey: PromptPrefixKey? = null
    private var primed: OrtPrimedHead? = null
    private var primedTokens = 0

    @Volatile
    private var snapshot = PromptPrefixStats()

    fun stats(): PromptPrefixStats = snapshot.copy(switchedOff = switch.disabled)

    /** Runs [tail] after [head]; the text the model reads is exactly head + tail either way. */
    suspend fun generate(head: String, tail: String, maxNewTokens: Int): OnDeviceTextGeneration = lock.withLock {
        val headTokens = OnDeviceTextAgentProvider.estimateTokens(head.length)
        if (switch.disabled || headTokens < minPrefixTokens) {
            return@withLock backend.generateWhole(head + tail, maxNewTokens)
        }
        try {
            if (!boundaryHolds(head, tail)) {
                snapshot = snapshot.copy(fallbacks = snapshot.fallbacks + 1)
                return@withLock backend.generateWhole(head + tail, maxNewTokens)
            }
            val key = PromptPrefixKey.of(adapterId(), head)
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
            val result = base.generate(tail, maxNewTokens)
            val before = snapshot
            snapshot = before.copy(
                hits = before.hits + if (hit) 1 else 0,
                misses = before.misses + if (hit) 0 else 1,
                prefixTokensEstimate = primedTokens,
            )
            result
        } catch (cancelled: CancellationException) {
            closePrimed()
            throw cancelled
        } catch (failure: Throwable) {
            switch.disabled = true
            closePrimed()
            snapshot = snapshot.copy(fallbacks = snapshot.fallbacks + 1, prefixTokensEstimate = 0)
            backend.generateWhole(head + tail, maxNewTokens)
        }
    }

    /** Frees the held head (when the model is released or closed). */
    suspend fun close() {
        lock.withLock {
            closePrimed()
            snapshot = snapshot.copy(prefixTokensEstimate = 0)
        }
    }

    /** True when the tokens of [head] then [tail] are the tokens of the whole prompt. */
    private fun boundaryHolds(head: String, tail: String): Boolean {
        val whole = backend.encode(head + tail)
        val first = backend.encode(head)
        val second = backend.encode(tail)
        if (first.isEmpty() || first.size + second.size != whole.size) return false
        for (index in first.indices) if (first[index] != whole[index]) return false
        for (index in second.indices) if (second[index] != whole[first.size + index]) return false
        return true
    }

    private fun closePrimed() {
        val current = primed
        primed = null
        primedKey = null
        primedTokens = 0
        if (current != null) runCatching { current.close() }
    }

    companion object {
        /** A head smaller than this (estimated tokens) is not worth holding. */
        const val MIN_PREFIX_TOKENS: Int = 64
    }
}
