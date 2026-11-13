package com.hereliesaz.geministrator

/**
 * Settings-screen hook for automatic crash/ANR reporting. Only distribution flavors that
 * actually report (the GitHub release build) pass a non-null value; others hide the toggle.
 */
data class CrashReportingSetting(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    /** The report waiting to be sent, exactly as it will be sent, or null when none waits. Shown beside the toggle. */
    val payloadPreview: () -> String? = { null },
)
