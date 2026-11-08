package com.hereliesaz.geministrator.workflow.schedule

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScheduleTickerTest {
    private val hour = 3_600_000L
    private val base = 20_765L * 24 * hour

    private fun due(id: String = "s") =
        WorkflowSchedule(
            id = id, workflowId = "hive-$id", name = "Nightly $id", objective = "Report",
            cadence = ScheduleCadence.everyHours(1), enabled = true, createdAtEpochMillis = base,
        )

    private class Fixture(var now: Long, var active: Boolean = false, var failWith: String? = null) {
        val store = InMemoryWorkflowScheduleStore()
        val started = mutableListOf<String>()
        val log = mutableListOf<String>()
        val ticker = ScheduleTicker(
            store = store, now = { now }, runActive = { active },
            start = { s -> failWith?.let { error(it) }; started += s.workflowId },
            log = { log += it },
        )
    }

    @Test
    fun aDueScheduleStartsOnceIsRecordedAndIsNotDueAgainUntilItsNextSlot() = runTest {
        val f = Fixture(base + 2 * hour)
        f.store.put(due())

        assertIs<ScheduleDecision.Start>(f.ticker.tick())
        assertEquals(listOf("hive-s"), f.started)
        val saved = f.store.all().single()
        assertEquals(base + 2 * hour, saved.lastStartedAtEpochMillis)
        assertEquals("Started.", saved.lastNote)
        assertTrue(f.log.single().contains("Nightly s") && f.log.single().contains("Started."))

        assertEquals(ScheduleDecision.Idle, f.ticker.tick(), "the same slot is not started twice")
        f.now += hour
        assertIs<ScheduleDecision.Start>(f.ticker.tick())
        assertEquals(2, f.started.size)
    }

    @Test
    fun anActiveRunHoldsItOnceInTheLogAndItStartsWhenTheRunEnds() = runTest {
        val f = Fixture(base + 2 * hour, active = true)
        f.store.put(due())

        assertIs<ScheduleDecision.Hold>(f.ticker.tick())
        assertIs<ScheduleDecision.Hold>(f.ticker.tick())
        assertEquals(1, f.log.size, "said once, not every look")
        assertEquals(emptyList(), f.started)
        assertTrue(f.store.all().single().lastNote!!.startsWith("Due, but"))

        f.active = false
        assertIs<ScheduleDecision.Start>(f.ticker.tick())
        assertEquals(listOf("hive-s"), f.started)
    }

    @Test
    fun aStartThatFailsIsRecordedWithItsReasonAndNotRetriedEveryLook() = runTest {
        val f = Fixture(base + 2 * hour, failWith = "no connected agent")
        f.store.put(due())

        f.ticker.tick()

        val saved = f.store.all().single()
        assertEquals("Could not start: no connected agent", saved.lastNote)
        assertEquals(base + 2 * hour, saved.lastStartedAtEpochMillis)
        assertEquals(ScheduleDecision.Idle, f.ticker.tick())
    }

    @Test
    fun aScheduleThatIsOffNeverStarts() = runTest {
        val f = Fixture(base + 100 * hour)
        f.store.put(due().copy(enabled = false))
        assertEquals(ScheduleDecision.Idle, f.ticker.tick())
        assertEquals(emptyList(), f.started)
    }
}
