package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotateRad
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Providers as blood supply (docs/Swarm-OS.md: "providers are capillaries"). Each provider is an
 * arteriole that enters from the field's edge, forks and tapers toward the creatures it feeds, and
 * ends in a fine capillary bed around each of them. Red cells pass in single file while it serves
 * work. Geometry only here; [drawVasculature] paints it.
 */
internal data class Vessel(
    /** Centre line, start to end. */
    val points: List<Offset>,
    val startWidth: Float,
    val endWidth: Float,
)

/**
 * A provider's vessels, grown by space colonisation (the algorithm used to model leaf veins and
 * retinal vasculature): from [entry], tips extend [step] at a time toward the attraction points
 * around them, attractions reached are consumed, and the tree that results branches and fills the
 * way real microvasculature does. Widths follow Murray's law from the tips back (cube of a parent's
 * radius = sum of its children's), from [minWidth] at a tip to at most [maxWidth].
 * [targets] are the creatures it feeds (centre, radius): attraction is densest in a ring around
 * each, where the finest vessels gather into a bed. Deterministic in [seed].
 */
internal fun grownVasculature(
    entry: Offset,
    targets: List<Pair<Offset, Float>>,
    bounds: Size,
    step: Float,
    minWidth: Float,
    maxWidth: Float,
    seed: Int,
): List<Vessel> {
    if (targets.isEmpty()) return emptyList()
    val random = kotlin.random.Random(seed)
    val attractions = mutableListOf<Offset>()
    targets.forEach { (c, r) ->
        repeat(150) {
            val a = random.nextFloat() * 6.2832f
            // Densest at the rim, thinning outward: a bed, not a ring.
            val d = r * (0.95f + 1.9f * random.nextFloat() * random.nextFloat())
            attractions += c + Offset(cos(a) * d, sin(a) * d)
        }
        // A sparser scatter along the way in, so trunks wander and send side branches.
        repeat(55) {
            val t = random.nextFloat()
            val p = entry + (c - entry) * t
            val spread = (c - entry).getDistance() * 0.35f * sin(PI.toFloat() * t)
            attractions += p + Offset((random.nextFloat() - 0.5f) * 2f * spread, (random.nextFloat() - 0.5f) * 2f * spread)
        }
    }
    attractions.removeAll { it.x < -step || it.y < -step || it.x > bounds.width + step || it.y > bounds.height + step }

    val xs = ArrayList<Offset>().apply { add(entry) }
    val parent = ArrayList<Int>().apply { add(-1) }
    val influence = step * 12f
    val kill = step * 1.2f
    // First run the trunk in straight from the edge until it is within reach of the attractions.
    val nearest = targets.minBy { (it.first - entry).getDistance() }.first
    while (attractions.none { (it - xs.last()).getDistance() < influence } && (nearest - xs.last()).getDistance() > step) {
        val d = nearest - xs.last()
        xs += xs.last() + d / d.getDistance() * step
        parent += xs.size - 2
    }
    repeat(600) {
        if (attractions.isEmpty()) return@repeat
        val pull = HashMap<Int, Offset>()
        val count = HashMap<Int, Int>()
        for (a in attractions) {
            var best = -1
            var bestD = influence
            for (i in xs.indices) {
                val d = (a - xs[i]).getDistance()
                if (d < bestD) { bestD = d; best = i }
            }
            if (best < 0) continue
            val dir = (a - xs[best]) / bestD
            pull[best] = (pull[best] ?: Offset.Zero) + dir
            count[best] = (count[best] ?: 0) + 1
        }
        if (pull.isEmpty()) return@repeat
        for ((i, sum) in pull) {
            val len = sum.getDistance().takeIf { it > 0f } ?: continue
            // A little wander, so no vessel is ruler-straight.
            val jitter = Offset(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) * 0.35f
            val dir = sum / len + jitter
            xs += xs[i] + dir / dir.getDistance() * step
            parent += i
        }
        attractions.removeAll { a -> xs.any { (a - it).getDistance() < kill } }
    }

    // Murray's law, tips first: r^3 of a node is the sum over its children.
    val children = IntArray(xs.size)
    for (i in 1 until xs.size) children[parent[i]]++
    val cube = FloatArray(xs.size) { if (children[it] == 0) minWidth * minWidth * minWidth else 0f }
    for (i in xs.indices.reversed()) {
        val p = parent[i]
        if (p >= 0) cube[p] += cube[i]
    }
    fun width(i: Int) = kotlin.math.cbrt(cube[i].toDouble()).toFloat().coerceIn(minWidth, maxWidth)

    // Chains between forks become vessels, smoothed so steps don't show.
    val vessels = mutableListOf<Vessel>()
    val kids = Array(xs.size) { mutableListOf<Int>() }
    for (i in 1 until xs.size) kids[parent[i]] += i
    fun chain(start: Int, first: Int) {
        val points = mutableListOf(xs[start])
        var i = first
        while (true) {
            points += xs[i]
            if (kids[i].size != 1) break
            i = kids[i][0]
        }
        vessels += Vessel(smooth(points), width(first), width(i))
        kids[i].forEach { chain(i, it) }
    }
    kids[0].forEach { chain(0, it) }
    return vessels
}

