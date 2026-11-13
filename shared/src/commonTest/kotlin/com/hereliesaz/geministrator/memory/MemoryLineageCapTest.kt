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
            parents = (1..MemoryLineageLog.MAX_PARENT_RECORDS + 1).map { MemoryParentRecord("w$it", "p", it.toLong()) },
        )
        val capped = log.capped()
        assertEquals(MemoryLineageLog.MAX_WORKFLOW_RECORDS, capped.workflows.size)
        assertEquals("w3", capped.workflows.first().workflowId)
        assertEquals("w$over", capped.workflows.last().workflowId)
        assertEquals(MemoryLineageLog.MAX_PARENT_RECORDS, capped.parents.size)
        assertEquals("w2", capped.parents.first().workflowId)
    }
}
