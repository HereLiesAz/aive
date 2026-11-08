package com.hereliesaz.geministrator.workflow.refinement

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LadderDatasetSummaryTest {
    private fun level(
        id: String, ladder: String, level: Int, at: Long, structured: Boolean = true, valid: Boolean = true, gate: String? = "approve",
    ) = LadderDatasetRecord(
        id = id, kind = LadderDatasetRecord.KIND_LEVEL, ladder = ladder, taskId = "t$level", level = level, total = 3,
        objective = "o", artifact = "a", structured = structured, valid = valid, gateVerdict = gate, capturedAtEpochMillis = at,
    )

    private fun outcome(id: String, ladder: String, at: Long) = LadderDatasetRecord(
        id = id, kind = LadderDatasetRecord.KIND_OUTCOME, ladder = ladder, taskId = "t", finalStatus = "Completed",
        verifierVerdict = "approve", capturedAtEpochMillis = at,
    )

    @Test
    fun theSummaryCountsWhatTheCorpusBuilderWouldKeep() {
        val records = listOf(
            level("1", "run1/a", 1, 10), level("2", "run1/a", 2, 11), level("3", "run2/a", 1, 12),
            level("4", "run2/a", 2, 13, gate = "reject"), level("5", "run3/a", 1, 14, structured = false),
            level("6", "run3/a", 2, 15, valid = false), outcome("7", "run1/a", 16),
        )
        val summary = LadderDatasetSummary.of(records)
        assertEquals(7, summary.records)
        assertEquals(3, summary.ladders)
        assertEquals(mapOf(1 to 2, 2 to 1), summary.usablePerLevel)
        assertEquals(5, summary.structured)
        assertEquals(1, summary.turnedBack)
        assertEquals(1, summary.outcomes)
        assertEquals(LadderDatasetSummary.of(emptyList()), LadderDatasetSummary(0, 0, emptyMap(), 0, 0, 0))
    }

    @Test
    fun recordsReadAsLinesAndTheCaptureReportsNewestFirst(): Unit = runBlocking {
        assertTrue("level 2/3 of run2/a: structured, sound, gate reject" in level("4", "run2/a", 2, 13, gate = "reject").line())
        assertTrue("ladder run1/a ended Completed, verifier approve" in outcome("7", "run1/a", 16).line())

        val store = SettingsLadderDatasetStore(MapSettings())
        val capture = LadderDatasetCapture(MapSettings(), store)
        store.append(listOf(level("1", "r/a", 1, 10), level("2", "r/a", 2, 30), level("3", "r/a", 3, 20)))
        assertEquals(listOf("2", "3"), capture.recent(2).map { it.id })
        assertEquals(3, capture.summary().records)
        assertEquals(false, capture.enabled)
        capture.enabled = true
        assertEquals(true, capture.enabled)
    }
}
