package com.hereliesaz.conveyance.h2g2

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * How a workflow dependency is taught visually inside the terrarium.
 *
 * Synapse keeps a graph-like relationship visible between two creatures. Tool and Clothing turn
 * the dependency into something carried or worn by the downstream creature so dense workflows do
 * not collapse into edge spaghetti.
 */
enum class H2g2DependencyManifestation {
    Synapse,
    Tool,
    Clothing,
}

enum class H2g2SwarmAdornmentKind {
    Probe,
    Wrench,
    Clipboard,
    Lantern,
    Satchel,
    Vest,
    Goggles,
    Helmet,
    Cape,
    Belt,
}

data class H2g2SwarmAdornment(
    val dependencyId: String,
    val manifestation: H2g2DependencyManifestation,
    val kind: H2g2SwarmAdornmentKind,
)

fun h2g2SwarmAdornment(
    dependencyId: String,
    manifestation: H2g2DependencyManifestation,
): H2g2SwarmAdornment {
    require(manifestation != H2g2DependencyManifestation.Synapse) {
        "Synapse dependencies do not create worn/carried adornments"
    }
    val candidates = when (manifestation) {
        H2g2DependencyManifestation.Tool -> listOf(
            H2g2SwarmAdornmentKind.Probe,
            H2g2SwarmAdornmentKind.Wrench,
            H2g2SwarmAdornmentKind.Clipboard,
            H2g2SwarmAdornmentKind.Lantern,
            H2g2SwarmAdornmentKind.Satchel,
        )
        H2g2DependencyManifestation.Clothing -> listOf(
            H2g2SwarmAdornmentKind.Vest,
            H2g2SwarmAdornmentKind.Goggles,
            H2g2SwarmAdornmentKind.Helmet,
            H2g2SwarmAdornmentKind.Cape,
            H2g2SwarmAdornmentKind.Belt,
        )
        H2g2DependencyManifestation.Synapse -> error("Handled above")
    }
    val index = positiveAdornmentHash(dependencyId) % candidates.size
    return H2g2SwarmAdornment(
        dependencyId = dependencyId,
        manifestation = manifestation,
        kind = candidates[index],
    )
}

/**
 * Lightweight cel-shaded overlay for dependency affordances. It intentionally shares the creature
 * canvas rather than introducing meshes, images, or physics objects. Identity remains the body plan;
 * adornments explain what the creature currently depends on or carries from upstream work.
 */
@Composable
fun H2g2SwarmAdornmentLayer(
    adornments: List<H2g2SwarmAdornment>,
    hueSeed: String,
    modifier: Modifier = Modifier,
) {
    if (adornments.isEmpty()) return
    val stable = remember(adornments) { adornments.distinctBy { it.dependencyId }.take(4) }
    val hueIndex = remember(hueSeed) { H2g2.indexOf(hueSeed) }
    val accent = H2g2.hues[(hueIndex + 2) % H2g2.hues.size]
    Canvas(modifier) {
        stable.forEachIndexed { index, adornment ->
            drawAdornment(
                adornment = adornment,
                accent = accent,
                index = index,
                count = stable.size,
            )
        }
    }
}

