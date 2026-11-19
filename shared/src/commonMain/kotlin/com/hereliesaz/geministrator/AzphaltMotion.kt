package com.hereliesaz.geministrator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
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
 * HG2Gui's stack arrivals, from h2g2 (one lottery for every HG2Gui app). A stack chooses one
 * entrance and its children inherit it; arrivals vary, leaving stays the same.
 */
internal typealias AzphaltEntrance = com.hereliesaz.conveyance.h2g2.StackEntrance

private val HouseEase = com.hereliesaz.conveyance.h2g2.H2g2Motion.Ease

/** A composable modifier because each element owns its own arrival progress. */
@Composable
internal fun Modifier.azphaltEntrance(
    entrance: AzphaltEntrance,
    index: Int,
    count: Int,
): Modifier {
    // Reduced motion: it arrives already in place, with no slide, scale, rotation or stagger.
    val reduce = AzphaltMotion.reduceMotion
    val progress = remember(entrance, index, count, reduce) { Animatable(entranceStartProgress(reduce)) }
    LaunchedEffect(entrance, index, count, reduce) {
        if (reduce) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        delay(com.hereliesaz.conveyance.h2g2.staggerDelayMs(index, count, com.hereliesaz.conveyance.h2g2.stackInterval(34, count)))
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

/** Where an entrance starts: already arrived when motion is reduced. */
internal fun entranceStartProgress(reduceMotion: Boolean): Float = if (reduceMotion) 1f else 0f

/** The scale a selected subject grows to; it does not grow when motion is reduced. */
internal fun selectedScale(selected: Boolean, reduceMotion: Boolean): Float =
    if (selected && !reduceMotion) 1.018f else 1f

/** Selected/open subjects transform in place rather than being replaced by a second component. */
@Composable
internal fun Modifier.azphaltSelectedTransform(selected: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = selectedScale(selected, AzphaltMotion.reduceMotion),
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

/**
 * A child band grows out of the record that owns it, from that record's own top-start corner on
 * h2g2's nest clock, and collapses back into the same place: what belongs to the record is drawn
 * inside it (h2g2 `Nest.kt`). Everything in the band is one nest level deeper, so a well inside it
 * takes the next surface down (`nestSurface`) and its own bands go deeper still.
 */
@Composable
internal fun AzphaltChildBand(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val ms = com.hereliesaz.conveyance.h2g2.NEST_MORPH_MS
    val depth = com.hereliesaz.conveyance.h2g2.LocalNestDepth.current
    AnimatedVisibility(
        visible = visible,
        // Reduced motion: no growing or shrinking, just a quick fade.
        enter = if (AzphaltMotion.reduceMotion) fadeIn(tween(80)) else expandIn(tween(ms, easing = HouseEase), expandFrom = Alignment.TopStart) + fadeIn(tween(ms / 2, delayMillis = ms / 3)),
        exit = if (AzphaltMotion.reduceMotion) fadeOut(tween(60)) else shrinkOut(tween(ms, easing = HouseEase), shrinkTowards = Alignment.TopStart) + fadeOut(tween(ms / 3)),
    ) {
        // A column, so a band holding several children stacks them rather than overlapping them.
        CompositionLocalProvider(com.hereliesaz.conveyance.h2g2.LocalNestDepth provides depth + 1) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { content() }
        }
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

/**
 * A card that arrives by growing out of a pill, h2g2's containment morph for a surface that opens
 * on its own layer (a dialog, a menu): it starts as a capsule a fraction of its width and grows on
 * the nest clock into a card of [openRadius], so it reads as the pill that was pressed opening up
 * rather than a new window appearing. Returns the shape to clip the card to and a modifier for it.
 */
@Composable
internal fun rememberGrowFromPill(openRadius: Dp = AzphaltShapes.dialogCorner): Pair<androidx.compose.ui.graphics.Shape, Modifier> {
    val reduce = AzphaltMotion.reduceMotion
    val grow = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) {
        if (reduce) grow.snapTo(1f) else grow.animateTo(1f, tween(com.hereliesaz.conveyance.h2g2.NEST_MORPH_MS, easing = HouseEase))
    }
    val p = grow.value
    val shape = com.hereliesaz.conveyance.h2g2.NestMorphShape(p, openRadius)
    val modifier = Modifier.graphicsLayer {
        scaleX = 0.35f + 0.65f * p
        scaleY = 0.2f + 0.8f * p
        alpha = (p * 2f).coerceAtMost(1f)
    }
    return shape to modifier
}

/**
 * Offers for a field (suggestions, recent entries) shown INSIDE the field's own band, growing out
 * of it on the nest clock, instead of a popup floating over the page. Not focusable: the field
 * keeps the keyboard while offers arrive.
 */
@Composable
internal fun AzphaltOfferBand(visible: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    AzphaltChildBand(visible = visible) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxWidth()
                .clip(com.hereliesaz.conveyance.h2g2.nestSurface(com.hereliesaz.conveyance.h2g2.LocalNestDepth.current))
                .background(AzphaltLiftedInk),
            content = content,
        )
    }
}

/**
 * A page that opens out of the one beneath it: going in, the new page grows from the top-start
 * corner over the old on the nest clock; going back, it folds into the same corner. For places
 * that cannot nest literally (a phone has room for one page), so the motion still says "inside".
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun AzphaltNestSwap(open: Boolean, modifier: Modifier = Modifier, content: @Composable (Boolean) -> Unit) {
    val ms = com.hereliesaz.conveyance.h2g2.NEST_MORPH_MS
    val reduce = AzphaltMotion.reduceMotion
    AnimatedContent(
        targetState = open,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(80)) togetherWith fadeOut(tween(60))
            } else if (targetState) {
                (expandIn(tween(ms, easing = HouseEase), expandFrom = Alignment.TopStart) + fadeIn(tween(ms / 2))) togetherWith fadeOut(tween(ms / 2))
            } else {
                fadeIn(tween(ms / 2)) togetherWith (shrinkOut(tween(ms, easing = HouseEase), shrinkTowards = Alignment.TopStart) + fadeOut(tween(ms)))
            }.apply { targetContentZIndex = if (targetState) 1f else 0f }
        },
        label = "azphalt-nest-swap",
    ) { state -> content(state) }
}
