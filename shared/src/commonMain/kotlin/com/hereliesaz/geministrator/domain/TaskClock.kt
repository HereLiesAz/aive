package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/**
 * One attempt's clock of active time. Only what the creature spent working counts: the time it waited on
 * the keeper, and the time the app was closed, are added to [waitedMillis] and left out of the allowance.
 * Pure data; [com.hereliesaz.geministrator.workflow.ActiveClock] moves it, always given `now`.
 */
@Serializable
data class TaskClock(
    /** A clock for another attempt is stale and restarts. */
    val attempt: Int,
    val startedAtEpochMillis: Long,
    /** The queen's own allowance for this attempt, resolved at its start; 0 = none. A keeper's [TimeLimit] overrides it. */
    val autoMillis: Long,
    val autoHardMillis: Long,
    /** Closed intervals that do not count. */
    val waitedMillis: Long = 0,
    /** An open interval that does not count yet (a wait that has not ended). */
    val waitingSinceEpochMillis: Long? = null,
    /** Heartbeat, rewritten at most once a minute. */
    val lastSeenEpochMillis: Long,
    /** The last sign of life from the creature's session, when the Source shows one. */
    val progressAtEpochMillis: Long,
    /** Set once, when the soft point passed. */
    val softAtEpochMillis: Long? = null,
    /** Active milliseconds when the attempt ended; feeds the history. */
    val activeMillisAtEnd: Long? = null,
)
