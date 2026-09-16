package com.hereliesaz.conveyance.h2g2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A transient non-agent visitor to the terrarium.
 *
 * Service identity is deliberately not encoded into appearance or motion. Every external service
 * uses the same toy delivery truck and the same enter/stop/leave choreography. The truck is a visual
 * verb — "something external arrived" — not another entity the user must learn to recognize.
 */
data class H2g2TerrariumServiceVisit(
    val id: String,
    val serviceName: String,
    val operation: String? = null,
    val target: H2g2TerrariumPosition,
    val active: Boolean,
)

/**
 * Renders all active service visits as the same fictional USPR ("US Pull Request") mail truck.
 * Each visit drives in from the left, stops beside its target, then exits right. No simulation loop,
 * service-specific skin, RNG, or persistent visual identity is involved.
 */
@Composable
fun H2g2TerrariumServiceLayer(
    visits: List<H2g2TerrariumServiceVisit>,
    modifier: Modifier = Modifier,
) {
    val active = visits.filter(H2g2TerrariumServiceVisit::active)
    if (active.isEmpty()) return

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        active.forEach { visit ->
            UsprVisitActor(
                visit = visit,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        }
    }
}

@Composable
private fun UsprVisitActor(
    visit: H2g2TerrariumServiceVisit,
    widthPx: Float,
    heightPx: Float,
) {
    val progress = remember(visit.id) { Animatable(-.18f) }
    var visible by remember(visit.id) { mutableStateOf(true) }

    LaunchedEffect(visit.id, visit.active) {
        if (!visit.active) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        progress.snapTo(-.18f)
        progress.animateTo(visit.target.x.coerceIn(.18f, .82f), tween(900))
        delay(720)
        progress.animateTo(1.18f, tween(980))
        visible = false
    }

    if (!visible) return

    val centerX = progress.value * widthPx
    val centerY = (visit.target.y * heightPx).coerceIn(68f, heightPx - 68f)
    Canvas(
        Modifier
            .offset {
                IntOffset(
                    x = (centerX - 74f).roundToInt(),
                    y = (centerY - 41f).roundToInt(),
                )
            }
            .size(148.dp, 82.dp),
    ) {
        drawUsprTruck()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUsprTruck() {
    val s = min(size.width, size.height)
    val outline = H2g2.ink
    val body = H2g2.white.copy(alpha = .96f)
    val stripe = H2g2.hues[4]

    drawRoundRect(
        color = outline,
        topLeft = Offset(size.width * .07f, size.height * .24f),
        size = Size(size.width * .78f, size.height * .48f),
        cornerRadius = CornerRadius(s * .055f),
    )
    drawRoundRect(
        color = body,
        topLeft = Offset(size.width * .09f, size.height * .26f),
        size = Size(size.width * .74f, size.height * .44f),
        cornerRadius = CornerRadius(s * .045f),
    )

    val cab = Path().apply {
        moveTo(size.width * .62f, size.height * .26f)
        lineTo(size.width * .79f, size.height * .26f)
        lineTo(size.width * .91f, size.height * .44f)
        lineTo(size.width * .91f, size.height * .69f)
        lineTo(size.width * .62f, size.height * .69f)
        close()
    }
    drawPath(cab, outline)

    val innerCab = Path().apply {
        moveTo(size.width * .645f, size.height * .285f)
        lineTo(size.width * .775f, size.height * .285f)
        lineTo(size.width * .88f, size.height * .45f)
        lineTo(size.width * .88f, size.height * .665f)
        lineTo(size.width * .645f, size.height * .665f)
        close()
    }
    drawPath(innerCab, body)

    drawRect(
        color = stripe,
        topLeft = Offset(size.width * .17f, size.height * .37f),
        size = Size(size.width * .38f, size.height * .16f),
    )
    drawLine(
        color = stripe,
        start = Offset(size.width * .16f, size.height * .57f),
        end = Offset(size.width * .57f, size.height * .57f),
        strokeWidth = s * .035f,
        cap = StrokeCap.Round,
    )

    val windshield = Path().apply {
        moveTo(size.width * .67f, size.height * .31f)
        lineTo(size.width * .77f, size.height * .31f)
        lineTo(size.width * .85f, size.height * .44f)
        lineTo(size.width * .67f, size.height * .44f)
        close()
    }
    drawPath(windshield, H2g2.ink)
    drawPath(windshield, stripe.copy(alpha = .35f))

    drawWheel(Offset(size.width * .26f, size.height * .72f), s)
    drawWheel(Offset(size.width * .74f, size.height * .72f), s)
    drawUsprMark(Offset(size.width * .365f, size.height * .45f), s)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWheel(center: Offset, s: Float) {
    drawCircle(H2g2.ink, s * .095f, center)
    drawCircle(H2g2.white.copy(alpha = .72f), s * .042f, center)
}

/** Blocky fixed USPR mark so every visit really is the same truck. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUsprMark(center: Offset, s: Float) {
    val h = s * .075f
    val stroke = s * .016f
    val spacing = s * .092f
    val start = center.x - spacing * 1.5f
    listOf('U', 'S', 'P', 'R').forEachIndexed { index, glyph ->
        drawGlyph(
            glyph = glyph,
            origin = Offset(start + index * spacing, center.y),
            height = h,
            stroke = stroke,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyph(
    glyph: Char,
    origin: Offset,
    height: Float,
    stroke: Float,
) {
    val width = height * .55f
    val topLeft = Offset(origin.x, origin.y - height * .5f)
    val topRight = Offset(origin.x + width, origin.y - height * .5f)
    val midLeft = Offset(origin.x, origin.y)
    val midRight = Offset(origin.x + width, origin.y)
    val bottomLeft = Offset(origin.x, origin.y + height * .5f)
    val bottomRight = Offset(origin.x + width, origin.y + height * .5f)

    fun line(a: Offset, b: Offset) = drawLine(H2g2.ink, a, b, stroke, StrokeCap.Round)
    when (glyph) {
        'U' -> {
            line(topLeft, bottomLeft)
            line(bottomLeft, bottomRight)
            line(bottomRight, topRight)
        }
        'S' -> {
            line(topRight, topLeft)
            line(topLeft, midLeft)
            line(midLeft, midRight)
            line(midRight, bottomRight)
            line(bottomRight, bottomLeft)
        }
        'P' -> {
            line(bottomLeft, topLeft)
            line(topLeft, topRight)
            line(topRight, midRight)
            line(midRight, midLeft)
            line(midLeft, bottomLeft)
        }
        'R' -> {
            line(bottomLeft, topLeft)
            line(topLeft, topRight)
            line(topRight, midRight)
            line(midRight, midLeft)
            line(midLeft, bottomLeft)
            line(midLeft, bottomRight)
        }
    }
}
