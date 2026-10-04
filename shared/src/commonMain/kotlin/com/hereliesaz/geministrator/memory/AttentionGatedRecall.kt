package com.hereliesaz.geministrator.memory

import kotlin.concurrent.Volatile
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
 *  - The gate reads the strongest hit plus part of its lead over the rest
 *    ([MemoryAttentionPolicy.distinctnessWeight]); once open it stays open down to the threshold
 *    less [MemoryAttentionPolicy.hysteresis]; and the interval is a token bucket that allows
 *    [MemoryAttentionPolicy.cueBurst] cues back to back before it must refill.
 *
 * Token consumption (the documented recovery signal) is fed in via [consumeTokens]. The number of
 * tokens needed before the gate reopens scales with the dial: the cue interval interpolates from
 * `focusedCueIntervalTokens` (dial 0) down to `intrusiveCueIntervalTokens` (dial 1), so a lower dial
 * needs more tokens to reopen and a higher dial fewer. One instance holds one agent's state; see
 * [PerAgentAttention].
 */
internal class AttentionGatedRecall(
    private val policy: MemoryAttentionPolicy = MemoryAttentionPolicy(),
    initialState: MemoryAttentionState = MemoryAttentionState(),
) {
    private val gate = MemoryAttentionGate(policy)
    private val mutex = Mutex()
    private var state: MemoryAttentionState = initialState

    /** Tokens consumed so far, and when (in those tokens) each recent memory was surfaced. */
    private var tokens: Long = 0
    private val surfacedAt = LinkedHashMap<MemoryNodeId, Long>()

    /** Cue credits: one is spent per surfacing; tokens refill one per cue interval, up to the burst. */
    private var credits: Double = policy.cueBurst.toDouble()

    /** The gate opened last time it was asked and has not closed since (hysteresis). */
    private var open: Boolean = false

    suspend fun currentState(): MemoryAttentionState = mutex.withLock { state }

    suspend fun consumeTokens(tokenCount: Int) {
        if (tokenCount <= 0) return
        mutex.withLock {
            state = gate.consumeTokens(state, tokenCount)
            tokens += tokenCount
            credits = minOf(policy.cueBurst.toDouble(), credits + tokenCount.toDouble() / gate.cuePolicy(state).minimumIntervalTokens)
        }
    }

    suspend fun suppress(level: Float) = mutex.withLock { state = gate.suppress(state, level) }

    suspend fun setBaseline(level: Float) = mutex.withLock { state = gate.setBaseline(state, level) }

    /** [rankedHits] must already be sorted best-first and capped by the caller. */
    /**
     * [select] for cues that accompany something already surfacing at this moment (the cloud for
     * a task's own prompt): the dial's threshold and novelty apply, the shared cue interval does not.
     */
    suspend fun selectAlongside(rankedHits: List<MemoryRecallHit>): List<MemoryRecallHit> = select(rankedHits, ignoreInterval = true)

    suspend fun select(rankedHits: List<MemoryRecallHit>, ignoreInterval: Boolean = false): List<MemoryRecallHit> = mutex.withLock {
        // Memories surfaced within the novelty window are not offered again; the gate decides on
        // what is left.
        val window = policy.noveltyCueIntervals.toLong() * gate.cuePolicy(state).minimumIntervalTokens
        val novel = rankedHits.filter { hit ->
            val shownAt = surfacedAt[hit.node.id] ?: return@filter true
            tokens - shownAt >= window
        }
        if (novel.isEmpty()) return@withLock novel
        val scores = novel.map { it.score.coerceIn(0f, 1f) }.sortedDescending()
        val strongest = scores.first()
        val lead = if (scores.size > 1) strongest - scores.drop(1).average().toFloat() else 0f
        val signal = (strongest + policy.distinctnessWeight * lead).coerceIn(0f, 1f)
        val threshold = gate.cuePolicy(state).minimumSimilarity - if (open) policy.hysteresis else 0f
        val similar = signal >= threshold
        val opens = similar && (ignoreInterval || credits >= 1.0)
        if (!similar) open = false
        if (opens) {
            if (!ignoreInterval) credits -= 1.0
            open = true
            state = gate.markCueSurfaced(state)
            novel.forEach { surfacedAt.remove(it.node.id); surfacedAt[it.node.id] = tokens }
            while (surfacedAt.size > RECENT_LIMIT) surfacedAt.remove(surfacedAt.keys.first())
            novel
        } else {
            emptyList()
        }
    }

    companion object {
        private const val RECENT_LIMIT = 64

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

    /** Temporarily lowers one agent's dial; token use recovers it (scaled by depth) to rest above its level. */
    suspend fun suppress(agentId: String, level: Float) = forAgent(agentId).suppress(level)

    suspend fun forget(agentId: String) = mutex.withLock { agents.remove(agentId) }
}
