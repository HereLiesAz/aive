package com.hereliesaz.geministrator.workflow.schedule

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    @Test
    fun aRunNowRequestStartsAScheduleThatIsOffOnceAndNeverTurnsItOn() = runTest {
        val f = Fixture(base + 2 * hour)
        f.store.put(due().copy(enabled = false, runNowRequested = true))

        assertIs<ScheduleDecision.Start>(f.ticker.tick())
        assertEquals(listOf("hive-s"), f.started)
        val saved = f.store.all().single()
        assertEquals(false, saved.enabled, "asking for one run does not turn the schedule on")
        assertEquals(false, saved.runNowRequested, "and the request is spent")
        assertEquals(ScheduleDecision.Idle, f.ticker.tick())
        f.now += 100 * hour
        assertEquals(ScheduleDecision.Idle, f.ticker.tick(), "it never comes round again on its own")
        assertEquals(1, f.started.size)
    }

    @Test
    fun aRunNowRequestWaitsForTheActiveRunAndKeepsItsPlaceUntilItStarts() = runTest {
        val f = Fixture(base + 2 * hour, active = true)
        f.store.put(due().copy(enabled = false, runNowRequested = true))

        assertIs<ScheduleDecision.Hold>(f.ticker.tick())
        assertEquals(true, f.store.all().single().runNowRequested)
        f.active = false
        assertIs<ScheduleDecision.Start>(f.ticker.tick())
        assertEquals(1, f.started.size)
    }

    /** Reads, then yields, so a second look can read the same state before the first has written anything. */
    private class SlowReadStore(private val inner: WorkflowScheduleStore) : WorkflowScheduleStore by inner {
        override suspend fun all(): List<WorkflowSchedule> = inner.all().also { yield() }
    }

    @Test
    fun aScheduleTurnedOffOrRemovedDuringASlowStartIsNotBroughtBack() = runTest {
        val store = InMemoryWorkflowScheduleStore()
        store.put(due("a"))
        store.put(due("b"))
        val ticker = ScheduleTicker(
            store = store, now = { base + 2 * hour }, runActive = { false },
            // While the launch is awaited, the keeper turns "a" off and removes "b".
            start = { s -> if (s.id == "a") store.update("a") { it.copy(enabled = false) } else store.remove("b") },
            log = {},
        )

        ticker.tick()
        ticker.tick()

        val a = store.all().single()
        assertEquals("a", a.id)
        assertEquals(false, a.enabled, "the keeper's change during the launch is kept")
        assertEquals("Started.", a.lastNote)
        assertEquals(base + 2 * hour, a.lastStartedAtEpochMillis)
    }

    @Test
    fun twoLooksThatReadTheSameDueScheduleStartItOnce() = runTest {
        val store = SlowReadStore(InMemoryWorkflowScheduleStore())
        store.put(due())
        val started = mutableListOf<String>()
        fun ticker() = ScheduleTicker(store = store, now = { base + 2 * hour }, runActive = { false }, start = { started += it.id }, log = {})
        val first = ticker()
        val second = ticker()

        listOf(launch { first.tick() }, launch { second.tick() }).joinAll()

        assertEquals(listOf("s"), started, "the second look finds the slot already claimed")
    }

    @Test
    fun aHiveThatChangedSinceItWasScheduledIsHeldAndTheScheduleTurnedOff() = runTest {
        val f = Fixture(base + 2 * hour)
        f.store.put(due().copy(definitionFingerprint = "old"))
        var vetting = HiveVetting("new")
        val ticker = ScheduleTicker(f.store, { f.now }, { false }, { f.started += it.workflowId }, { f.log += it }, vet = { vetting })

        val decision = ticker.tick()

        assertIs<ScheduleDecision.Refused>(decision)
        assertEquals(emptyList(), f.started)
        val held = f.store.all().single()
        assertEquals(false, held.enabled)
        assertEquals(ScheduleVetting.CHANGED_NOTE, held.lastNote)
        assertNull(held.lastStartedAtEpochMillis)

        // The keeper reviews and turns it on again, which takes the hive as it is now.
        f.store.update("s") { it.copy(enabled = true, definitionFingerprint = vetting.fingerprint) }
        assertIs<ScheduleDecision.Start>(ticker.tick())
        assertEquals(listOf("hive-s"), f.started)
    }

    @Test
    fun aScheduleSavedWithoutAFingerprintTakesTheHiveOnFirstSight() = runTest {
        val f = Fixture(base + 2 * hour)
        f.store.put(due())
        val ticker = ScheduleTicker(f.store, { f.now }, { false }, { f.started += it.workflowId }, { f.log += it }, vet = { HiveVetting("now") })

        assertIs<ScheduleDecision.Start>(ticker.tick())

        assertEquals("now", f.store.all().single().definitionFingerprint)
        assertEquals(1, f.started.size)
    }

    @Test
    fun aHiveThatMayNeverBeScheduledIsRefusedWithItsReason() = runTest {
        val f = Fixture(base + 2 * hour)
        f.store.put(due())
        val ticker = ScheduleTicker(f.store, { f.now }, { false }, { f.started += it.workflowId }, { f.log += it }, vet = { HiveVetting("x", refusal = ScheduleVetting.FILM_REFUSAL) })

        assertIs<ScheduleDecision.Refused>(ticker.tick())

        assertEquals(emptyList(), f.started)
        assertEquals(ScheduleVetting.FILM_REFUSAL, f.store.all().single().lastNote)
        assertEquals(false, f.store.all().single().enabled)
    }

    @Test
    fun anUnreadableListStartsNothingAndIsSaidOnce() = runTest {
        val settings = com.hereliesaz.geministrator.persistence.InMemorySettings()
        settings.putString(SettingsWorkflowScheduleStore.DEFAULT_KEY, "{not json")
        val log = mutableListOf<String>()
        val ticker = ScheduleTicker(SettingsWorkflowScheduleStore(settings), { base }, { false }, { error("must not start") }, { log += it })

        assertEquals(ScheduleDecision.Idle, ticker.tick())
        assertEquals(ScheduleDecision.Idle, ticker.tick())

        assertEquals(1, log.size)
    }
}
