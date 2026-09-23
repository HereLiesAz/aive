package com.hereliesaz.aive

import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestrationPrefillChunkingTest {
    @Test
    fun chunksCoverPromptContiguouslyWithBoundedSize() {
        val chunks = AndroidOrchestrationAgentRuntime.prefillChunks(420, 32)
        assertEquals(14, chunks.size)
        assertEquals(0, chunks.first().first)
        assertEquals(419, chunks.last().last)
        assertTrue(chunks.all { it.count() in 1..32 })
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun shortPromptIsSingleChunk() {
        assertEquals(listOf(0 until 5), AndroidOrchestrationAgentRuntime.prefillChunks(5, 32))
    }

    @Test
    fun boundedChunkKeepsLogitsCopyFarBelowHeapCap() {
        val vocab = 151_936L
        val worstCaseBytes = AndroidOrchestrationAgentRuntime.PREFILL_CHUNK_TOKENS * vocab * Float.SIZE_BYTES
        assertTrue(worstCaseBytes < 32L * 1024 * 1024, "logits copy was $worstCaseBytes bytes")
    }

    @Test
    fun outOfMemoryIsConvertedToUserFacingException() {
        val failure = OrchestrationModelOutOfMemoryException(OrchestrationAgentRole.Planner, OutOfMemoryError())
        assertTrue(failure is Exception)
        assertTrue(failure.message!!.contains("ran out of memory"))
    }
}
