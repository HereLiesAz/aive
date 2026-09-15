package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexicalMemoryToolTest {
    @Test
    fun freeTextGripAddsNormalizedActionCueButReturnsOriginalQuery() = runBlocking {
        val delegate = CapturingMemoryTool()
        val tool = LexicalMemoryTool(delegate)
        val original = MemoryQuery("service writes cache")

        val result = tool.grip(original)

        assertEquals(original, result.query)
        assertTrue(delegate.lastFreeTextQuery!!.text.contains("write"))
        assertTrue(delegate.lastFreeTextQuery!!.text.startsWith(original.text))
    }

    @Test
    fun tagGripAddsNormalizedLexicalAddresses() = runBlocking {
        val delegate = CapturingMemoryTool()
        val tool = LexicalMemoryTool(delegate)
        val original = MemoryTagQuery(tags = listOf("writes"))

        tool.grip(original)

        val captured = delegate.lastTagQuery!!
        assertTrue("writes" in captured.tags)
        assertTrue("write" in captured.tags)
    }

    private class CapturingMemoryTool : MemoryTool {
        var lastFreeTextQuery: MemoryQuery? = null
        var lastTagQuery: MemoryTagQuery? = null

        override suspend fun bank(request: MemoryBankRequest): MemoryQueueEntry = error("not used")

        override suspend fun grip(query: MemoryQuery): MemoryRecallBundle {
            lastFreeTextQuery = query
            return MemoryRecallBundle(query, emptyList())
        }

        override suspend fun grip(query: MemoryTagQuery): MemoryRecallBundle {
            lastTagQuery = query
            return MemoryRecallBundle(
                MemoryQuery(
                    text = query.tags.joinToString(" "),
                    resolution = query.resolution,
                    maxResults = query.maxResults,
                    includeConflicts = query.includeConflicts,
                ),
                emptyList(),
            )
        }

        override suspend fun expand(
            nodeId: MemoryNodeId,
            resolution: MemoryResolution,
            maxResults: Int,
            includeConflicts: Boolean,
        ): List<MemoryRecallHit> = emptyList()
    }
}
