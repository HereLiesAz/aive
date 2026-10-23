package com.hereliesaz.geministrator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * HG2Gui-style stack arrivals. A stack chooses one entrance and its children inherit it; arrivals
 * may vary, but leaving remains consistent. This keeps the UI lively without making navigation
 * unpredictable.
 */
internal enum class AzphaltEntrance(val weight: Int) {
    Slide(4),
    Unfold(3),
    Drop(3),
    Cascade(2),
    Deal(2),
    Telescope(2),
    Split(1),
    Rally(1),
    Extend(2),
    Unroll(2),
    Tumble(2),
    ;

    companion object {
        private var last: AzphaltEntrance? = null

        fun roll(random: Random = Random.Default): AzphaltEntrance {
            val pool = entries.filter { it != last }
            var ticket = random.nextInt(pool.sumOf { it.weight })
            for (candidate in pool) {
                if (ticket < candidate.weight) {
                    last = candidate
                    return candidate
                }
                ticket -= candidate.weight
            }
            return pool.last().also { last = it }
        }

        fun childBand(random: Random = Random.Default): AzphaltEntrance {
            val candidates = entries.filterNot { it == Unroll || it == Tumble }
            val pool = candidates.filter { it != last }
            var ticket = random.nextInt(pool.sumOf { it.weight })
            for (candidate in pool) {
                if (ticket < candidate.weight) {
                    last = candidate
                    return candidate
                }
                ticket -= candidate.weight
            }
            return pool.last().also { last = it }
        }
    }
}

private val HouseEase = CubicBezierEasing(0f, 0.9f, 0.1f, 1f)

/** A composable modifier because each element owns its own arrival progress. */
@Composable
internal fun Modifier.azphaltEntrance(
    entrance: AzphaltEntrance,
    index: Int,
    count: Int,
): Modifier {
    val progress = remember(entrance, index, count) { Animatable(0f) }
    LaunchedEffect(entrance, index, count) {
        progress.snapTo(0f)
        val interval = if (count > 6) 18L else 34L
        delay(interval * index)
        progress.animateTo(1f, tween(durationMillis = 320, easing = HouseEase))
    }
    val p = progress.value
    return graphicsLayer {
        alpha = p
        when (entrance) {
            AzphaltEntrance.Slide -> translationX = (1f - p) * 120f
            AzphaltEntrance.Unfold -> {
                scaleX = 0.08f + (0.92f * p)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            AzphaltEntrance.Drop -> translationY = (1f - p) * -90f
            AzphaltEntrance.Cascade -> {
                translationX = (1f - p) * (70f + index * 12f)
                rotationZ = (1f - p) * -5f
            }
            AzphaltEntrance.Deal -> {
                translationX = (1f - p) * 150f
                translationY = (1f - p) * (index - count / 2f) * 10f
                rotationZ = (1f - p) * (index - count / 2f) * 2f
            }
            AzphaltEntrance.Telescope -> {
                scaleX = 0.35f + 0.65f * p
                scaleY = 0.7f + 0.3f * p
            }
            AzphaltEntrance.Split -> translationX = (1f - p) * if (index % 2 == 0) -130f else 130f
            AzphaltEntrance.Rally -> {
                translationY = (1f - p) * if (index % 2 == 0) -70f else 70f
                rotationZ = (1f - p) * if (index % 2 == 0) -4f else 4f
            }
            AzphaltEntrance.Extend -> {
                scaleX = 0.2f + 0.8f * p
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
            }
            AzphaltEntrance.Unroll -> {
                translationX = (1f - p) * (index * 24f)
                rotationZ = (1f - p) * -10f
            }
            AzphaltEntrance.Tumble -> {
                translationX = (1f - p) * 90f
                translationY = (1f - p) * -50f
                rotationZ = (1f - p) * (18f - index * 2f)
            }
        }
    }
}

/** Selected/open subjects transform in place rather than being replaced by a second component. */
@Composable
internal fun Modifier.azphaltSelectedTransform(selected: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.018f else 1f,
        animationSpec = tween(220, easing = HouseEase),
        label = "azphalt-selected-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
internal fun animatedRecordRadius(selected: Boolean): Dp {
    val radius by animateDpAsState(
        targetValue = if (selected) 30.dp else 22.dp,
        animationSpec = tween(220, easing = HouseEase),
        label = "azphalt-record-radius",
    )
    return radius
}

/** Child bands grow out of the record that owns them and collapse back into that same place. */
@Composable
internal fun AzphaltChildBand(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(280, easing = HouseEase)) + fadeIn(tween(140)),
        exit = shrinkVertically(animationSpec = tween(240, easing = HouseEase)) + fadeOut(tween(110)),
    ) {
        content()
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun <T> AzphaltPlaceTransition(
    target: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(120)) togetherWith fadeOut(tween(90)))
        },
        label = "azphalt-place-transition",
    ) { state ->
        content(state)
    }
}
