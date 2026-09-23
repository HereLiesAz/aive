package com.hereliesaz.aive

import com.hereliesaz.geministrator.memory.MemoryAttentionPolicy
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
    fun rapidRepeatedRecallIsThrottledToTagCuesUntilTokensPass() = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy())
        // Fresh state: no cue yet, strong association -> full recall, cue interval reset.
        assertEquals(ranked, recall.select(ranked))
        assertEquals(0L, recall.currentState().tokensSinceCue)

        // Immediate repeat: interval not elapsed -> tag-level cues only.
        val throttled = recall.select(ranked)
        assertTrue(throttled.isNotEmpty())
        assertTrue(throttled.all { it.node.kind in AttentionGatedRecall.CUE_KINDS })

        // Token passage past the dial's cue interval re-opens deeper recall.
        recall.consumeTokens(5_000)
        assertEquals(ranked, recall.select(ranked))
    }

    @Test
    fun weakAssociationsStayCueOnlyAndSuppressionRecoversWithTokens() = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy(recoveryWindowTokens = 1_000))
        val weak = listOf(hit("phrase", MemoryNodeKind.Phrase, 0.6f), hit("noun", MemoryNodeKind.NounTag, 0.5f))
        recall.suppress(0f)
        // Focused: 0.6 < 0.92 threshold -> only the tag cue survives.
        assertEquals(listOf("noun"), recall.select(weak).map { it.node.id.value })
        // Tokens restore the dial toward baseline (0.5 -> threshold 0.685) and pass the interval,
        // but 0.6 is still below threshold; a strong association now opens.
        recall.consumeTokens(3_000)
        assertEquals(listOf("noun"), recall.select(weak).map { it.node.id.value })
        assertEquals(ranked, recall.select(ranked))
    }
}
