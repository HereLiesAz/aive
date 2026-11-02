package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/** Which of the keeper's spend caps stopped a run. */
@Serializable
enum class SpendCapKind {
    /** The run's own known cost reached the per-run cost cap. */
    RunCost,

    /** Known cost across every run on this device in the last hour reached the hourly cap. */
    HourlySpend,
}

/**
 * Durable run-level stop opened by a spend cap (docs/Keepers-Room.md, This device).
 *
 * While this is present, every non-terminal task is parked in Blocked and no provider session is
 * live: sessions were cancelled before the stop was committed and are re-dispatched as a retry when
 * the keeper lifts it. Only known cost counts toward a cap; a provider that reports no cost never
 * trips one.
 */
@Serializable
data class WorkflowSpendPause(
    val kind: SpendCapKind,
    val reason: String,
    val capUsd: Double,
    val spentUsd: Double,
    val taskSnapshots: List<PausedTaskSnapshot>,
    val pausedAtEpochMillis: Long,
) {
    init {
        require(reason.isNotBlank()) { "Spend stop reason must not be blank" }
        require(capUsd > 0.0) { "Spend cap must be positive" }
        require(taskSnapshots.map { it.taskDefinitionId }.distinct().size == taskSnapshots.size) {
            "Spend stop task snapshots must be unique by task definition"
        }
    }
}
