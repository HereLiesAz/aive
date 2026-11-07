package com.hereliesaz.geministrator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * An unborn creature (docs/Node-Creatures.md, Birth): its body and head and nothing else, no eyes,
 * no tails, no tendrils, shaped as an egg. It swells as the creatures that gestate it work, takes on
 * their colours as each begins, and carries one pip per gestator: solid once that one is done, dim
 * while it works, an outline before it begins. A [CreatureBirth.Withered] egg is grey and cracked.
 *
 * [contributions] pairs each working gestator's own colour with how much it has contributed.
 * Reduced motion snaps growth and colour; they still change only when the run records a change.
 */
@Composable
internal fun CreatureEgg(
    birth: CreatureBirth,
    seed: String,
    contributions: List<Pair<Color, Float>>,
    modifier: Modifier = Modifier,
) {
    val egg = birth as? CreatureBirth.Egg
    val withered = birth is CreatureBirth.Withered
    val reduceMotion = AzphaltMotion.reduceMotion
    val base = remember(seed) { eggBaseTint(seed) }
    val target = if (withered) WITHERED_SHELL else remember(base, contributions) { blendEggTint(base, contributions) }
    val tint by animateColorAsState(target, if (reduceMotion) snap() else tween(EGG_EASE_MS), label = "egg-tint")
    val grown by animateFloatAsState(
        targetValue = if (withered) WITHERED_GROWTH else egg?.growth ?: 0f,
        animationSpec = if (reduceMotion) snap() else tween(EGG_EASE_MS),
        label = "egg-growth",
    )
    val pips = egg?.gestators?.map { it.contribution }.orEmpty().take(MAX_PIPS)
    Canvas(modifier) {
        val box = min(size.width, size.height)
        val width = box * (EGG_MIN_WIDTH + (EGG_MAX_WIDTH - EGG_MIN_WIDTH) * grown.coerceIn(0f, 1f))
        val height = width * EGG_ASPECT
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shell = eggPath(cx, cy, width, height)
        // It rests on the ground: a soft shadow under it.
        drawOval(
            Color.Black.copy(alpha = 0.28f),
            topLeft = Offset(cx - width * 0.5f, cy + height * 0.44f),
            size = Size(width, height * 0.14f),
        )
        drawPath(
            shell,
            Brush.radialGradient(
                listOf(lerp(tint, Color.White, 0.38f), tint, lerp(tint, Color.Black, 0.4f)),
                center = Offset(cx - width * 0.16f, cy - height * 0.2f),
                radius = height * 0.78f,
            ),
        )
        drawPath(shell, lerp(tint, Color.Black, 0.55f), style = Stroke(width = max(1.5.dp.toPx(), box * 0.018f)))
        drawOval(
            Color.White.copy(alpha = if (withered) 0.18f else 0.45f),
            topLeft = Offset(cx - width * 0.27f, cy - height * 0.33f),
            size = Size(width * 0.2f, height * 0.15f),
        )
        if (withered) {
            val crack = Path().apply {
                moveTo(cx - width * 0.12f, cy - height * 0.46f)
                lineTo(cx + width * 0.04f, cy - height * 0.2f)
                lineTo(cx - width * 0.1f, cy - height * 0.02f)
                lineTo(cx + width * 0.08f, cy + height * 0.18f)
            }
            drawPath(crack, Color.Black.copy(alpha = 0.7f), style = Stroke(width = max(1.5.dp.toPx(), box * 0.02f)))
        }
        val radius = max(2.dp.toPx(), box * 0.022f)
        val gap = radius * 3.2f
        val startX = cx - gap * (pips.size - 1) / 2f
        pips.forEachIndexed { index, contribution ->
            val centre = Offset(startX + gap * index, cy + height * 0.5f + radius * 3.2f)
            when {
                contribution >= 1f -> drawCircle(Color.White.copy(alpha = 0.95f), radius, centre)
                contribution > 0f -> drawCircle(Color.White.copy(alpha = 0.5f), radius, centre)
                else -> drawCircle(Color.White.copy(alpha = 0.55f), radius, centre, style = Stroke(width = radius * 0.5f))
            }
        }
    }
}

/**
 * An egg's outline: wider below than above, the narrow end up. (x, y) are in the canvas, [width] and
 * [height] the egg's extent about its centre ([cx], [cy]).
 */
internal fun eggPath(cx: Float, cy: Float, width: Float, height: Float): Path = Path().apply {
    for (step in 0..EGG_OUTLINE_STEPS) {
        val angle = (2.0 * PI * step / EGG_OUTLINE_STEPS).toFloat()
        val s = sin(angle)
        val x = cx + width / 2f * cos(angle) * (1f - EGG_TAPER * s)
        val y = cy - height / 2f * s
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private const val EGG_OUTLINE_STEPS = 72
private const val EGG_TAPER = 0.16f
private const val EGG_ASPECT = 1.28f
private const val EGG_MIN_WIDTH = 0.24f
private const val EGG_MAX_WIDTH = 0.54f
private const val WITHERED_GROWTH = 0.3f
private const val MAX_PIPS = 6
private const val EGG_EASE_MS = 600
private val WITHERED_SHELL = Color(0xFF6F6B66)

/**
 * Shows [egg] while [unborn], then [born]. The moment [unborn] ends the creature arrives from the egg:
 * it grows into place while a ring spreads and fades from where the shell was. Nothing animates for a
 * creature that was never an egg, and reduced motion snaps it in.
 */
@Composable
internal fun BirthTransition(
    unborn: Boolean,
    egg: @Composable () -> Unit,
    born: @Composable () -> Unit,
) {
    val reduceMotion = AzphaltMotion.reduceMotion
    // 0 while unborn, 1 once born; a creature composed already born starts at 1 and never animates.
    val arrival by animateFloatAsState(
        targetValue = if (unborn) 0f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(HATCH_MS),
        label = "hatch",
    )
    if (unborn) {
        egg()
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().graphicsLayer {
                val grown = HATCH_FROM + (1f - HATCH_FROM) * arrival
                scaleX = grown
                scaleY = grown
                alpha = arrival
            },
        ) { born() }
        if (arrival < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val box = min(size.width, size.height)
                drawCircle(
                    Color.White.copy(alpha = (1f - arrival) * 0.8f),
                    radius = box * (0.18f + 0.3f * arrival),
                    style = Stroke(width = max(1.5.dp.toPx(), box * 0.02f)),
                )
            }
        }
    }
}

private const val HATCH_MS = 700
private const val HATCH_FROM = 0.5f
