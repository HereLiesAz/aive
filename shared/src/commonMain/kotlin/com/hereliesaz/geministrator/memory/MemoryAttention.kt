package com.hereliesaz.geministrator.memory

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Deterministic policy for ambient associative memory cues.
 *
 * This is an orchestration/attention concern, not a Memory Clerk capability. It never changes the
 * graph or the meaning of a memory; it only controls how readily related tags are allowed into an
 * active agent's context.
 */
@Serializable
data class MemoryAttentionPolicy(
    val recoveryWindowTokens: Int = 4_096,
    val focusedSimilarityThreshold: Float = 0.92f,
    val intrusiveSimilarityThreshold: Float = 0.45f,
    val focusedCueIntervalTokens: Int = 2_048,
    val intrusiveCueIntervalTokens: Int = 128,
    val minimumCueTags: Int = 1,
    val maximumCueTags: Int = 8,
) {
    init {
        require(recoveryWindowTokens > 0)
        require(focusedSimilarityThreshold in 0f..1f)
        require(intrusiveSimilarityThreshold in 0f..1f)
        require(focusedSimilarityThreshold >= intrusiveSimilarityThreshold)
        require(focusedCueIntervalTokens > 0)
        require(intrusiveCueIntervalTokens > 0)
        require(focusedCueIntervalTokens >= intrusiveCueIntervalTokens)
        require(minimumCueTags > 0)
        require(maximumCueTags >= minimumCueTags)
    }
}

/**
 * Per-agent associative-attention state.
 *
 * [baselineLevel] is the agent's configured ADD level. [effectiveLevel] may be temporarily lowered
 * for focus. [suppressedFromLevel] records the temporary floor so token consumption can recover
 * deterministically toward baseline without relying on the agent to remember to turn memory back
 * on.
 */
@Serializable
data class MemoryAttentionState(
    val baselineLevel: Float = 0.5f,
    val effectiveLevel: Float = baselineLevel,
    val suppressedFromLevel: Float = effectiveLevel,
    val tokensSinceSuppression: Long = 0,
    val tokensSinceCue: Long = Long.MAX_VALUE,
) {
    init {
        require(baselineLevel in 0f..1f)
        require(effectiveLevel in 0f..1f)
        require(suppressedFromLevel in 0f..1f)
        require(tokensSinceSuppression >= 0)
        require(tokensSinceCue >= 0)
    }
}

@Serializable
data class MemoryAttentionCuePolicy(
    val minimumSimilarity: Float,
    val minimumIntervalTokens: Int,
    val maxTags: Int,
)

class MemoryAttentionGate(
    private val policy: MemoryAttentionPolicy = MemoryAttentionPolicy(),
) {
    /** Persistently changes the agent's normal ADD level. */
    fun setBaseline(state: MemoryAttentionState, level: Float): MemoryAttentionState {
        require(level in 0f..1f)

        // A state already resting at baseline is not under temporary suppression, so a persistent
        // baseline change should take effect immediately in either direction.
        if (state.effectiveLevel == state.baselineLevel) {
            return state.copy(
                baselineLevel = level,
                effectiveLevel = level,
                suppressedFromLevel = level,
                tokensSinceSuppression = 0,
            )
        }

        // While focus suppression is active, changing the normal baseline must not unexpectedly
        // raise current attention. Lower baselines still clamp the effective level immediately.
        val effective = minOf(state.effectiveLevel, level)
        return state.copy(
            baselineLevel = level,
            effectiveLevel = effective,
            suppressedFromLevel = minOf(state.suppressedFromLevel, effective),
        )
    }

    /**
     * Temporarily lowers associative intrusion. Raising attention is a baseline/configuration
     * action; this operation intentionally only permits suppression.
     */
    fun suppress(state: MemoryAttentionState, level: Float): MemoryAttentionState {
        require(level in 0f..1f)
        val bounded = minOf(level, state.baselineLevel, state.effectiveLevel)
        return state.copy(
            effectiveLevel = bounded,
            suppressedFromLevel = bounded,
            tokensSinceSuppression = 0,
        )
    }

    /**
     * Token use restores a temporarily lowered dial toward its configured baseline. Wall-clock
     * idleness does not perform this recovery.
     */
    fun consumeTokens(state: MemoryAttentionState, tokenCount: Int): MemoryAttentionState {
        require(tokenCount >= 0)
        if (tokenCount == 0) return state
        val increment = tokenCount.toLong()
        val used = saturatingAdd(state.tokensSinceSuppression, increment)
        val progress = (used.toDouble() / policy.recoveryWindowTokens.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
        val recovered = lerp(state.suppressedFromLevel, state.baselineLevel, progress)
        val cueTokens = saturatingAdd(state.tokensSinceCue, increment)
        return state.copy(
            effectiveLevel = recovered,
            tokensSinceSuppression = used,
            tokensSinceCue = cueTokens,
        )
    }

    fun cuePolicy(state: MemoryAttentionState): MemoryAttentionCuePolicy {
        val add = state.effectiveLevel.coerceIn(0f, 1f)
        val similarity = lerp(policy.focusedSimilarityThreshold, policy.intrusiveSimilarityThreshold, add)
        val interval = lerp(
            policy.focusedCueIntervalTokens.toFloat(),
            policy.intrusiveCueIntervalTokens.toFloat(),
            add,
        ).roundToInt().coerceAtLeast(1)
        val tags = lerp(
            policy.minimumCueTags.toFloat(),
            policy.maximumCueTags.toFloat(),
            add,
        ).roundToInt().coerceIn(policy.minimumCueTags, policy.maximumCueTags)
        return MemoryAttentionCuePolicy(
            minimumSimilarity = similarity,
            minimumIntervalTokens = interval,
            maxTags = tags,
        )
    }

    fun shouldSurfaceTags(
        state: MemoryAttentionState,
        strongestAssociationScore: Float,
    ): Boolean {
        require(strongestAssociationScore in 0f..1f)
        val cue = cuePolicy(state)
        return strongestAssociationScore >= cue.minimumSimilarity &&
            state.tokensSinceCue >= cue.minimumIntervalTokens.toLong()
    }

    fun markCueSurfaced(state: MemoryAttentionState): MemoryAttentionState =
        state.copy(tokensSinceCue = 0)
}

private fun saturatingAdd(current: Long, increment: Long): Long {
    require(current >= 0)
    require(increment >= 0)
    return if (current > Long.MAX_VALUE - increment) Long.MAX_VALUE else current + increment
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)
