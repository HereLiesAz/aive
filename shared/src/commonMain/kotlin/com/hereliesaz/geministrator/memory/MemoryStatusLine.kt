package com.hereliesaz.geministrator.memory

/** Whether the Memory swarm's **Consolidate now** can start a drain, and if not, why. */
enum class ConsolidateNowState {
    /** Memory is on and consolidation running: a tap starts a drain. */
    Ready,

    /** A packet is being consolidated right now. */
    Working,

    /** Consolidation is paused; resuming it is the way through. */
    Paused,

    /** Memory is off; turning it on is the way through. */
    Off,
}

/** Off wins over paused (nothing consolidates while memory is off, paused or not). */
fun consolidateNowState(settings: MemoryLayerSettings, activity: MemoryActivity): ConsolidateNowState = when {
    !settings.enabled -> ConsolidateNowState.Off
    settings.consolidationPaused -> ConsolidateNowState.Paused
    activity.working != null -> ConsolidateNowState.Working
    else -> ConsolidateNowState.Ready
}

/**
 * Whether a packet's result was consolidation work: a stage applied, an entry completed, pair
 * summaries written, or a condensation declined. Idle (nothing due) and Failed are not.
 */
val MemoryConsolidationResult.consolidatedSomething: Boolean
    get() = when (this) {
        MemoryConsolidationResult.Idle -> false
        is MemoryConsolidationResult.Failed -> false
        is MemoryConsolidationResult.Applied -> true
        is MemoryConsolidationResult.Completed -> true
        is MemoryConsolidationResult.PairSummaries -> true
        is MemoryConsolidationResult.Declined -> true
    }

/** Unit of a "time since" shown on the Memory swarm's status line. */
enum class MemoryElapsedUnit { JustNow, Minutes, Hours, Days }

data class MemoryElapsed(val unit: MemoryElapsedUnit, val amount: Long)

/**
 * How long ago [sinceEpochMillis] was at [nowEpochMillis], rounded down to whole minutes, hours or
 * days. Under a minute (or a clock that went backwards) is "just now".
 */
fun memoryElapsed(sinceEpochMillis: Long, nowEpochMillis: Long): MemoryElapsed {
    val minutes = (nowEpochMillis - sinceEpochMillis).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> MemoryElapsed(MemoryElapsedUnit.JustNow, 0L)
        minutes < 60L -> MemoryElapsed(MemoryElapsedUnit.Minutes, minutes)
        minutes < 24L * 60L -> MemoryElapsed(MemoryElapsedUnit.Hours, minutes / 60L)
        else -> MemoryElapsed(MemoryElapsedUnit.Days, minutes / (24L * 60L))
    }
}

/** Queue entries still to be consolidated (pending or being processed), as the Queue section counts them. */
fun MemorySnapshot.waitingToConsolidate(): Int =
    queue.count { it.status == MemoryQueueStatus.Pending || it.status == MemoryQueueStatus.Processing }