/** Chaikin corner-cutting, twice: a polyline made organic. */
private fun smooth(points: List<Offset>): List<Offset> {
    var p = points
    repeat(2) {
        if (p.size < 3) return p
        val out = ArrayList<Offset>(p.size * 2)
        out += p.first()
        for (i in 0 until p.size - 1) {
            out += p[i] * 0.75f + p[i + 1] * 0.25f
            out += p[i] * 0.25f + p[i + 1] * 0.75f
        }
        out += p.last()
        p = out
    }
    return p
}

/** A gently wandering line from [a] to [b], fixed at both ends. */
internal fun meander(a: Offset, b: Offset, seed: Int, amplitude: Float = 0.06f): List<Offset> {
    val d = b - a
    val length = d.getDistance()
    if (length < 1f) return listOf(a, b)
    val across = Offset(-d.y / length, d.x / length)
    val steps = max(8, (length / 6f).toInt())
    val f1 = 1f + hash01(seed, 1) * 1.2f
    val f2 = 5f + hash01(seed, 2) * 3f
    val p1 = hash01(seed, 3) * 6.28f
    val p2 = hash01(seed, 4) * 6.28f
    return (0..steps).map { s ->
        val t = s / steps.toFloat()
        val envelope = sin(PI.toFloat() * t)
        val off = length * (amplitude * sin(2f * PI.toFloat() * f1 * t + p1) + amplitude * 0.22f * sin(2f * PI.toFloat() * f2 * t + p2)) * envelope
        a + d * t + across * off
    }
}

private fun hash01(seed: Int, salt: Int): Float {
    var h = seed * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
}

private val VesselWall = Color(0xFF3A0C0A)
private val VesselLumen = Color(0xFF8C1F19)
private val VesselSheen = Color(0xFFE8786A)
private val RedCell = Color(0xFFE2463A)
private val RedCellRim = Color(0xFF7A1410)

/**
 * Paints [vessels]: a dark wall, the blood within, and a wet sheen, tapering along each vessel.
 * While [flowing], red cells pass along in single file at clock [phase] (0..1, looping).
 */
internal fun DrawScope.drawVasculature(vessels: List<Vessel>, flowing: Boolean, phase: Float, cell: Float) {
    vessels.forEach { vessel ->
        val pts = vessel.points
        val n = pts.size - 1
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val w = vessel.startWidth + (vessel.endWidth - vessel.startWidth) * t
            drawLine(VesselWall.copy(alpha = 0.45f), pts[i], pts[i + 1], strokeWidth = w * 2.2f, cap = StrokeCap.Round)
        }
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val w = vessel.startWidth + (vessel.endWidth - vessel.startWidth) * t
            drawLine(VesselLumen.copy(alpha = 0.85f), pts[i], pts[i + 1], strokeWidth = w, cap = StrokeCap.Round)
        }
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val w = vessel.startWidth + (vessel.endWidth - vessel.startWidth) * t
            if (w < 3f) continue
            val a = pts[i]
            val b = pts[i + 1]
            val along = b - a
            val len = along.getDistance().takeIf { it > 0f } ?: continue
            val shift = Offset(-along.y / len, along.x / len) * (w * 0.22f)
            drawLine(VesselSheen.copy(alpha = 0.28f), a - shift, b - shift, strokeWidth = w * 0.22f, cap = StrokeCap.Round)
        }
    }
    if (!flowing) return
    vessels.forEach { vessel ->
        // Cells ride only where a vessel can hold them; in the finest, flow shows as the blood itself.
        if (vessel.startWidth < cell * 1.5f) return@forEach
        val pts = vessel.points
        // Cumulative length, to place cells evenly along the vessel.
        val lengths = FloatArray(pts.size)
        for (i in 1 until pts.size) lengths[i] = lengths[i - 1] + (pts[i] - pts[i - 1]).getDistance()
        val total = lengths.last()
        if (total <= 0f) return@forEach
        val spacing = cell * 6.5f
        var at = (phase * spacing * 6f) % spacing
        var seg = 1
        while (at < total) {
            while (seg < pts.size - 1 && lengths[seg] < at) seg++
            val a = pts[seg - 1]
            val b = pts[seg]
            val span = (lengths[seg] - lengths[seg - 1]).takeIf { it > 0f } ?: 1f
            val p = a + (b - a) * ((at - lengths[seg - 1]) / span)
            val w = vessel.startWidth + (vessel.endWidth - vessel.startWidth) * (at / total)
            val r = (w * 0.38f).coerceIn(cell * 0.6f, cell * 1.6f)
            rotateRad(atan2(b.y - a.y, b.x - a.x), pivot = p) {
                drawOval(RedCellRim.copy(alpha = 0.7f), topLeft = Offset(p.x - r * 1.25f, p.y - r), size = Size(r * 2.5f, r * 2f))
                drawOval(RedCell.copy(alpha = 0.8f), topLeft = Offset(p.x - r, p.y - r * 0.75f), size = Size(r * 2f, r * 1.5f))
            }
            at += spacing
        }
    }
}

/** Where a provider's arteriole enters: one edge per provider in turn, level with what it feeds. */
internal fun vesselEntry(index: Int, toward: Offset, width: Float, height: Float): Offset = when (index % 4) {
    0 -> Offset(-8f, toward.y)
    1 -> Offset(width + 8f, toward.y)
    2 -> Offset(toward.x, height + 8f)
    else -> Offset(toward.x, -8f)
}

/** Kept for callers measuring reach; a vessel's drawn length. */
internal fun Vessel.length(): Float = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
