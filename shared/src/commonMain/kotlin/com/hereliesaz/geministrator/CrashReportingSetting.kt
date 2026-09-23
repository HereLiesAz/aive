package com.hereliesaz.geministrator

/**
 * Settings-screen hook for automatic crash/ANR reporting. Only distribution flavors that
 * actually report (the GitHub release build) pass a non-null value; others hide the toggle.
 */
data class CrashReportingSetting(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)
