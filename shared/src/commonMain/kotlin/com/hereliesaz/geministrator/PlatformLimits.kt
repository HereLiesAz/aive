package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import com.hereliesaz.geministrator.distributed.ComputeWorkHold
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.compute_hold_metered_network
import com.hereliesaz.geministrator.resources.compute_hold_needs_external_power
import com.hereliesaz.geministrator.resources.compute_hold_power_unknown
import com.hereliesaz.geministrator.resources.compute_hold_sharing_off
import com.hereliesaz.geministrator.resources.compute_hold_too_hot
import com.hereliesaz.geministrator.resources.platform_limit_compute_browser
import com.hereliesaz.geministrator.resources.platform_limit_compute_store
import com.hereliesaz.geministrator.resources.platform_limit_remote_browser
import com.hereliesaz.geministrator.resources.platform_limit_remote_store
import org.jetbrains.compose.resources.stringResource

/** Why a build does not have a device feature: a limit of the platform, not a switch that is off. */
enum class PlatformLimit {
    /** A browser tab cannot listen for other devices or stay connected once it sleeps. */
    Browser,

    /** The Google Play build of the Android app does not include it. */
    PlayStore,
}

/**
 * The device features (compute pools, Remote access) this build does not have and why. A null entry means the
 * build has the feature, or the platform simply has not built it yet and nothing is claimed about a limit.
 */
data class PlatformLimits(
    val compute: PlatformLimit? = null,
    val remote: PlatformLimit? = null,
)

@Composable
internal fun platformLimitText(limit: PlatformLimit, remote: Boolean): String = stringResource(
    when (limit) {
        PlatformLimit.Browser -> if (remote) Res.string.platform_limit_remote_browser else Res.string.platform_limit_compute_browser
        PlatformLimit.PlayStore -> if (remote) Res.string.platform_limit_remote_store else Res.string.platform_limit_compute_store
    },
)

/** The plain-language reason a pool device is not taking work (see [computeWorkDecision]). */
@Composable
internal fun computeWorkHoldText(hold: ComputeWorkHold): String = stringResource(
    when (hold) {
        ComputeWorkHold.SharingOff -> Res.string.compute_hold_sharing_off
        ComputeWorkHold.TooHot -> Res.string.compute_hold_too_hot
        ComputeWorkHold.MeteredNetwork -> Res.string.compute_hold_metered_network
        ComputeWorkHold.NeedsExternalPower -> Res.string.compute_hold_needs_external_power
        ComputeWorkHold.PowerUnknown -> Res.string.compute_hold_power_unknown
    },
)
