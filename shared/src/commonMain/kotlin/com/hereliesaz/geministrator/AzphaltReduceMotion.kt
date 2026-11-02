package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings

/**
 * Process-wide reduced-motion switch. Each platform entry point sets [platformReduceMotion] from its
 * own signal (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0`; web: `prefers-reduced-motion`;
 * desktop: the JVM has no portable OS signal, so it follows the `AIVE_REDUCE_MOTION` environment
 * variable or `aive.reduceMotion` system property, and is otherwise off). The keeper can also turn
 * motion off in Keeper's room › Appearance ([keeperReduceMotion], stored on this device). The two
 * are OR-ed ([effectiveReduceMotion]): the keeper's switch can only turn motion off, never back on
 * against the platform. Looping decorative animations (the mascot terrarium clocks, the creature
 * gallery) freeze on a still frame while [reduceMotion] is on.
 */
object AzphaltMotion {
    private val settings by lazy { Settings() }

    /** The platform's own reduced-motion signal; written only by the platform entry points. */
    var platformReduceMotion: Boolean by mutableStateOf(false)

    /** The keeper's Reduce motion switch, remembered on this device. */
    var keeperReduceMotion: Boolean by mutableStateOf(settings.getBoolean(KEEPER_REDUCE_MOTION_KEY, false))
        private set

    /** Whether motion is reduced: the platform signal or the keeper's switch. */
    val reduceMotion: Boolean
        get() = effectiveReduceMotion(platformReduceMotion, keeperReduceMotion)

    fun updateKeeperReduceMotion(on: Boolean) {
        keeperReduceMotion = on
        settings.putBoolean(KEEPER_REDUCE_MOTION_KEY, on)
    }

    private const val KEEPER_REDUCE_MOTION_KEY = "aive.appearance.reduce-motion.v1"
}

/** The keeper's switch can add reduced motion but never remove the platform's. */
fun effectiveReduceMotion(platformReduceMotion: Boolean, keeperReduceMotion: Boolean): Boolean =
    platformReduceMotion || keeperReduceMotion

/**
 * Pure decision for [AzphaltMotion.reduceMotion]. [animatorDurationScale] is Android's global
 * animator scale (null when unknown); a scale of exactly 0 means the user turned animations off.
 * [systemPrefersReduced] is the platform's explicit reduced-motion preference (for example the
 * CSS `prefers-reduced-motion: reduce` media query).
 */
fun shouldReduceMotion(animatorDurationScale: Float?, systemPrefersReduced: Boolean): Boolean =
    systemPrefersReduced || (animatorDurationScale != null && animatorDurationScale <= 0f)
