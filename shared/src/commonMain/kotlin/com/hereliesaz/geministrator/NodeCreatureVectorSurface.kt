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
    roleLabel: String,
    modifier: Modifier = Modifier,
) {
    val palette = nodeCreaturePalette(roleLabel, hueSeed)

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
                    body = palette.body,
                    accent = palette.accent,
                ),
            )
        }

        packet.silhouetteEdges.forEach { edge ->
            drawLine(
                color = Azphalt.Ink,
                start = map(edge.from),
                end = map(edge.to),
                strokeWidth = (edge.weight * density * 1.65f).coerceAtLeast(1.8f),
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

private data class NodeCreaturePalette(
    val body: Color,
    val accent: Color,
)

private fun nodeCreaturePalette(roleLabel: String, hueSeed: String): NodeCreaturePalette =
    when (classifyNodeCreatureRole(roleLabel)) {
        NodeCreatureRoleKind.HallMonitor -> NodeCreaturePalette(
            body = Color(0xFF69A9EF),
            accent = Color(0xFF214D85),
        )
        NodeCreatureRoleKind.Antagonist -> NodeCreaturePalette(
            body = Color(0xFF191919),
            accent = Color(0xFFA10A5A),
        )
        NodeCreatureRoleKind.AdversarialReviewer -> NodeCreaturePalette(
            body = Color(0xFFF12E3D),
            accent = Color(0xFF721722),
        )
        NodeCreatureRoleKind.RecoveryEngineer -> NodeCreaturePalette(
            body = Color(0xFF35B96A),
            accent = Color(0xFFEAC58A),
        )
        NodeCreatureRoleKind.ReleaseEngineer -> NodeCreaturePalette(
            body = Color(0xFFA464E6),
            accent = Color(0xFFF0A400),
        )
        NodeCreatureRoleKind.CodeReviewer -> NodeCreaturePalette(
            body = Color(0xFF2F70E8),
            accent = Color(0xFF173D7A),
        )
        NodeCreatureRoleKind.CrashTestDummy -> NodeCreaturePalette(
            body = Color(0xFFFFC928),
            accent = Color(0xFF3AA9EA),
        )
        NodeCreatureRoleKind.QaEngineer -> NodeCreaturePalette(
            body = Color(0xFF39BFEA),
            accent = Color(0xFF21395E),
        )
        NodeCreatureRoleKind.ImplementationEngineer -> NodeCreaturePalette(
            body = Color(0xFFFF7A00),
            accent = Color(0xFF5F7898),
        )
        NodeCreatureRoleKind.UxDesigner -> NodeCreaturePalette(
            body = Color(0xFFF35F8A),
            accent = Color(0xFF8A2C50),
        )
        NodeCreatureRoleKind.EpaRepresentative -> NodeCreaturePalette(
            body = Color(0xFF38B84A),
            accent = Color(0xFF1C7130),
        )
        NodeCreatureRoleKind.Architect -> NodeCreaturePalette(
            body = Color(0xFF8B59E8),
            accent = Color(0xFF5A35A7),
        )
        NodeCreatureRoleKind.Researcher -> NodeCreaturePalette(
            body = Color(0xFF39A9EA),
            accent = Color(0xFF165AA5),
        )
        NodeCreatureRoleKind.ProductManager -> NodeCreaturePalette(
            body = Color(0xFFF15F89),
            accent = Color(0xFF7A3150),
        )
        NodeCreatureRoleKind.Orchestrator -> NodeCreaturePalette(
            body = Color(0xFFF5B82E),
            accent = Color(0xFF16223C),
        )
        NodeCreatureRoleKind.Generic -> NodeCreaturePalette(
            body = Azphalt.hue(hueSeed),
            accent = Azphalt.cap(hueSeed),
        )
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
        NodeCreatureMaterial.Eye -> accent
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
