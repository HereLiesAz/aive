package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition

private val TissueCore = Color(0xFF2A231E)
private val TissueEdge = Color(0xFF141110)
private val Capillary = Color(0xFFC6392F)

/** The tissue ground: dark, lit from the centre, with one capillary per provider in use. */
internal fun DrawScope.drawTissueGround(
    tissue: TissueScene,
    toScreen: (H2g2TerrariumPosition) -> Offset,
) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(TissueCore, TissueEdge),
            center = Offset(size.width / 2f, size.height * 0.45f),
            radius = maxOf(size.width, size.height) * 0.75f,
        ),
    )
    tissue.capillaries.forEach { capillary ->
        val start = toScreen(capillary.start)
        val end = toScreen(capillary.end)
        drawLine(Capillary.copy(alpha = 0.35f), start, end, strokeWidth = 22.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Capillary.copy(alpha = 0.9f), start, end, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * The run as an egg. Every task being worked on feeds it through a tail from its creature; it swells
 * with completed tasks, glows yellow while gestating, and greys when the run fails.
 */
internal fun DrawScope.drawTissueEgg(
    egg: TissueEgg,
    zoom: Float,
    toScreen: (H2g2TerrariumPosition) -> Offset,
) {
    val at = toScreen(egg.position)
    val shell = Azphalt.Yellow
    val t = egg.growth.coerceIn(0f, 1f)
    val rx = (7f + 12f * t).dp.toPx() * zoom
    val ry = (9f + 15f * t).dp.toPx() * zoom
    drawOval(shell, topLeft = Offset(at.x - rx, at.y - ry), size = Size(rx * 2f, ry * 2f))
    val hx = (2f + 3f * t).dp.toPx() * zoom
    val hy = (3f + 4f * t).dp.toPx() * zoom
    drawOval(
        Color.White.copy(alpha = 0.5f),
        topLeft = Offset(at.x - rx * 0.3f - hx, at.y - ry * 0.35f - hy),
        size = Size(hx * 2f, hy * 2f),
    )
}
