package com.hereliesaz.aive

import com.hereliesaz.geministrator.memory.MemoryAttentionPolicy
import com.hereliesaz.geministrator.memory.MemoryAttentionState
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryRecallHit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttentionGatedRecallTest {
    private fun hit(id: String, kind: MemoryNodeKind, score: Float) =
        MemoryRecallHit(MemoryNode(MemoryNodeId(id), kind, "text-$id", createdAtEpochMillis = 0L), score)

    private val ranked = listOf(
        hit("summary", MemoryNodeKind.Summary, 0.95f),
        hit("noun", MemoryNodeKind.NounTag, 0.9f),
        hit("context", MemoryNodeKind.Context, 0.85f),
        hit("category", MemoryNodeKind.Category, 0.8f),
        hit("verb", MemoryNodeKind.VerbTag, 0.7f),
    )

    @Test
    fun closedGateIsSilentNotATagFallback() = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy())
        // Fresh state: no cue yet, strong association -> full recall, cue interval reset.
        assertEquals(ranked, recall.select(ranked))
        assertEquals(0L, recall.currentState().tokensSinceCue)
        // Immediate repeat: interval not elapsed -> silence, even though tag hits are present.
        assertTrue(recall.select(ranked).isEmpty())
        // Token passage past the dial's cue interval re-opens recall.
        recall.consumeTokens(5_000)
        assertEquals(ranked, recall.select(ranked))
    }

    @Test
    fun weakAssociationsStaySilentWhenFocused() = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy())
        val weak = listOf(hit("phrase", MemoryNodeKind.Phrase, 0.6f), hit("noun", MemoryNodeKind.NounTag, 0.5f))
        recall.suppress(0f)
        assertTrue(recall.select(weak).isEmpty())
        assertEquals(ranked, recall.select(ranked))
    }

    /** Smallest token count after a surfaced cue that reopens the gate at [level]. */
    private fun tokensToReopen(level: Float): Int = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy(), MemoryAttentionState(baselineLevel = level))
        assertEquals(ranked, recall.select(ranked))
        var used = 0
        do {
            recall.consumeTokens(16)
            used += 16
        } while (recall.select(ranked).isEmpty())
        used
    }

    @Test
    fun lowerDialNeedsMoreTokensToReopen() {
        val low = tokensToReopen(0.2f)
        val high = tokensToReopen(0.8f)
        assertTrue(low > high, "low=$low high=$high")
        assertTrue(low >= 2 * high, "low=$low high=$high")
    }

    @Test
    fun attentionIsIsolatedPerAgent() = runBlocking {
        val agents = PerAgentAttention()
        val a = agents.forAgent("task-a")
        val b = agents.forAgent("task-b")
        assertEquals(ranked, a.select(ranked))
        assertTrue(a.select(ranked).isEmpty()) // agent A throttled by its own rapid queries
        assertEquals(ranked, b.select(ranked)) // agent B unaffected
        assertTrue(agents.forAgent("task-a") === a)
    }

    @Test
    fun dialIsSettableDefaultAndPerAgent() = runBlocking {
        val agents = PerAgentAttention(defaultLevel = 0.2f)
        assertEquals(0.2f, agents.forAgent("x").currentState().baselineLevel)
        agents.defaultLevel = 0.9f
        assertEquals(0.9f, agents.forAgent("y").currentState().baselineLevel)
        agents.setLevel("x", 0.7f)
        assertEquals(0.7f, agents.forAgent("x").currentState().effectiveLevel)
        agents.suppress("x", 0.1f)
        assertEquals(0.1f, agents.forAgent("x").currentState().effectiveLevel)
        assertEquals(0.9f, agents.forAgent("y").currentState().effectiveLevel)
    }

    /** Tokens (in 16-token steps) until a suppressed agent's dial stops rising. */
    private fun tokensToRecover(baseline: Float, suppressTo: Float): Pair<Int, Float> = runBlocking {
        val agents = PerAgentAttention(defaultLevel = baseline)
        agents.suppress("a", suppressTo)
        val recall = agents.forAgent("a")
        var used = 0
        var last = recall.currentState().effectiveLevel
        while (true) {
            recall.consumeTokens(16)
            used += 16
            val now = recall.currentState().effectiveLevel
            if (now == last && recall.currentState().effectiveLevel >= baseline) break
            last = now
        }
        used to last
    }

    @Test
    fun deeperSuppressionNeedsMoreTokensToRecover() {
        val (shallow, _) = tokensToRecover(0.8f, 0.6f)
        val (deep, _) = tokensToRecover(0.8f, 0.0f)
        assertTrue(deep >= 2 * shallow, "deep=$deep shallow=$shallow")
    }

    @Test
    fun recoveryOvershootsBaseline() = runBlocking {
        val (_, rest) = tokensToRecover(0.5f, 0.1f)
        assertTrue(rest > 0.5f, "rest=$rest")
        // An explicit baseline change afterwards takes effect immediately and drops the overshoot.
        val agents = PerAgentAttention(defaultLevel = 0.5f)
        agents.suppress("a", 0.1f)
        agents.forAgent("a").consumeTokens(10_000)
        assertTrue(agents.forAgent("a").currentState().effectiveLevel > 0.5f)
        agents.setLevel("a", 0.3f)
        assertEquals(0.3f, agents.forAgent("a").currentState().effectiveLevel)
    }
}
