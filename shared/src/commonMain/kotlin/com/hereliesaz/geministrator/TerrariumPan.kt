package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import kotlin.math.hypot

/*
 * What a finger does on the hive's open ground (Build and Live alike). Decided here, away from the
 * pointer plumbing in MascotNodeTerrarium.kt, so it can be tested:
 *
 *  - one finger that starts on empty ground pans the camera;
 *  - one finger that starts on a strand cuts it (only while the strand can be cut, i.e. in Build);
 *  - two fingers zoom and pan together;
 *  - a strand is drawn only from a creature's tail, and a creature is moved by its body. Those are
 *    handled on the creature itself and never reach the ground.
 *
 * Cutting used to be the default for any drag on empty ground, which left no way to pan.
 */

/** What a one-finger drag that began on open ground is for. */
internal enum class CanvasDragIntent { Pan, Slice }

/** A strand within this distance of the finger counts as touched (dp at 48dp-target scale, in px at density 1). */
internal const val STRAND_TOUCH_DP = 20f

/** How far past the outermost creatures the camera may look, as a fraction of the field. */
internal const val PAN_MARGIN = 0.1f

/**
 * A drag that starts on a strand cuts it, when [sliceAvailable]; any other drag on the ground pans.
 * Slice is not offered in Live, where the wiring cannot be edited, so a drag there always pans.
 */
internal fun canvasDragIntent(startedOnStrand: Boolean, sliceAvailable: Boolean): CanvasDragIntent =
    if (startedOnStrand && sliceAvailable) CanvasDragIntent.Slice else CanvasDragIntent.Pan

/** The shortest distance from [p] to the polyline [line]; infinity for an empty line. */
internal fun distanceToPolyline(p: Offset, line: List<Offset>): Float {
    if (line.isEmpty()) return Float.POSITIVE_INFINITY
    if (line.size == 1) return (p - line[0]).getDistance()
    return line.zipWithNext().minOf { (a, b) -> distanceToSegment(p, a, b) }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared <= 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSquared).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
}

/** Whether a touch at [p] lands on any of the [strands] (each a drawn polyline) within [tolerancePx]. */
internal fun touchesStrand(p: Offset, strands: Collection<List<Offset>>, tolerancePx: Float): Boolean =
    strands.any { distanceToPolyline(p, it) <= tolerancePx }

/**
 * [focus] kept where the hive can still be seen: inside the box around [positions] widened by
 * [margin] (and the field itself). With no creatures the camera rests at the centre.
 */
internal fun clampTerrariumFocus(
    focus: Offset,
    positions: Collection<H2g2TerrariumPosition>,
    margin: Float = PAN_MARGIN,
): Offset {
    if (positions.isEmpty()) return Offset(0.5f, 0.5f)
    val minX = (positions.minOf { it.x } - margin).coerceAtLeast(0f)
    val maxX = (positions.maxOf { it.x } + margin).coerceAtMost(1f)
    val minY = (positions.minOf { it.y } - margin).coerceAtLeast(0f)
    val maxY = (positions.maxOf { it.y } + margin).coerceAtMost(1f)
    return Offset(focus.x.coerceIn(minX, maxX), focus.y.coerceIn(minY, maxY))
}

/**
 * The focus after the ground is dragged by [dragPx] screen pixels: the ground follows the finger,
 * so the focus moves the other way, by the drag over the field's size at [zoom]. Clamped by
 * [clampTerrariumFocus].
 */
internal fun pannedTerrariumFocus(
    focus: Offset,
    dragPx: Offset,
    widthPx: Float,
    heightPx: Float,
    zoom: Float,
    positions: Collection<H2g2TerrariumPosition>,
): Offset {
    val w = (widthPx * zoom).coerceAtLeast(1f)
    val h = (heightPx * zoom).coerceAtLeast(1f)
    return clampTerrariumFocus(Offset(focus.x - dragPx.x / w, focus.y - dragPx.y / h), positions)
}
