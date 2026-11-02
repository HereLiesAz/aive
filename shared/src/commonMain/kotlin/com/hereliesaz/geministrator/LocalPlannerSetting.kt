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


/** Settings hook for released on-device orchestration specialists. */
data class LocalOrchestrationSpecialistSetting(
    val status: LocalOrchestrationSpecialistStatus,
    val releasedRoles: Int,
    val totalRoles: Int,
    val onInstall: () -> Unit,
    val onRemove: () -> Unit,
)

sealed interface LocalOrchestrationSpecialistStatus {
    data object NotInstalled : LocalOrchestrationSpecialistStatus
    data class Installing(val progress: String) : LocalOrchestrationSpecialistStatus
    data object Installed : LocalOrchestrationSpecialistStatus
    data class Failed(val message: String) : LocalOrchestrationSpecialistStatus
}

/**
 * Keeper's room hook (This device) for routing small text tasks to the device's own model
 * ([com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider]). Off until the keeper turns
 * it on; it takes effect only while [modelInstalled].
 */
data class OnDeviceSmallTaskSetting(
    val enabled: Boolean,
    val modelInstalled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

/** Where the keeper's choice for [OnDeviceSmallTaskSetting] is kept on this device. */
class OnDeviceSmallTaskPreference(
    private val settings: com.russhwolf.settings.Settings = com.russhwolf.settings.Settings(),
) {
    var enabled: Boolean
        get() = settings.getBoolean(KEY, false)
        set(value) = settings.putBoolean(KEY, value)

    private companion object {
        const val KEY = "on-device.small-tasks.enabled"
    }
}
