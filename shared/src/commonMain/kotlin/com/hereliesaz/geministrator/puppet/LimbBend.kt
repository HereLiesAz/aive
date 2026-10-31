package com.hereliesaz.geministrator.puppet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Limbs bend and stretch; they never rotate (docs/architecture/PUPPET_RIG_FORMAT.md, Limbs).
 *
 * A limb (a tendril or a tail) runs from its pivot (its base, where it meets the body) to its tip:
 * the pivot reflected through the centre of its drawn box. Its base keeps its rest direction. Its
 * `rotation` channel bends it: the tip goes where the channel points it, seen from the base, and
 * the limb curves evenly along its length (a circular arc) to get there ([LimbBend.bendOf]).
 * Stretching lengthens it along that arc.
 */

private val LIMB_ID = Regex("^(tendril|tail)(\\.(\\d+|base|mid|lower|upper))*$")

/** Tendrils and tails: by kind, or, in rigs from before kinds, by name (never an egg or a pod). */
val PuppetPart.isLimb: Boolean
    get() = kind == "tendril" || kind == "tail" || (kind == "other" && LIMB_ID.matches(id))

/** The limb's axis in part-local px: from the pivot to the tip. */
val PuppetPart.limbTipX: Float get() = (1f - 2f * pivot.x) * drawnWidth
val PuppetPart.limbTipY: Float get() = (1f - 2f * pivot.y) * drawnHeight

internal object LimbBend {
    private const val DEG = (PI / 180.0).toFloat()

    /**
     * A limb's bend from its rotation channel. The channel says where the tip goes, seen from the
     * base (as when limbs swung whole); an arc whose chord turns that far turns twice as far at
     * its tip, so the tip travels as it did and the limb curves to get there.
     */
    fun bendOf(rotation: Float): Float = 2f * rotation

    /**
     * Where a point of the limb lands once it bends. ([along], [across]) are the point's distance
     * along the limb's axis from its base and to the axis's right; [axisX], [axisY] the unit axis
     * the limb is laid along. The limb, [length] long at rest, bends by [bendDeg] (positive turns
     * the tip clockwise) over its whole drawn arc: [segments] copies end to end, each stretched by
     * [stretch]. [shift] moves the point out along the arc by that many limb lengths (copy j).
     * Writes the part-local x, y and the arc's direction there (radians) into [out].
     */
    fun place(
        along: Float,
        across: Float,
        axisX: Float,
        axisY: Float,
        length: Float,
        bendDeg: Float,
        stretch: Float,
        segments: Int,
        shift: Float,
        out: FloatArray,
    ) {
        val px = -axisY
        val py = axisX
        val s = (along + shift * length) * stretch
        val total = length * stretch * segments
        val theta = bendDeg * DEG
        if (s <= 0f || total <= 0f || abs(theta) < 1e-4f) {
            out[0] = s * axisX + across * px
            out[1] = s * axisY + across * py
            out[2] = 0f
            return
        }
        val k = theta / total
        val phi = k * s
        val cx = sin(phi) / k
        val cy = (1f - cos(phi)) / k
        val nx = -sin(phi)
        val ny = cos(phi)
        // In the (axis, right) frame: the arc point plus the offset across it, along its normal.
        val ax = cx + across * nx
        val ay = cy + across * ny
        out[0] = ax * axisX + ay * px
        out[1] = ax * axisY + ay * py
        out[2] = phi
    }

    /** [place] for a part-local point ([x], [y]) of [part] (its pivot is the origin). */
    fun placeLocal(
        part: PuppetPart,
        x: Float,
        y: Float,
        bendDeg: Float,
        stretch: Float,
        segments: Int,
        shift: Float,
        out: FloatArray,
    ) {
        val tx = part.limbTipX
        val ty = part.limbTipY
        val length = hypot(tx, ty)
        if (length <= 0f) {
            out[0] = x; out[1] = y; out[2] = 0f
            return
        }
        val ax = tx / length
        val ay = ty / length
        place(x * ax + y * ay, -x * ay + y * ax, ax, ay, length, bendDeg, stretch, segments, shift, out)
    }

    /**
     * The bend and stretch that carry the tip of a limb, [length] long along unit axis ([axisX],
     * [axisY]), to the part-local point ([toX], [toY]): the arc through the target that leaves the
     * base at its rest direction. Writes bend (degrees) and the arc length it needs into [out].
     */
    fun reach(toX: Float, toY: Float, axisX: Float, axisY: Float, out: FloatArray) {
        val along = toX * axisX + toY * axisY
        val across = -toX * axisY + toY * axisX
        val distance = hypot(toX, toY)
        // A chord at angle φ from the base direction is spanned by an arc turning 2φ.
        val phi = kotlin.math.atan2(across, along).coerceIn(-MAX_HALF_BEND, MAX_HALF_BEND)
        val theta = 2f * phi
        out[0] = theta / DEG
        out[1] = if (abs(phi) < 1e-4f) distance else distance * phi / sin(phi)
    }

    /** A reach never curls a limb further back than this (radians, half the bend). */
    private const val MAX_HALF_BEND = 1.4f
}

/** Grid cells across and along a limb's sprite: enough for a smooth bend. */
private const val LIMB_GRID = 10
private const val LIMB_VERTICES = (LIMB_GRID + 1) * (LIMB_GRID + 1)

