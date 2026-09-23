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
 *  - Tags are the ambient layer: the only memory an agent sees without deliberately thinking about
 *    it. They are not a fallback of this gate; the gate governs whether associative recall is
 *    injected into the prompt at all.
 *  - While the gate is closed (strongest association below the dial's similarity threshold, or not
 *    enough tokens have passed since the last cue) the result is silence: nothing is injected.
 *  - When the gate opens, the ranked recall surfaces and the cue interval is reset via
 *    [MemoryAttentionGate.markCueSurfaced].
 *
 * Token consumption (the documented recovery signal) is fed in via [consumeTokens]. The number of
 * tokens needed before the gate reopens scales with the dial: the cue interval interpolates from
 * `focusedCueIntervalTokens` (dial 0) down to `intrusiveCueIntervalTokens` (dial 1), so a lower dial
 * needs more tokens to reopen and a higher dial fewer. One instance holds one agent's state; see
 * [PerAgentAttention].
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
            emptyList()
        }
    }

    companion object {
        val CUE_KINDS = setOf(MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag, MemoryNodeKind.Category)

        fun approximateTokens(text: String?, charsPerToken: Int = 4): Int =
            if (text.isNullOrEmpty()) 0 else (text.length + charsPerToken - 1) / charsPerToken
    }
}

/**
 * Attention state keyed per agent. An agent here is one managed session, identified by the
 * [com.hereliesaz.geministrator.domain.TaskRunId] value that both the prompt-context request and
 * the session handle carry. [defaultLevel] is the dial position new agents start at; it and each
 * agent's own level can be changed at runtime.
 */
internal class PerAgentAttention(
    private val policy: MemoryAttentionPolicy = MemoryAttentionPolicy(),
    defaultLevel: Float = MemoryAttentionState().baselineLevel,
) {
    private val mutex = Mutex()
    private val agents = mutableMapOf<String, AttentionGatedRecall>()

    init { require(defaultLevel in 0f..1f) }

    @Volatile
    var defaultLevel: Float = defaultLevel
        set(value) {
            require(value in 0f..1f)
            field = value
        }

    suspend fun forAgent(agentId: String): AttentionGatedRecall = mutex.withLock {
        agents.getOrPut(agentId) {
            val level = defaultLevel
            AttentionGatedRecall(policy, MemoryAttentionState(baselineLevel = level))
        }
    }

    /** Persistently turns one agent's dial to [level]. */
    suspend fun setLevel(agentId: String, level: Float) = forAgent(agentId).setBaseline(level)

    /** Temporarily lowers one agent's dial; token use recovers it toward its level. */
    suspend fun suppress(agentId: String, level: Float) = forAgent(agentId).suppress(level)

    suspend fun forget(agentId: String) = mutex.withLock { agents.remove(agentId) }
}
