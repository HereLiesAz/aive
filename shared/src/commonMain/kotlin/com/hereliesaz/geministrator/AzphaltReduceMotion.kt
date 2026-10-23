package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide reduced-motion switch. Each platform entry point sets it from its own signal
 * (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0`; web: `prefers-reduced-motion`;
 * desktop: no OS signal is read, so it stays off). Looping decorative animations (the mascot
 * terrarium clocks, the creature gallery) freeze on a still frame while it is on.
 */
object AzphaltMotion {
    var reduceMotion: Boolean by mutableStateOf(false)
}

/**
 * Pure decision for [AzphaltMotion.reduceMotion]. [animatorDurationScale] is Android's global
 * animator scale (null when unknown); a scale of exactly 0 means the user turned animations off.
 * [systemPrefersReduced] is the platform's explicit reduced-motion preference (for example the
 * CSS `prefers-reduced-motion: reduce` media query).
 */
fun shouldReduceMotion(animatorDurationScale: Float?, systemPrefersReduced: Boolean): Boolean =
    systemPrefersReduced || (animatorDurationScale != null && animatorDurationScale <= 0f)
