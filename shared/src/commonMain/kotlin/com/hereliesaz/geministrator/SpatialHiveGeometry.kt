package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** World-space coordinates, independent of the viewport, execution and workflow wiring. Y is north. */
@Serializable
internal data class SpatialPoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: SpatialPoint) = SpatialPoint(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: SpatialPoint) = SpatialPoint(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = SpatialPoint(x * scale, y * scale, z * scale)
    fun length() = sqrt(x * x + y * y + z * z)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun cross(other: SpatialPoint) = SpatialPoint(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun unit(): SpatialPoint = length().takeIf { it > 0.00001f }?.let { this * (1f / it) } ?: Origin

    companion object {
        val Origin = SpatialPoint(0f, 0f, 0f)
    }
}

/** FNV-1a visual seed. Not an identifier, a unique run fingerprint or a credential. */
internal fun spatialSeed(key: String): Int {
    var hash = 0x811c9dc5.toInt()
    key.forEach { hash = (hash xor it.code) * 0x01000193 }
    return hash
}

/**
 * Allocate previously unseen organism sites. Existing finite coordinates are reserved, including
 * temporarily absent organisms, so reordering or inserting tasks cannot move established sites.
 * The Queen always occupies the origin. The stored map, not the random generator, is the layout
 * authority. No execution relationship changes when a point changes.
 */
internal fun spatialCreaturePositions(
    workflow: String,
    ids: List<String>,
    queen: String,
    saved: Map<String, SpatialPoint> = emptyMap(),
): Map<String, SpatialPoint> {
    val out = saved.filter { (id, point) -> id != queen && point.finite() && point.length() >= 1.7f }.toMutableMap()
    out[queen] = SpatialPoint.Origin
    for (id in ids.distinct()) {
        if (id in out) continue
        val random = Random(spatialSeed("$workflow\u0000$id"))
        var placed = false
        repeat(256) { attempt ->
            if (!placed) {
                val y = random.nextFloat() * 2f - 1f
                val angle = random.nextFloat() * (2f * PI.toFloat())
                val radial = sqrt((1f - y * y).coerceAtLeast(0f))
                val radius = 2.1f + random.nextFloat() * 2.5f + (attempt / 32) * 1.8f
                val p = SpatialPoint(cos(angle) * radial, y, sin(angle) * radial) * radius
                if (out.values.all { (it - p).length() >= 1.7f }) {
                    out[id] = p
                    placed = true
                }
            }
        }
        if (!placed) {
            out[id] = SpatialPoint((out.values.maxOfOrNull { it.length() } ?: 0f) + 2f, 0f, 0f)
        }
    }
    return out
}

/** Lift the real Memory Web into the colony's depth-varying lower bed, visible from the north pole. */
internal fun spatialSporePosition(id: String, x: Float, y: Float, extent: Float): SpatialPoint {
    val scale = minOf(.05f, 4.2f / extent.coerceAtLeast(1f))
    val rng = Random(spatialSeed("spore\u0000$id"))
    return SpatialPoint(x * scale, -2.3f + (rng.nextFloat() - .5f) * 1.4f, y * scale)
}

/** Transverse deviations vanish at both ends; the cord remains physically attached to its spores. */
internal fun spatialHyphaPoints(id: String, from: SpatialPoint, to: SpatialPoint): List<SpatialPoint> {
    val direction = (to - from).unit()
    if (direction == SpatialPoint.Origin) return listOf(from, to)
    val reference = if (abs(direction.y) < .8f) SpatialPoint(0f, 1f, 0f) else SpatialPoint(1f, 0f, 0f)
    val a = direction.cross(reference).unit()
    val b = direction.cross(a).unit()
    val phase = Random(spatialSeed("hypha\u0000$id")).nextFloat() * 2f * PI.toFloat()
    val amplitude = minOf(.55f, (to - from).length() * .17f)
    return (0..12).map { i ->
        if (i == 0) from else if (i == 12) to else {
            val t = i / 12f
            val envelope = sin(PI.toFloat() * t) * amplitude
            from + (to - from) * t + a * (sin(phase + t * 5f) * envelope) +
                b * (cos(phase + t * 7f) * envelope * .55f)
        }
    }
}
