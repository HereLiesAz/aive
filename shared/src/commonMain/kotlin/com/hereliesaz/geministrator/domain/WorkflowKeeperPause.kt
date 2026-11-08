package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/**
 * The keeper's own pause of a running hive (docs/Keepers-Room.md, Pause, change, resume). While it
 * is present nothing new starts: no creature is dispatched, no system step or remote compute begins,
 * and a retry waits. What is already running finishes, approvals and decisions still go through, and
 * the keeper can edit and look around. Clearing it lets the hive carry on from where it was.
 *
 * It is not a stop: no provider session is cancelled and no task is parked, so nothing is lost and
 * nothing has to be started again. It sits beside [WorkflowGlobalPause] (the Hall Monitor's) and
 * [WorkflowSpendPause] (a spend cap's), which do stop and park work; either of those takes effect
 * over it.
 */
@Serializable
data class WorkflowKeeperPause(
    val pausedAtEpochMillis: Long,
)
