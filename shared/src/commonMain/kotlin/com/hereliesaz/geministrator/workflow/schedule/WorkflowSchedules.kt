package com.hereliesaz.geministrator.workflow.schedule

import kotlinx.serialization.Serializable

/**
 * How often a scheduled hive runs (docs/Scheduled-Workflows.md). The hour is in the schedule's own
 * offset from UTC ([WorkflowSchedule.utcOffsetMinutes], 0 until the keeper sets one): the app reads no
 * time zone from the device.
 */
@Serializable
data class ScheduleCadence(
    val kind: Kind,
    /** For [Kind.EveryHours]: the gap between starts. */
    val everyHours: Int = 24,
    /** For [Kind.Daily] and [Kind.Weekly]: the hour of the day, 0 to 23, in the schedule's offset. */
    val hourUtc: Int = 7,
    /** For [Kind.Weekly]: 1 is Monday through 7, Sunday. */
    val dayOfWeek: Int = 1,
) {
    enum class Kind { EveryHours, Daily, Weekly }

    init {
        require(everyHours in 1..24 * 31) { "A gap of $everyHours hours is out of range" }
        require(hourUtc in 0..23) { "Hour $hourUtc is out of range" }
        require(dayOfWeek in 1..7) { "Day $dayOfWeek is out of range" }
    }

    companion object {
        fun everyHours(hours: Int) = ScheduleCadence(Kind.EveryHours, everyHours = hours)
        fun daily(hourUtc: Int) = ScheduleCadence(Kind.Daily, hourUtc = hourUtc)
        fun weekly(dayOfWeek: Int, hourUtc: Int) = ScheduleCadence(Kind.Weekly, hourUtc = hourUtc, dayOfWeek = dayOfWeek)
    }
}

/**
 * Which daylight-saving rule moves a schedule's hour (docs/Scheduled-Workflows.md). The app reads no time zone from the
 * device, so a schedule's [WorkflowSchedule.utcOffsetMinutes] is its standard (winter) offset and this adds an hour in
 * summer by a fixed rule. Other regions keep [None] and move the offset by hand when their clocks change.
 */
@Serializable
enum class DaylightSaving {
    /** The offset never changes. */
    None,

    /** The United States and Canada: second Sunday of March 02:00 to first Sunday of November 02:00, local time. */
    UnitedStates,

    /** The European Union: last Sunday of March 01:00 UTC to last Sunday of October 01:00 UTC. */
    Europe,
}

/**
 * A hive set to run by itself. It is off until the keeper turns it on, and only ever starts the hive
 * as drawn, with [objective] as its run's objective, in the project that is open; it cannot approve
 * anything, so a hive with an approval waits for the keeper like any other.
 */
@Serializable
data class WorkflowSchedule(
    val id: String,
    val workflowId: String,
    val name: String,
    val objective: String,
    val cadence: ScheduleCadence,
    val enabled: Boolean = false,
    /** Whether the run's objective is followed by the swarm's evidence, computed when it starts (SELF_IMPROVEMENT.md). */
    val withEvidence: Boolean = false,
    /** The keeper's standard (winter) offset from UTC in minutes (New Orleans is -360), for daily and weekly hours. */
    val utcOffsetMinutes: Int = 0,
    /** The rule that adds an hour in summer to [utcOffsetMinutes]; none until the keeper picks one. */
    val daylightSaving: DaylightSaving = DaylightSaving.None,
    /** When it was made: the first start is the first slot after this, never one already past. */
    val createdAtEpochMillis: Long,
    val lastStartedAtEpochMillis: Long? = null,
    /** What the last decision was, in plain words, for the keeper to read. */
    val lastNote: String? = null,
    /**
     * The keeper asked for one start now ("Run now"). It is due at the next look whether the schedule is on or not, and
     * the start clears it; it never turns the schedule on.
     */
    val runNowRequested: Boolean = false,
    /**
     * A fingerprint of the hive's definition ([ScheduleVetting.fingerprint]) as it was when the keeper made or turned on the
     * schedule. A schedule is bound to a hive's id, not its content: when the hive differs from this at a start, the run is
     * held and the schedule turned off. Null on a schedule saved before this existed: it is taken from the hive on first sight.
     */
    val definitionFingerprint: String? = null,
) {
    init {
        require(id.isNotBlank() && workflowId.isNotBlank()) { "A schedule needs an id and a hive" }
        require(objective.isNotBlank()) { "A schedule needs an objective" }
        require(utcOffsetMinutes in -14 * 60..14 * 60) { "An offset of $utcOffsetMinutes minutes is out of range" }
    }
}

/** When schedules are due, as pure functions of the clock. */
object ScheduleClock {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /**
     * The first slot strictly after [after] for [cadence], in epoch milliseconds, with the day and hour read at
     * [utcOffsetMinutes] from UTC, plus an hour in summer under [daylightSaving]. A wall time the clocks skip runs at the
     * first instant after the gap; one they repeat runs at its first occurrence.
     */
    fun nextSlot(
        cadence: ScheduleCadence,
        after: Long,
        utcOffsetMinutes: Int = 0,
        daylightSaving: DaylightSaving = DaylightSaving.None,
    ): Long {
        if (cadence.kind == ScheduleCadence.Kind.EveryHours) return after + cadence.everyHours * HOUR
        val standard = utcOffsetMinutes * 60_000L
        val before = offsetAt(after, standard, daylightSaving)
        val local = nextLocalSlot(cadence, after + before)
        val inForce = offsetAt(local - before, standard, daylightSaving)
        var slot = local - inForce
        if (offsetAt(slot, standard, daylightSaving) != inForce || slot <= after) slot = local - before
        return slot
    }

