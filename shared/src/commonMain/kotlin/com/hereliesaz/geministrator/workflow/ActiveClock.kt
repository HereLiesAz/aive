package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskClock

/**
 * Moves a [TaskClock]. Pure: every call is given `now`, so tests drive it with a fake clock. Active time is
 * elapsed time minus the intervals that do not count (waiting on the keeper, the app being away).
 */
object ActiveClock {
    /** A gap longer than this between two looks means the app was closed or the machine slept. */
    const val AWAY_AFTER_MILLIS = 2 * 60_000L

    /** The heartbeat is rewritten at most this often. */
    const val HEARTBEAT_MILLIS = 60_000L

    fun begin(attempt: Int, now: Long, auto: Allowance): TaskClock = TaskClock(
        attempt = attempt,
        startedAtEpochMillis = now,
        autoMillis = auto.millis,
        autoHardMillis = auto.hardMillis,
        lastSeenEpochMillis = now,
        progressAtEpochMillis = now,
    )

    fun auto(clock: TaskClock): Allowance =
        Allowance(clock.autoMillis, clock.autoHardMillis, if (clock.autoMillis > 0) Basis.Baseline else Basis.None)

    /** Active milliseconds; never negative, so a clock that stepped back counts as no progress. */
    fun active(clock: TaskClock, now: Long): Long {
        val open = clock.waitingSinceEpochMillis?.let { maxOf(0L, now - it) } ?: 0L
        return maxOf(0L, now - clock.startedAtEpochMillis - clock.waitedMillis - open)
    }

    /**
     * Looks at the clock at [now]. [counting] is false while the creature waits on the keeper; [progressAt] is the
     * last sign of life the Source gave, if any. Returns the same instance when nothing needs writing, so the
     * caller persists only a change: a transition, or a heartbeat at most once a minute.
     */
    fun step(clock: TaskClock, now: Long, counting: Boolean, progressAt: Long? = null): TaskClock {
        // A clock that stepped back is held where it was.
        val at = maxOf(now, clock.lastSeenEpochMillis)
        var next = clock
        val gap = at - clock.lastSeenEpochMillis
        val waitOpen = clock.waitingSinceEpochMillis != null
        if (gap > AWAY_AFTER_MILLIS && !waitOpen) {
            next = next.copy(waitedMillis = next.waitedMillis + gap, lastSeenEpochMillis = at)
        }
        if (!counting && next.waitingSinceEpochMillis == null) {
            next = next.copy(waitingSinceEpochMillis = at, lastSeenEpochMillis = at)
        } else if (counting && next.waitingSinceEpochMillis != null) {
            next = next.copy(
                waitedMillis = next.waitedMillis + maxOf(0L, at - next.waitingSinceEpochMillis!!),
                waitingSinceEpochMillis = null,
                lastSeenEpochMillis = at,
            )
        }
        if (progressAt != null && progressAt > next.progressAtEpochMillis) {
            // A sign of life is written with the next heartbeat, not on its own.
            if (at - next.lastSeenEpochMillis >= HEARTBEAT_MILLIS) {
                next = next.copy(progressAtEpochMillis = minOf(progressAt, at), lastSeenEpochMillis = at)
            }
        }
        if (at - next.lastSeenEpochMillis >= HEARTBEAT_MILLIS) next = next.copy(lastSeenEpochMillis = at)
        return next
    }

    /** At app start: the time the app was closed gets no credit or blame. */
    fun afterRestart(clock: TaskClock, now: Long): TaskClock {
        val at = maxOf(now, clock.lastSeenEpochMillis)
        val gap = at - clock.lastSeenEpochMillis
        return if (clock.waitingSinceEpochMillis != null) {
            clock.copy(lastSeenEpochMillis = at)
        } else {
            clock.copy(waitedMillis = clock.waitedMillis + gap, lastSeenEpochMillis = at)
        }
    }

    fun finish(clock: TaskClock, now: Long): TaskClock = clock.copy(activeMillisAtEnd = active(clock, now))
}
