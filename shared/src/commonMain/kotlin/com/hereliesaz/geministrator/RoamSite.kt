package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.creature_site
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** A place a shared creature works at is drawn this much smaller than a creature. */
internal const val ROAM_SITE_SCALE = 0.5f

internal fun lerpOffset(from: Offset, to: Offset, t: Float): Offset =
    Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

/**
 * A task of a shared creature that the creature is not at (CreatureRoam.kt): a dashed place in the
 * colour of how that task stands, named by its job. It is not a creature; it takes a tap, which opens
 * the task.
 */
@Composable
internal fun RoamSite(
    node: H2g2WorkflowNode,
    center: Offset,
    sizePx: Float,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { AzphaltMinTouchTarget.toPx() }
    val boxPx = maxOf(sizePx, touchPx)
    val color = node.state.mascotStateColor()
    val job = node.subtitle?.trim().orEmpty().ifEmpty { node.label }
    val description = stringResource(Res.string.creature_site, node.label, job)
    Box(
        modifier = Modifier
            .offset { IntOffset((center.x - boxPx / 2f).roundToInt(), (center.y - boxPx / 2f).roundToInt()) }
            .size(with(density) { boxPx.toDp() })
            .alpha(if (dimmed) 0.4f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) * 0.3f * (sizePx / boxPx).coerceAtLeast(0.6f)
            // Pending is Ink-dark on an Ink field, so the ring is lifted toward white to stay seen.
            val ring = androidx.compose.ui.graphics.lerp(color, Azphalt.White, 0.4f)
            drawCircle(ring.copy(alpha = 0.16f), radius = r)
            drawCircle(
                color = ring,
                radius = r,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                ),
            )
            if (selected) drawCircle(Azphalt.White.copy(alpha = 0.7f), radius = r * 1.25f, style = Stroke(width = 2.dp.toPx()))
        }
        Text(
            text = job,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = AzphaltType.endCap,
            color = color.contrastingText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = with(density) { (boxPx * 1.6f).toDp() })
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) color else color.copy(alpha = 0.82f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