    /** The offset from UTC in force at [instant], in milliseconds. */
    fun offsetAt(instant: Long, standardMillis: Long, rule: DaylightSaving): Long =
        if (rule != DaylightSaving.None && inSummerTime(instant, standardMillis, rule)) standardMillis + HOUR else standardMillis

    private fun inSummerTime(instant: Long, standard: Long, rule: DaylightSaving): Boolean {
        val year = civilYear((instant + standard).floorDiv(DAY))
        return when (rule) {
            DaylightSaving.None -> false
            DaylightSaving.UnitedStates -> {
                val start = (nthSunday(year, 3, 2)) * DAY + 2 * HOUR - standard
                val end = (nthSunday(year, 11, 1)) * DAY + 2 * HOUR - (standard + HOUR)
                instant in start until end
            }
            DaylightSaving.Europe -> {
                val start = lastSunday(year, 3) * DAY + HOUR
                val end = lastSunday(year, 10) * DAY + HOUR
                instant in start until end
            }
        }
    }

    /** The epoch day of the [n]th Sunday of [month] (1 to 4). */
    private fun nthSunday(year: Long, month: Int, n: Int): Long {
        val first = daysFromCivil(year, month, 1)
        return first + (7 - dayOfWeek(first)) % 7 + 7L * (n - 1)
    }

    /** The epoch day of the last Sunday of a 31-day [month]. */
    private fun lastSunday(year: Long, month: Int): Long {
        val last = daysFromCivil(year, month, 31)
        return last - dayOfWeek(last) % 7
    }

    // Civil calendar arithmetic (Howard Hinnant), proleptic Gregorian.
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civilYear(epochDay: Long): Long {
        val z = epochDay + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        return yoe + era * 400 + if (month <= 2) 1 else 0
    }

    private fun nextLocalSlot(cadence: ScheduleCadence, after: Long): Long = when (cadence.kind) {
        ScheduleCadence.Kind.EveryHours -> after + cadence.everyHours * HOUR
        ScheduleCadence.Kind.Daily -> {
            val today = after.floorDiv(DAY) * DAY + cadence.hourUtc * HOUR
            if (today > after) today else today + DAY
        }
        ScheduleCadence.Kind.Weekly -> {
            var day = after.floorDiv(DAY)
            while (true) {
                val slot = day * DAY + cadence.hourUtc * HOUR
                if (slot > after && dayOfWeek(day) == cadence.dayOfWeek) break
                day += 1
            }
            day * DAY + cadence.hourUtc * HOUR
        }
    }

    /** 1 is Monday through 7: epoch day 0, 1970-01-01, was a Thursday. */
    fun dayOfWeek(epochDay: Long): Int = ((epochDay + 3).mod(7L) + 1).toInt()

    /** When [schedule] is next due: the slot after its last start, or after it was made. */
    fun nextDue(schedule: WorkflowSchedule): Long =
        nextSlot(
            schedule.cadence,
            schedule.lastStartedAtEpochMillis ?: schedule.createdAtEpochMillis,
            schedule.utcOffsetMinutes,
            schedule.daylightSaving,
        )

    fun isDue(schedule: WorkflowSchedule, now: Long): Boolean =
        schedule.runNowRequested || (schedule.enabled && now >= nextDue(schedule))
}

/** What one look at the schedules decides. */
sealed interface ScheduleDecision {
    /** Nothing is due. */
    data object Idle : ScheduleDecision

    /** [schedule] is due and nothing stands in its way: start it. */
    data class Start(val schedule: WorkflowSchedule) : ScheduleDecision

    /** [schedule] is due but [reason] holds it; it is looked at again next time. */
    data class Hold(val schedule: WorkflowSchedule, val reason: HoldReason) : ScheduleDecision

    /** [schedule] was due and its hive may not be started on a schedule as it is now: it was turned off with [note] on it. */
    data class Refused(val schedule: WorkflowSchedule, val note: String) : ScheduleDecision
}

enum class HoldReason {
    /** Another run has not finished (it may be waiting on a person); one run is active at a time. */
    RunActive,
}

/**
 * Looks at [schedules] at [now]. At most one starts per look, the one most overdue; a schedule that
 * missed many slots (the app was closed) starts once, not once per slot. Pure.
 */
fun decideSchedules(schedules: List<WorkflowSchedule>, now: Long, runActive: Boolean): ScheduleDecision {
    val due = schedules.filter { ScheduleClock.isDue(it, now) }
        .sortedWith(compareBy({ if (it.runNowRequested) Long.MIN_VALUE else ScheduleClock.nextDue(it) }, { it.id }))
    val first = due.firstOrNull() ?: return ScheduleDecision.Idle
    return if (runActive) ScheduleDecision.Hold(first, HoldReason.RunActive) else ScheduleDecision.Start(first)
}
