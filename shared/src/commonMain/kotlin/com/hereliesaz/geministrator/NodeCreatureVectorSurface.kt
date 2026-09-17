package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.min

/**
 * Thin raster host for Rust-rendered node creatures.
 *
 * The Rust engine has already generated, animated, lit and projected the creature into flat vector
 * triangles and silhouette edges. Compose only paints that packet into the surrounding workflow UI.
 */
@Composable
internal fun NodeCreatureVectorSurface(
    packet: NodeCreatureRenderPacket,
    hueSeed: String,
    modifier: Modifier = Modifier,
) {
    val body = Azphalt.hue(hueSeed)
    val accent = Azphalt.cap(hueSeed)

    Canvas(modifier) {
        val scale = min(size.width, size.height) * 0.92f
        val origin = Offset(size.width * 0.5f, size.height * 0.5f)

        fun map(point: NodeCreaturePoint): Offset = Offset(
            x = origin.x + point.x * scale,
            y = origin.y + point.y * scale,
        )

        packet.triangles.forEach { triangle ->
            val path = Path().apply {
                val a = map(triangle.a)
                val b = map(triangle.b)
                val c = map(triangle.c)
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
                lineTo(c.x, c.y)
                close()
            }
            drawPath(
                path = path,
                color = nodeCreatureMaterialColor(
                    material = triangle.material,
                    shade = triangle.shade,
                    body = body,
                    accent = accent,
                ),
            )
        }

        packet.silhouetteEdges.forEach { edge ->
            drawLine(
                color = Azphalt.Ink,
                start = map(edge.from),
                end = map(edge.to),
                strokeWidth = (edge.weight * density).coerceAtLeast(1.2f),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Selects an actual projected antenna terminal rather than approximating a circular node edge. */
internal fun NodeCreatureRenderPacket.terminalAnchorToward(toward: NodeCreaturePoint): NodeCreaturePoint {
    if (terminalAnchors.isEmpty()) return NodeCreaturePoint(0f, 0f)
    val length = kotlin.math.sqrt(toward.x * toward.x + toward.y * toward.y)
    if (length <= 0.0001f) return NodeCreaturePoint(0f, 0f)
    val dx = toward.x / length
    val dy = toward.y / length
    return terminalAnchors.maxBy { terminal ->
        val terminalLength = kotlin.math.sqrt(terminal.x * terminal.x + terminal.y * terminal.y)
            .coerceAtLeast(0.0001f)
        terminal.x / terminalLength * dx + terminal.y / terminalLength * dy
    }
}

private fun nodeCreatureMaterialColor(
    material: NodeCreatureMaterial,
    shade: Int,
    body: Color,
    accent: Color,
): Color {
    val source = when (material) {
        NodeCreatureMaterial.Body -> body
        NodeCreatureMaterial.Accent -> accent
        NodeCreatureMaterial.Eye -> Azphalt.White
        NodeCreatureMaterial.Limb -> Azphalt.Ink
        NodeCreatureMaterial.Terminal -> accent
    }
    val factor = when (shade) {
        0 -> 0.58f
        1 -> 0.84f
        else -> 1.08f
    }
    return Color(
        red = (source.red * factor).coerceIn(0f, 1f),
        green = (source.green * factor).coerceIn(0f, 1f),
        blue = (source.blue * factor).coerceIn(0f, 1f),
        alpha = source.alpha,
    )
}
