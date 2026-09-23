package com.hereliesaz.aive

import com.hereliesaz.geministrator.memory.MemoryAttentionGate
import com.hereliesaz.geministrator.memory.MemoryAttentionPolicy
import com.hereliesaz.geministrator.memory.MemoryAttentionState
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryRecallHit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies the Attention Deficit Dial ([MemoryAttentionGate]) to already-computed recall hits.
 *
 * Per docs/architecture/MEMORY_BANKING_AND_ATTENTION.md the dial only controls *surfacing*; it never
 * touches the memory graph. Recall is cue-first:
 *  - While the gate is closed (strongest association below the dial's similarity threshold, or not
 *    enough tokens have passed since the last cue), only tag-level cues (noun/verb/category tags)
 *    may surface, capped at the dial's `maxTags`.
 *  - When the gate opens, the full ranked recall (tags plus deeper phrase/summary/context) surfaces
 *    and the cue interval is reset via [MemoryAttentionGate.markCueSurfaced].
 *
 * Token consumption (the documented recovery signal) is fed in via [consumeTokens]. State is held
 * for the lifetime of the owning runtime, i.e. one per attached memory layer.
 */
internal class AttentionGatedRecall(
    policy: MemoryAttentionPolicy = MemoryAttentionPolicy(),
    initialState: MemoryAttentionState = MemoryAttentionState(),
) {
    private val gate = MemoryAttentionGate(policy)
    private val mutex = Mutex()
    private var state: MemoryAttentionState = initialState

    suspend fun currentState(): MemoryAttentionState = mutex.withLock { state }

    suspend fun consumeTokens(tokenCount: Int) {
        if (tokenCount <= 0) return
        mutex.withLock { state = gate.consumeTokens(state, tokenCount) }
    }

    suspend fun suppress(level: Float) = mutex.withLock { state = gate.suppress(state, level) }

    suspend fun setBaseline(level: Float) = mutex.withLock { state = gate.setBaseline(state, level) }

    /** [rankedHits] must already be sorted best-first and capped by the caller. */
    suspend fun select(rankedHits: List<MemoryRecallHit>): List<MemoryRecallHit> = mutex.withLock {
        if (rankedHits.isEmpty()) return@withLock rankedHits
        val strongest = rankedHits.maxOf { it.score }.coerceIn(0f, 1f)
        if (gate.shouldSurfaceTags(state, strongest)) {
            state = gate.markCueSurfaced(state)
            rankedHits
        } else {
            val maxTags = gate.cuePolicy(state).maxTags
            rankedHits.filter { it.node.kind in CUE_KINDS }.take(maxTags)
        }
    }

    companion object {
        val CUE_KINDS = setOf(MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag, MemoryNodeKind.Category)

        fun approximateTokens(text: String?, charsPerToken: Int = 4): Int =
            if (text.isNullOrEmpty()) 0 else (text.length + charsPerToken - 1) / charsPerToken
    }
}
