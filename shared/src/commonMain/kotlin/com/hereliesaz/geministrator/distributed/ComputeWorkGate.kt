package com.hereliesaz.geministrator.distributed

/**
 * Why a device is not taking new pool work right now. Each hold is published as a node capability
 * ([capability]) so every device in the pool, and the Compute part, can say why a node is not
 * available instead of only "observe only".
 */
enum class ComputeWorkHold(val capability: String) {
    /** Sharing is off in this device's own setting. */
    SharingOff("hold:sharing-off"),

    /** The phone is too hot (Android's thermal status or battery temperature). */
    TooHot("thermal-hold"),

    /** On a metered network and the keeper has not allowed that. */
    MeteredNetwork("hold:metered-network"),

    /** The keeper requires external power and the device is on battery. */
    NeedsExternalPower("hold:needs-external-power"),

    /** The keeper requires external power and this device cannot tell whether it is plugged in. */
    PowerUnknown("hold:power-unknown"),
    ;

    companion object {
        /** The hold a node reports through its capabilities, or null when it reports none. */
        fun of(capabilities: Set<String>): ComputeWorkHold? = entries.firstOrNull { it.capability in capabilities }
    }
}

/** Whether a device takes new pool work now; [hold] is the first reason it does not. */
data class ComputeWorkDecision(val hold: ComputeWorkHold?) {
    val accepts: Boolean get() = hold == null
}

/**
 * The one rule every platform applies before taking new pool work. Pure: the platform reads its own
 * conditions and passes them in. [meteredNetwork] and [onExternalPower] are null where the platform
 * cannot tell: an unknown network counts as unmetered (the keeper cannot do anything about it), but an
 * unknown power state counts as not plugged in when external power is required, because nothing proves
 * the requirement is met. Checks run in this order: sharing, heat, network, power.
 */
fun computeWorkDecision(
    configuration: DistributedComputeConfiguration,
    meteredNetwork: Boolean?,
    onExternalPower: Boolean?,
    thermalHold: Boolean = false,
): ComputeWorkDecision = ComputeWorkDecision(
    when {
        !configuration.sharingEnabled -> ComputeWorkHold.SharingOff
        thermalHold -> ComputeWorkHold.TooHot
        meteredNetwork == true && !configuration.allowMeteredNetwork -> ComputeWorkHold.MeteredNetwork
        configuration.requireExternalPower && onExternalPower == null -> ComputeWorkHold.PowerUnknown
        configuration.requireExternalPower && onExternalPower == false -> ComputeWorkHold.NeedsExternalPower
        else -> null
    },
)
