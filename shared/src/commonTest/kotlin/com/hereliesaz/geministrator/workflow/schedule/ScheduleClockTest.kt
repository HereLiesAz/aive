package com.hereliesaz.geministrator.workflow.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScheduleClockTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    // 2026-11-08 is a Sunday: epoch day 20765.
    private val sunday = 20_765L * day

    private fun schedule(
        id: String = "s",
        cadence: ScheduleCadence = ScheduleCadence.daily(7),
        enabled: Boolean = true,
        created: Long = sunday,
        last: Long? = null,
    ) = WorkflowSchedule(
        id = id, workflowId = "hive", name = id, objective = "Do it", cadence = cadence, enabled = enabled,
        createdAtEpochMillis = created, lastStartedAtEpochMillis = last,
    )

    @Test
    fun daysOfTheWeekCountFromMondayAndEpochDayZeroWasAThursday() {
        assertEquals(4, ScheduleClock.dayOfWeek(0))
        assertEquals(7, ScheduleClock.dayOfWeek(20_765))
        assertEquals(1, ScheduleClock.dayOfWeek(20_766))
    }

    @Test
    fun dailyIsTheNextTimeOfDayStrictlyAfter() {
        val daily = ScheduleCadence.daily(7)
        assertEquals(sunday + 7 * hour, ScheduleClock.nextSlot(daily, sunday + 2 * hour))
        assertEquals(sunday + day + 7 * hour, ScheduleClock.nextSlot(daily, sunday + 7 * hour), "a slot already reached is not due again")
        assertEquals(sunday + day + 7 * hour, ScheduleClock.nextSlot(daily, sunday + 9 * hour))
    }

    @Test
    fun weeklyIsTheNextMatchingDayAndHourStrictlyAfter() {
        val monday8 = ScheduleCadence.weekly(dayOfWeek = 1, hourUtc = 8)
        assertEquals(sunday + day + 8 * hour, ScheduleClock.nextSlot(monday8, sunday + 12 * hour))
        assertEquals(sunday + 8 * day + 8 * hour, ScheduleClock.nextSlot(monday8, sunday + day + 8 * hour))
        val sunday6 = ScheduleCadence.weekly(dayOfWeek = 7, hourUtc = 6)
        assertEquals(sunday + 7 * day + 6 * hour, ScheduleClock.nextSlot(sunday6, sunday + 6 * hour))
    }

    @Test
    fun everyHoursCountsFromTheLastStartOrFromWhenItWasMade() {
        val s = schedule(cadence = ScheduleCadence.everyHours(6), created = sunday)
        assertEquals(sunday + 6 * hour, ScheduleClock.nextDue(s))
        assertEquals(sunday + 10 * hour, ScheduleClock.nextDue(s.copy(lastStartedAtEpochMillis = sunday + 4 * hour)))
    }

    @Test
    fun aNewScheduleDoesNotStartForASlotThatAlreadyPassedAndAnOffOneNeverStarts() {
        val s = schedule(created = sunday + 9 * hour)
        assertTrue(!ScheduleClock.isDue(s, sunday + 10 * hour), "today's 07:00 was before it was made")
        assertTrue(ScheduleClock.isDue(s, sunday + day + 7 * hour))
        assertTrue(!ScheduleClock.isDue(s.copy(enabled = false), sunday + 10 * day))
    }

    @Test
    fun manyMissedSlotsStartOnceAndTheMostOverdueGoesFirst() {
        val late = schedule("late", ScheduleCadence.everyHours(1), created = sunday)
        val later = schedule("later", ScheduleCadence.everyHours(24), created = sunday)
        val now = sunday + 5 * day
        val first = decideSchedules(listOf(later, late), now, runActive = false)
        assertEquals("late", assertIs<ScheduleDecision.Start>(first).schedule.id)
        // Once started, its next slot is counted from now: one start, not one per missed slot.
        val after = late.copy(lastStartedAtEpochMillis = now)
        assertEquals("later", assertIs<ScheduleDecision.Start>(decideSchedules(listOf(after, later), now, false)).schedule.id)
        assertEquals(now + hour, ScheduleClock.nextDue(after))
    }

    @Test
    fun anActiveRunHoldsEverythingAndNothingDueIsIdle() {
        val due = schedule(created = sunday)
        val hold = decideSchedules(listOf(due), sunday + 3 * day, runActive = true)
        assertEquals(HoldReason.RunActive, assertIs<ScheduleDecision.Hold>(hold).reason)
        assertEquals(ScheduleDecision.Idle, decideSchedules(listOf(due), sunday + hour, runActive = false))
        assertEquals(ScheduleDecision.Idle, decideSchedules(emptyList(), sunday, runActive = false))
    }

    @Test
    fun outOfRangeCadencesAreRefused() {
        assertFailsWith<IllegalArgumentException> { ScheduleCadence.daily(24) }
        assertFailsWith<IllegalArgumentException> { ScheduleCadence.weekly(0, 1) }
        assertFailsWith<IllegalArgumentException> { ScheduleCadence.everyHours(0) }
    }

    @Test
    fun anOffsetMovesTheHourAndTheDayOfWeekWithIt() {
        // Daily 07:00 at UTC-6 is 13:00 UTC.
        val daily = ScheduleCadence.daily(7)
        assertEquals(sunday + 13 * hour, ScheduleClock.nextSlot(daily, sunday + 2 * hour, utcOffsetMinutes = -360))
        // Monday 01:00 at UTC-6 is Monday 07:00 UTC; Sunday 22:00 UTC is already Sunday 16:00 there.
        val monday1 = ScheduleCadence.weekly(dayOfWeek = 1, hourUtc = 1)
        assertEquals(sunday + day + 7 * hour, ScheduleClock.nextSlot(monday1, sunday + 22 * hour, utcOffsetMinutes = -360))
        // At UTC+5:30 it is already Monday 03:30 when UTC says Sunday 22:00: that Monday's 01:00 has passed, so next week's.
        assertEquals(sunday + 8 * day + 1 * hour - 330 * 60_000L, ScheduleClock.nextSlot(monday1, sunday + 22 * hour, utcOffsetMinutes = 330))
        assertFailsWith<IllegalArgumentException> {
            WorkflowSchedule(id = "s", workflowId = "h", name = "n", objective = "o", cadence = daily, utcOffsetMinutes = 15 * 60, createdAtEpochMillis = 0)
        }
    }
}
