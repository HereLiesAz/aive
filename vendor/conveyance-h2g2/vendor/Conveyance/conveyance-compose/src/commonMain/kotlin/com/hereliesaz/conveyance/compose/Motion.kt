package com.hereliesaz.conveyance.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.hereliesaz.conveyance.Weight

/**
 * Weight, rendered as physics.
 *
 * This is the only place in the framework where a spring is named, and product code cannot reach it.
 * That is the entire mechanism by which an application built on Conveyance is internally consistent
 * without anyone reviewing it for consistency: there is no `animationSpec` parameter to pass, so
 * there is no decision to make differently on a Friday afternoon.
 *
 * The three weights are distinguishable by hand, not just by number. Light overshoots slightly and
 * feels eager. Medium settles clean. Heavy does not overshoot at all and is slow to start and slow
 * to stop, which is what gives a person the beat in which to change their mind — the confirmation
 * dialog, replaced by inertia.
 */
object Motion {

    fun <T> spec(weight: Weight): AnimationSpec<T> = when (weight) {
        Weight.Light -> spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow,
        )
        Weight.Medium -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )
        Weight.Heavy -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow,
        )
    }

    /** Whether this weight is allowed to overshoot. Heavy never is; consequence should not bounce. */
    fun overshoots(weight: Weight): Boolean = weight == Weight.Light
}
