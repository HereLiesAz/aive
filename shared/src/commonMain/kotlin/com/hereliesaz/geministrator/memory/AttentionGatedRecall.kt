package com.hereliesaz.geministrator.memory

import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import com.hereliesaz.geministrator.Sha256
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
/** Bounded checkpoint metadata; no prompt, memory text or raw node identifier. */
@Serializable
internal data class AttentionCheckpoint(
    val state: MemoryAttentionState,
    val tokens: Long,
    val credits: Double,
    val open: Boolean,
    val recent: List<AttentionExposure>,
)

@Serializable
internal data class AttentionExposure(val nodeHash: String, val depth: Int, val at: Long)

internal class AttentionGatedRecall(
    private val policy: MemoryAttentionPolicy = MemoryAttentionPolicy(),
    initialState: MemoryAttentionState = MemoryAttentionState(),
    checkpoint: AttentionCheckpoint? = null,
) {
    private val gate = MemoryAttentionGate(policy)
    private val mutex = Mutex()
    private var state: MemoryAttentionState = checkpoint?.state ?: initialState

    /** Tokens consumed so far, and when (in those tokens) each recent memory was surfaced. */
    private var tokens: Long = checkpoint?.tokens?.coerceAtLeast(0) ?: 0
    private val surfacedAt = LinkedHashMap<Pair<String, Int>, Long>().apply {
        checkpoint?.recent?.takeLast(RECENT_LIMIT)?.forEach {
            if (it.at in 0..tokens && it.depth >= 0 && it.nodeHash.matches(Regex("[a-f0-9]{64}"))) put(it.nodeHash to it.depth, it.at)
        }
    }

    /** Cue credits: one is spent per surfacing; tokens refill one per cue interval, up to the burst. */
    private var credits: Double = checkpoint?.credits?.takeIf { it.isFinite() }?.coerceIn(0.0, policy.cueBurst.toDouble()) ?: policy.cueBurst.toDouble()

    /** The gate opened last time it was asked and has not closed since (hysteresis). */
    private var open: Boolean = checkpoint?.open ?: false

    suspend fun checkpoint(): AttentionCheckpoint = mutex.withLock {
        AttentionCheckpoint(state, tokens, credits, open, surfacedAt.map { (key, at) -> AttentionExposure(key.first, key.second, at) })
    }

    private fun key(id: MemoryNodeId, depth: Int) = Sha256.hex(id.value.encodeToByteArray()) to depth

    suspend fun currentState(): MemoryAttentionState = mutex.withLock { state }

    suspend fun consumeTokens(tokenCount: Int) {
        if (tokenCount <= 0) return
        mutex.withLock {
            state = gate.consumeTokens(state, tokenCount)
            tokens = if (tokens > Long.MAX_VALUE - tokenCount) Long.MAX_VALUE else tokens + tokenCount
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

    /**
     * Only the novelty limit, for what the agent itself followed: nothing shown at this [depth]
     * within the window is shown again, and what is shown is remembered. The dial's threshold and
     * interval do not apply.
     */
    suspend fun fresh(hits: List<MemoryRecallHit>, depth: Int = 0): List<MemoryRecallHit> = mutex.withLock {
        val novel = novelAt(hits, depth)
        remember(novel, depth)
        novel
    }

    private fun novelAt(hits: List<MemoryRecallHit>, depth: Int): List<MemoryRecallHit> {
        val window = policy.noveltyCueIntervals.toLong() * gate.cuePolicy(state).minimumIntervalTokens
        return hits.filter { hit ->
            val shownAt = surfacedAt[key(hit.node.id, depth)] ?: return@filter true
            tokens - shownAt >= window
        }
    }

    private fun remember(shown: List<MemoryRecallHit>, depth: Int) {
        shown.forEach { surfacedAt.remove(key(it.node.id, depth)); surfacedAt[key(it.node.id, depth)] = tokens }
        while (surfacedAt.size > RECENT_LIMIT) surfacedAt.remove(surfacedAt.keys.first())
    }

    /**
     * [depth] is how far down the condensation what is shown goes (0 = gist). The novelty window is
     * kept per memory and depth: a repeat at the same depth is held back, going deeper is not.
     */
    suspend fun select(rankedHits: List<MemoryRecallHit>, ignoreInterval: Boolean = false, depth: Int = 0): List<MemoryRecallHit> = mutex.withLock {
        // Memories surfaced at this depth within the novelty window are not offered again; the gate
        // decides on what is left.
        val novel = novelAt(rankedHits, depth)
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
            remember(novel, depth)
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

    /** Reconstruct a gate from the exact host-bound session's saved metadata, or start fresh. */
    suspend fun restore(agentId: String, checkpoint: AttentionCheckpoint?) = mutex.withLock {
        agents[agentId] = AttentionGatedRecall(policy, MemoryAttentionState(baselineLevel = defaultLevel), checkpoint)
    }

    suspend fun forget(agentId: String) = mutex.withLock { agents.remove(agentId) }

    suspend fun clear() = mutex.withLock { agents.clear() }
}
