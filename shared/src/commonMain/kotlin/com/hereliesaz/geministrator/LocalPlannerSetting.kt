package com.hereliesaz.geministrator

/**
 * Settings-screen hook for the optional on-device workflow planner. Only platforms that can run it
 * (desktop) pass a non-null value; others hide the section and plan with the linked LLM.
 */
data class LocalPlannerSetting(
    val status: LocalPlannerStatus,
    /** Human-readable download size, e.g. "3.6 GB". */
    val downloadSize: String,
    val onInstall: () -> Unit,
    val onRemove: () -> Unit,
)

sealed interface LocalPlannerStatus {
    data object NotInstalled : LocalPlannerStatus

    data class Installing(val progress: String) : LocalPlannerStatus

    data object Installed : LocalPlannerStatus

    data class Failed(val message: String) : LocalPlannerStatus
}
