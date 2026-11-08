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
    /** The keeper's offset from UTC in minutes (New Orleans is -360 in winter), for daily and weekly hours. */
    val utcOffsetMinutes: Int = 0,
    /** When it was made: the first start is the first slot after this, never one already past. */
    val createdAtEpochMillis: Long,
    val lastStartedAtEpochMillis: Long? = null,
    /** What the last decision was, in plain words, for the keeper to read. */
    val lastNote: String? = null,
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
     * The first slot strictly after [after] for [cadence], in epoch milliseconds, with the day and hour
     * read at [utcOffsetMinutes] from UTC.
     */
    fun nextSlot(cadence: ScheduleCadence, after: Long, utcOffsetMinutes: Int = 0): Long {
        val offset = utcOffsetMinutes * 60_000L
        return nextLocalSlot(cadence, after + offset) - offset
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
        nextSlot(schedule.cadence, schedule.lastStartedAtEpochMillis ?: schedule.createdAtEpochMillis, schedule.utcOffsetMinutes)

    fun isDue(schedule: WorkflowSchedule, now: Long): Boolean = schedule.enabled && now >= nextDue(schedule)
}

/** What one look at the schedules decides. */
sealed interface ScheduleDecision {
    /** Nothing is due. */
    data object Idle : ScheduleDecision

    /** [schedule] is due and nothing stands in its way: start it. */
    data class Start(val schedule: WorkflowSchedule) : ScheduleDecision

    /** [schedule] is due but [reason] holds it; it is looked at again next time. */
    data class Hold(val schedule: WorkflowSchedule, val reason: HoldReason) : ScheduleDecision
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
        .sortedWith(compareBy({ ScheduleClock.nextDue(it) }, { it.id }))
    val first = due.firstOrNull() ?: return ScheduleDecision.Idle
    return if (runActive) ScheduleDecision.Hold(first, HoldReason.RunActive) else ScheduleDecision.Start(first)
}
