package com.hereliesaz.geministrator.log

import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.memory.MemoryConsolidationResult
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryQueueId
import com.hereliesaz.geministrator.memory.logText
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.workflow.SessionActivityFeeds
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogJournalTest {
    @Test
    fun keepsTheNewestLinesInOrderAndDatesThem() {
        var clock = 1_000L
        val journal = LogJournal(maxLines = 3, nowEpochMillis = { clock++ })
        (1..5).forEach { journal.record("tag", "line $it") }

        assertEquals(listOf("line 3", "line 4", "line 5"), journal.lines.value.map { it.text })
        assertEquals(listOf(1_002L, 1_003L, 1_004L), journal.lines.value.map { it.atEpochMillis })
    }

    @Test
    fun scrubsKeysBeforeKeepingAndCutsLongLines() {
        val journal = LogJournal(maxLineChars = 20)
        journal.record("AiveLaunch", "sk-abcdefghijklmnopqrstuvwxyz1234 leaked")
        journal.record("AiveLaunch", "x".repeat(100))
        journal.record("AiveLaunch", "   ")

        assertEquals(2, journal.lines.value.size)
        assertTrue("sk-abc" !in journal.lines.value[0].text)
        assertEquals(20, journal.lines.value[1].text.length)
        assertTrue(journal.lines.value[1].text.endsWith("…"))
    }

    @Test
    fun survivesARestartWhenGivenSettings() {
        val settings = InMemorySettings()
        val first = LogJournal(settings = settings, storageKey = "log", nowEpochMillis = { 7L })
        first.record("tag", "first\nsecond\tthird \\ done")

        val second = LogJournal(settings = settings, storageKey = "log")
        assertEquals(listOf(LogLine(7L, "tag", "first\nsecond\tthird \\ done")), second.lines.value)

        second.clear()
        assertTrue(LogJournal(settings = settings, storageKey = "log").lines.value.isEmpty())
    }

    @Test
    fun theAppLogSurvivesDesktopsValueLimit() {
        // java.util.prefs refuses a value over 8,192 characters; a full journal is far larger.
        val inner = InMemorySettings()
        val limited = object : com.russhwolf.settings.Settings by inner {
            override fun putString(key: String, value: String) {
                require(value.length <= 8_192) { "Value too long" }
                inner.putString(key, value)
            }
        }
        val store = AppLog.journalSettings(limited)
        val first = LogJournal(settings = store, storageKey = AppLog.SETTINGS_KEY)
        repeat(60) { first.record("tag", "line $it " + "x".repeat(400)) }

        val second = LogJournal(settings = store, storageKey = AppLog.SETTINGS_KEY)
        assertEquals(60, second.lines.value.size)
        assertTrue(second.lines.value.last().text.startsWith("line 59"))
    }

    @Test
    fun aLineKeepsItsKeyWhenOlderOnesAreEvicted() {
        val lines = (1..5).map { LogLine(it.toLong(), "s", "t") } + LogLine(5L, "s", "t")
        val keys = com.hereliesaz.geministrator.stableLogKeys(lines)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.drop(2), com.hereliesaz.geministrator.stableLogKeys(lines.drop(2)))
    }

    @Test
    fun aDamagedStoreIsSkippedLineByLine() {
        val settings = InMemorySettings()
        settings.putString("log", "not a line\n5\tscope\tgood\nx\ty\tz")
        assertEquals(listOf(LogLine(5L, "scope", "good")), LogJournal(settings = settings, storageKey = "log").lines.value)
    }

    @Test
    fun timesAreUtcAndFixedWidth() {
        assertEquals("1970-01-01 00:00:00.000Z", formatLogTime(0))
        assertEquals("2026-11-07 12:51:03.412Z", formatLogTime(1_794_055_863_412L))
        assertEquals("2000-02-29 23:59:59.999Z", formatLogTime(951_868_799_999L))
        assertEquals("1969-12-31 23:59:59.999Z", formatLogTime(-1))
    }

    @Test
    fun runEventsKeepTheWholeReasonAndAreOrderedByTime() {
        val run = WorkflowRunId("r")
        val reason = "because ".repeat(30)
        val lines = runEventLines(
            events = listOf(
                TaskFailed(run, TaskDefinitionId("build"), reason, 30),
                TaskStarted(run, TaskDefinitionId("build"), 1, 20),
                WorkflowCreated(run, objective = "ship it", occurredAtEpochMillis = 10),
                TaskCompleted(run, TaskDefinitionId("docs"), 40),
            ),
            taskName = { if (it.value == "build") "Build" else it.value },
        )

        assertEquals(listOf(10L, 20L, 30L, 40L), lines.map { it.atEpochMillis })
        assertEquals("workflow", lines[0].scope)
        assertEquals("Build", lines[1].scope)
        assertEquals("Failed: $reason", lines[2].text)
        assertTrue(lines[2].text.length > 80)
        assertEquals("docs", lines[3].scope)
    }

    @Test
    fun agentActivityIsThisRunsOnlyAndInterleavedByTime() {
        var clock = 0L
        val feeds = SessionActivityFeeds(nowEpochMillis = { clock++ })
        val mine = SessionActivityKey(TaskRunId("t1"), ProviderRunId("p1"))
        val theirs = SessionActivityKey(TaskRunId("other"), ProviderRunId("p2"))
        val also = SessionActivityKey(TaskRunId("t2"), ProviderRunId("p3"))
        feeds.record(mine, SessionActivityKind.Thinking, "weighing")
        feeds.record(theirs, SessionActivityKind.Progress, "elsewhere")
        feeds.record(also, SessionActivityKind.Progress, "running tests")
        feeds.record(mine, SessionActivityKind.Progress, "editing")

        val lines = agentActivityLines(
            feeds.feeds.value,
            sessionScope(mapOf(TaskRunId("t1") to "Architect", TaskRunId("t2") to "Engineer")),
        )

        assertEquals(
            listOf(
                "Architect" to "[thinking] weighing",
                "Engineer" to "[step] running tests",
                "Architect" to "[step] editing",
            ),
            lines.map { it.scope to it.text },
        )
        assertEquals(listOf(0L, 2L, 3L), lines.map { it.atEpochMillis })
    }

    @Test
    fun activityHoldsMoreThanSixtyLinesPerSession() {
        val feeds = SessionActivityFeeds()
        val key = SessionActivityKey(TaskRunId("t"), ProviderRunId("p"))
        repeat(200) { feeds.record(key, SessionActivityKind.Progress, "step $it") }
        assertEquals(200, feeds.lines(key).size)
    }

    @Test
    fun memorySaysWhatItDidAndNothingWhenIdle() {
        assertNull(MemoryConsolidationResult.Idle.logText())
        assertNull(MemoryConsolidationResult.PairSummaries(0).logText())
        assertEquals("Wrote 2 pair summaries", MemoryConsolidationResult.PairSummaries(2).logText())
        val failed = MemoryConsolidationResult.Failed(MemoryQueueId("q1"), MemoryConsolidationStage.entries.first(), "model offline")
        assertTrue(failed.logText()!!.endsWith("(queue q1): model offline"))
        assertEquals("Done with queue q1", MemoryConsolidationResult.Completed(MemoryQueueId("q1")).logText())
    }
}
