package com.hereliesaz.conveyance.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.hereliesaz.conveyance.Weight

/**
 * The Yield: work shown as a deformation of the thing that was engaged.
 *
 * Never a separate spinner. A spinner severs the link between what the person touched and what is
 * happening, and that link is precisely what they are trying to learn. The element stays
 * recognisably itself throughout — it compresses, it does not disappear behind an indicator, and it
 * is never swapped for something else.
 *
 * No colour is involved. Every channel already carries an assigned meaning, and progress is not one
 * of them; deformation is geometry, which is the channel Yield is entitled to.
 *
 * @param extent fraction of known work, or null when the end is not known.
 */
@Composable
fun Modifier.yielding(extent: Float?, weight: Weight): Modifier {
    val compressed = 0.94f
    val target = if (extent != null) {
        compressed + (1f - compressed) * extent.coerceIn(0f, 1f)
    } else {
        // Work with no known end deforms rhythmically rather than proportionally: an element that
        // sat at a fixed compression would read as broken rather than busy.
        val pulse = rememberInfiniteTransition(label = "yield")
        val value by pulse.animateFloat(
            initialValue = 1f,
            targetValue = compressed,
            animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
            label = "yield.pulse",
        )
        value
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = Motion.spec(weight),
        label = "yield.scale",
    )
    return graphicsLayer {
        scaleY = scale
    }
}

/**
 * The Tell: an unpractised element performs a half-rep of its own interaction, once.
 *
 * Not an arrow pointing at it, not a caption, not a coach mark that has to be dismissed. The element
 * does an abbreviated version of the thing it wants the person to do, the way a poker player's hands
 * give them away a beat before they act, and then never mentions it again.
 *
 * It is over in a third of a second and it does not repeat, because an element that keeps hinting
 * after a person has done the thing is nagging — a construction sign that learned to blink.
 */
@Composable
fun Modifier.tell(owed: Boolean, weight: Weight): Modifier {
    val nudge by animateFloatAsState(
        targetValue = if (owed) 1f else 0f,
        animationSpec = Motion.spec(weight),
        label = "tell",
    )
    return graphicsLayer {
        // A half-rep, not a performance: a fraction of the displacement the real gesture would make.
        translationY = nudge * 6f
        scaleX = 1f + nudge * 0.02f
        scaleY = 1f + nudge * 0.02f
    }
}
