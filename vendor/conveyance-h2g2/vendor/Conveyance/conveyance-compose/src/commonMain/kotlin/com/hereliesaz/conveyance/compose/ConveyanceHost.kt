package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.hereliesaz.conveyance.Practice

/**
 * The root every Conveyance surface sits inside.
 *
 * It holds the three things that must be shared across an entire product rather than per screen: the
 * element registry, because a verb may need to travel somewhere the current screen has never heard
 * of; practice counts, because familiarity belongs to the person and not to a composition that is
 * thrown away on rotation; and the stage, because anything clipped to one element cannot make the
 * journey between two.
 *
 * The stage is drawn above the content and is not optional. A framework that leaves its own motion
 * to the application is not a framework, it is a style guide with types.
 */
@Composable
fun ConveyanceHost(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    /**
     * Supply a registry to observe the surface from outside.
     *
     * Applications leave this null. An audit harness passes one in, because grading a screen needs
     * the truth about it and the truth lives here.
     */
    registry: ElementRegistry? = null,
    content: @Composable () -> Unit,
) {
    val elements = registry ?: remember { ElementRegistry() }
    val practice = remember { Practice() }
    val ghosts = remember { Ghosts() }
    val stage = remember { Stage() }
    val suppression = remember { Suppression() }

    CompositionLocalProvider(
        LocalElements provides elements,
        LocalPractice provides practice,
        LocalGhosts provides ghosts,
        LocalStage provides stage,
        LocalReducedMotion provides reducedMotion,
        LocalSuppression provides suppression,
    ) {
        Box(modifier.fillMaxSize()) {
            content()
            stage.flights.forEach { flight ->
                FlightLayer(stage, flight)
            }
        }
    }
}

val LocalPractice = staticCompositionLocalOf { Practice() }

val LocalStage = staticCompositionLocalOf { Stage() }

/**
 * Whether the person has asked for reduced motion.
 *
 * Read to pick a verb's reduced register, which drops traversal and keeps identity. It is never read
 * to decide *whether* to convey — only how.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