/** The mesh's triangles: the same for every limb, so built once. */
private val LIMB_INDICES: List<Int> = ArrayList<Int>(LIMB_GRID * LIMB_GRID * 6).apply {
    val n = LIMB_GRID + 1
    for (gy in 0 until LIMB_GRID) {
        for (gx in 0 until LIMB_GRID) {
            val i = gy * n + gx
            add(i); add(i + 1); add(i + n)
            add(i + 1); add(i + n + 1); add(i + n)
        }
    }
}
private val LIMB_COLORS: List<Color> = List(LIMB_VERTICES) { Color.White }

/**
 * Reusable buffers for [drawLimbMesh], so a limb drawn every frame allocates only the [Vertices]
 * (which copies its lists into its own arrays). One per renderer; not for concurrent draws.
 */
internal class LimbMeshScratch {
    val positions = ArrayList<Offset>(LIMB_VERTICES)
    val texture = ArrayList<Offset>(LIMB_VERTICES)
    val point = FloatArray(3)
    val paint = Paint().apply { isAntiAlias = true }
}

/**
 * Draws a limb's [sprite] bent and stretched, as a textured mesh laid by [layLimbMesh].
 */
internal fun DrawScope.drawLimbMesh(
    atlas: ImageBitmap,
    sprite: PuppetPart,
    part: PuppetPart,
    worldArray: FloatArray,
    offset: Int,
    fit: PuppetFit,
    alpha: Float,
    bendDeg: Float,
    stretch: Float,
    segments: Int,
    shift: Float,
    shader: androidx.compose.ui.graphics.Shader,
    scratch: LimbMeshScratch = LimbMeshScratch(),
): Boolean {
    if (alpha <= 0f) return true
    // No axis to lay a mesh along (a centred pivot): the caller draws the sprite whole instead.
    if (!layLimbMesh(sprite, part, worldArray, offset, fit, bendDeg, stretch, segments, shift, scratch)) return false
    val paint = scratch.paint.apply {
        this.shader = shader
        this.alpha = alpha
    }
    drawContext.canvas.drawVertices(
        Vertices(VertexMode.Triangles, scratch.positions, scratch.texture, LIMB_COLORS, LIMB_INDICES),
        BlendMode.Modulate,
        paint,
    )
    return true
}

/**
 * Lays a limb's mesh into [scratch]: an 11×11 grid over [sprite], row by row from its top, each
 * vertex in draw-scope px with its atlas texel. [worldArray] holds the limb's world affine
 * `[a b c d tx ty]` (its rest direction, no bend) at [offset]; artboard px map to the draw scope
 * with [fit]. The curve is [part]'s: its axis and its length. An inherited limb's [sprite] (another
 * rig's) is laid along that axis and scaled evenly to that length, so its tip lands where this
 * rig's tip is, as [CompiledPuppetRig.along], reach, the pulse, children and attachments reckon it.
 * [shift] is the copy's place along a multi-segment limb. False, laying nothing, for an empty sprite.
 */
internal fun layLimbMesh(
    sprite: PuppetPart,
    part: PuppetPart,
    worldArray: FloatArray,
    offset: Int,
    fit: PuppetFit,
    bendDeg: Float,
    stretch: Float,
    segments: Int,
    shift: Float,
    scratch: LimbMeshScratch,
): Boolean {
    val rect = sprite.rect ?: return false
    if (rect.w <= 0 || rect.h <= 0) return false
    val stx = sprite.limbTipX
    val sty = sprite.limbTipY
    val spriteLength = hypot(stx, sty)
    val ptx = part.limbTipX
    val pty = part.limbTipY
    val partLength = hypot(ptx, pty)
    if (spriteLength <= 0f || partLength <= 0f) return false
    val sax = stx / spriteLength
    val say = sty / spriteLength
    val ax = ptx / partLength
    val ay = pty / partLength
    // The sprite scaled to the limb's length: 1 for the limb's own sprite.
    val toLength = partLength / spriteLength
    val w = worldArray
    val o = offset
    val left = -sprite.pivot.x * sprite.drawnWidth
    val top = -sprite.pivot.y * sprite.drawnHeight
    val out = scratch.point
    val positions = scratch.positions.apply { clear() }
    val texture = scratch.texture.apply { clear() }
    for (gy in 0..LIMB_GRID) {
        val fy = gy / LIMB_GRID.toFloat()
        for (gx in 0..LIMB_GRID) {
            val fx = gx / LIMB_GRID.toFloat()
            val x = left + fx * sprite.drawnWidth
            val y = top + fy * sprite.drawnHeight
            LimbBend.place(
                (x * sax + y * say) * toLength, (-x * say + y * sax) * toLength, ax, ay, partLength,
                bendDeg, stretch, segments, shift, out,
            )
            val wx = w[o] * out[0] + w[o + 2] * out[1] + w[o + 4]
            val wy = w[o + 1] * out[0] + w[o + 3] * out[1] + w[o + 5]
            positions += Offset(fit.offsetX + wx * fit.scale, fit.offsetY + wy * fit.scale)
            texture += Offset(rect.x + fx * rect.w, rect.y + fy * rect.h)
        }
    }
    return true
}
