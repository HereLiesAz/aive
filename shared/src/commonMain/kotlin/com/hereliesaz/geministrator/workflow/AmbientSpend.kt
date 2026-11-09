package com.hereliesaz.geministrator.workflow

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What billed generations made outside any workflow run spent: the planner that drafts a hive, the reviewer
 * that reads the plan, the memory clerks. They belong to no run and no creature, so no role budget counts
 * them; the keeper's hourly spend cap does ([hourlySpend]). Kept in this process only: after a restart the
 * hour starts over for this spend, though not for a run's own (those are events in the store).
 */
class AmbientSpendLedger(private val now: () -> Long = ::currentMillis) {
    private class Entry(val atEpochMillis: Long, val costUsd: Double?, val costUnknown: Boolean)

    private val mutex = Mutex()
    private val entries = ArrayDeque<Entry>()

    /**
     * Records one billed generation: [costUsd] when it was priced, else its cost is unknown (never free).
     * A cost that is not a usable number is unknown too.
     */
    suspend fun record(costUsd: Double?, costUnknown: Boolean = false) {
        val usable = costUsd?.takeIf { it.isFinite() && it >= 0.0 }
        val at = now()
        mutex.withLock {
            entries.addLast(Entry(at, usable, costUnknown || (costUsd != null && usable == null)))
            while (entries.isNotEmpty() && entries.first().atEpochMillis < at - ONE_HOUR_MILLIS) entries.removeFirst()
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** Known cost recorded in the hour before [nowEpochMillis], and whether any of that hour's spend was unpriced. */
    suspend fun hourlySpend(nowEpochMillis: Long): HourlySpend = mutex.withLock {
        val since = nowEpochMillis - ONE_HOUR_MILLIS
        val recent = entries.filter { it.atEpochMillis in since..nowEpochMillis }
        HourlySpend(recent.sumOf { it.costUsd ?: 0.0 }, recent.any { it.costUnknown })
    }

    companion object {
        /** The ledger the app's planner, reviewer and clerks write to, and the runtime's spend caps read. */
        val Shared: AmbientSpendLedger = AmbientSpendLedger()
        private const val MAX_ENTRIES = 2_000
    }
}

/** Known cost over an hour, and whether some usage in it was billed but could not be priced. */
data class HourlySpend(val knownUsd: Double, val unpricedUsage: Boolean) {
    operator fun plus(other: HourlySpend) = HourlySpend(knownUsd + other.knownUsd, unpricedUsage || other.unpricedUsage)
}

@OptIn(ExperimentalTime::class)
private fun currentMillis(): Long = Clock.System.now().toEpochMilliseconds()