private fun DrawScope.drawAdornment(
    adornment: H2g2SwarmAdornment,
    accent: Color,
    index: Int,
    count: Int,
) {
    val s = min(size.width, size.height)
    val outline = H2g2.ink
    val light = shadeAdornment(accent, 1.16f)
    val mid = shadeAdornment(accent, .9f)
    val shadow = shadeAdornment(accent, .58f)
    val spread = if (count <= 1) 0f else (index - (count - 1) / 2f) * s * .09f

    when (adornment.kind) {
        H2g2SwarmAdornmentKind.Probe -> {
            val base = Offset(size.width * .72f + spread, size.height * .63f)
            val tip = Offset(base.x + s * .13f, base.y - s * .22f)
            drawLine(outline, base, tip, s * .045f, StrokeCap.Round)
            drawLine(mid, base, tip, s * .025f, StrokeCap.Round)
            drawCircle(light, s * .043f, tip)
            drawCircle(outline, s * .043f, tip, style = Stroke(s * .012f))
        }
        H2g2SwarmAdornmentKind.Wrench -> {
            val base = Offset(size.width * .72f + spread, size.height * .68f)
            val end = Offset(base.x + s * .13f, base.y - s * .16f)
            drawLine(outline, base, end, s * .055f, StrokeCap.Round)
            drawLine(mid, base, end, s * .032f, StrokeCap.Round)
            drawCircle(shadow, s * .038f, base)
            val jaw = Path().apply {
                moveTo(end.x - s * .045f, end.y - s * .012f)
                lineTo(end.x - s * .005f, end.y + s * .035f)
                lineTo(end.x + s * .048f, end.y - s * .006f)
            }
            drawPath(jaw, outline, style = Stroke(s * .025f, cap = StrokeCap.Round))
            drawPath(jaw, light, style = Stroke(s * .012f, cap = StrokeCap.Round))
        }
        H2g2SwarmAdornmentKind.Clipboard -> {
            val topLeft = Offset(size.width * .66f + spread, size.height * .53f)
            val w = s * .18f
            val h = s * .22f
            drawRect(outline, topLeft, androidx.compose.ui.geometry.Size(w, h))
            drawRect(mid, topLeft + Offset(s * .012f, s * .012f), androidx.compose.ui.geometry.Size(w - s * .024f, h - s * .024f))
            drawRoundRect(light, topLeft + Offset(w * .33f, -s * .025f), androidx.compose.ui.geometry.Size(w * .34f, s * .055f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .018f))
            repeat(3) { row ->
                val y = topLeft.y + s * (.065f + row * .045f)
                drawLine(shadow, Offset(topLeft.x + s * .035f, y), Offset(topLeft.x + w - s * .035f, y), s * .012f, StrokeCap.Round)
            }
        }
        H2g2SwarmAdornmentKind.Lantern -> {
            val center = Offset(size.width * .75f + spread, size.height * .65f)
            drawCircle(outline, s * .085f, center)
            drawCircle(mid, s * .071f, center)
            drawCircle(light.copy(alpha = .9f), s * .04f, center)
            drawArc(outline, 195f, 150f, false, center - Offset(s * .07f, s * .12f), androidx.compose.ui.geometry.Size(s * .14f, s * .14f), style = Stroke(s * .016f))
        }
        H2g2SwarmAdornmentKind.Satchel -> {
            val topLeft = Offset(size.width * .68f + spread, size.height * .61f)
            val w = s * .18f
            val h = s * .14f
            drawRoundRect(outline, topLeft, androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(s * .025f))
            drawRoundRect(mid, topLeft + Offset(s * .012f, s * .012f), androidx.compose.ui.geometry.Size(w - s * .024f, h - s * .024f), androidx.compose.ui.geometry.CornerRadius(s * .018f))
            drawLine(outline, Offset(topLeft.x + w * .15f, topLeft.y), Offset(topLeft.x - s * .08f, topLeft.y - s * .2f), s * .018f, StrokeCap.Round)
        }
        H2g2SwarmAdornmentKind.Vest -> {
            val center = Offset(size.width * .5f + spread, size.height * .59f)
            val p = Path().apply {
                moveTo(center.x - s * .14f, center.y - s * .11f)
                lineTo(center.x - s * .19f, center.y + s * .11f)
                lineTo(center.x, center.y + s * .17f)
                lineTo(center.x + s * .19f, center.y + s * .11f)
                lineTo(center.x + s * .14f, center.y - s * .11f)
                lineTo(center.x + s * .05f, center.y - s * .03f)
                lineTo(center.x, center.y + s * .04f)
                lineTo(center.x - s * .05f, center.y - s * .03f)
                close()
            }
            drawPath(p, outline)
            drawPath(p, mid, style = Stroke(s * .018f))
        }
        H2g2SwarmAdornmentKind.Goggles -> {
            val y = size.height * .35f
            val left = Offset(size.width * .44f + spread, y)
            val right = Offset(size.width * .56f + spread, y)
            drawCircle(outline, s * .065f, left)
            drawCircle(outline, s * .065f, right)
            drawCircle(light.copy(alpha = .8f), s * .045f, left)
            drawCircle(light.copy(alpha = .8f), s * .045f, right)
            drawLine(outline, left + Offset(s * .055f, 0f), right - Offset(s * .055f, 0f), s * .018f, StrokeCap.Round)
        }
        H2g2SwarmAdornmentKind.Helmet -> {
            val center = Offset(size.width * .5f + spread, size.height * .31f)
            drawArc(outline, 180f, 180f, true, center - Offset(s * .16f, s * .09f), androidx.compose.ui.geometry.Size(s * .32f, s * .24f))
            drawArc(mid, 180f, 180f, true, center - Offset(s * .14f, s * .075f), androidx.compose.ui.geometry.Size(s * .28f, s * .2f))
            drawLine(light, Offset(center.x - s * .14f, center.y + s * .02f), Offset(center.x + s * .14f, center.y + s * .02f), s * .018f, StrokeCap.Round)
        }
        H2g2SwarmAdornmentKind.Cape -> {
            val center = Offset(size.width * .5f + spread, size.height * .56f)
            val wave = positiveAdornmentHash(adornment.dependencyId) % 7
            val bend = (wave - 3) * s * .008f
            val p = Path().apply {
                moveTo(center.x - s * .12f, center.y - s * .16f)
                cubicTo(center.x - s * .22f, center.y, center.x - s * .2f + bend, center.y + s * .22f, center.x - s * .07f, center.y + s * .28f)
                lineTo(center.x + s * .1f, center.y + s * .22f)
                cubicTo(center.x + s * .19f, center.y + s * .04f, center.x + s * .18f, center.y - s * .04f, center.x + s * .12f, center.y - s * .16f)
                close()
            }
            drawPath(p, shadow)
            drawPath(p, outline, style = Stroke(s * .016f))
        }
        H2g2SwarmAdornmentKind.Belt -> {
            val y = size.height * .63f
            drawLine(outline, Offset(size.width * .32f + spread, y), Offset(size.width * .68f + spread, y), s * .055f, StrokeCap.Round)
            drawLine(mid, Offset(size.width * .32f + spread, y), Offset(size.width * .68f + spread, y), s * .031f, StrokeCap.Round)
            drawRect(light, Offset(size.width * .47f + spread, y - s * .035f), androidx.compose.ui.geometry.Size(s * .06f, s * .07f))
        }
    }
}

private fun positiveAdornmentHash(value: String): Int {
    var hash = 0x811C9DC5.toInt()
    value.forEach { c ->
        hash = hash xor c.code
        hash *= 0x01000193
    }
    return hash and Int.MAX_VALUE
}

private fun shadeAdornment(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)
