package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MemoryLineageCapTest {
    @Test
    fun aSmallLogIsReturnedAsIs() {
        val log = MemoryLineageLog(workflows = listOf(MemoryWorkflowRecord("a", null, 1)))
        assertSame(log, log.capped())
    }

    @Test
    fun anOverlongLogKeepsTheNewestRecordsOfEachList() {
        val over = MemoryLineageLog.MAX_WORKFLOW_RECORDS + 2
        val log = MemoryLineageLog(
            workflows = (1..over).map { MemoryWorkflowRecord("w$it", null, it.toLong()) },
            // Links between workflows that are kept, more of them than the limit.
            parents = (1..MemoryLineageLog.MAX_PARENT_RECORDS + 1).map { MemoryParentRecord("w$over", "w${over - 1}", it.toLong()) },
        )
        val capped = log.capped()
        assertEquals(MemoryLineageLog.MAX_WORKFLOW_RECORDS, capped.workflows.size)
        assertEquals("w3", capped.workflows.first().workflowId)
        assertEquals("w$over", capped.workflows.last().workflowId)
        assertEquals(MemoryLineageLog.MAX_PARENT_RECORDS, capped.parents.size)
        assertEquals(2L, capped.parents.first().recordedAtEpochMillis)
    }

    @Test
    fun aLinkToAWorkflowThatWasCutIsCutWithIt() {
        val over = MemoryLineageLog.MAX_WORKFLOW_RECORDS + 2
        val log = MemoryLineageLog(
            workflows = (1..over).map { MemoryWorkflowRecord("w$it", null, it.toLong()) },
            parents = listOf(
                MemoryParentRecord("w$over", "w1", 1), // w1 was evicted: it is no longer an ancestor
                MemoryParentRecord("w2", "w$over", 2), // w2 was evicted: nothing of it is read
                MemoryParentRecord("w$over", "w${over - 1}", 3),
            ),
        )
        assertEquals(listOf(MemoryParentRecord("w$over", "w${over - 1}", 3)), log.capped().parents)
    }
}
