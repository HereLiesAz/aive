package com.hereliesaz.geministrator

import kotlin.math.sqrt

/**
 * The hive's light, handed to the GPU: a small list of soft lights the fragment shader adds near a point. The mesh stays
 * static; only these change as the run's own records say something happened (SpatialSignals.kt): an activated memory
 * flipping blue, a pulse travelling down the tissue toward whom a creature is talking, a creature beating at its rate,
 * a creature bursting as it finishes. Nothing here is evidence of anything: it only draws what the records say.
 */
internal data class SpatialLight(
    val at: SpatialPoint, val radius: Float, val red: Float, val green: Float, val blue: Float, val strength: Float,
)

/** The most lights one frame may carry; the shader's arrays are this long. The strongest are kept. */
internal const val MAX_SPATIAL_LIGHTS = 48

private const val BLUE_R = .25f
private const val BLUE_G = .55f
private const val BLUE_B = 1f

/** The point [fraction] (0 to 1) of the way along a polyline, by length. A line of fewer than two points is its first point. */
internal fun pointAlong(points: List<SpatialPoint>, fraction: Float): SpatialPoint {
    if (points.size < 2) return points.firstOrNull() ?: SpatialPoint.Origin
    val lengths = points.zipWithNext { a, b ->
        val d = b - a
        sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
    }
    val total = lengths.sum()
    if (total <= 0f) return points.first()
    var remaining = fraction.coerceIn(0f, 1f) * total
    for (i in lengths.indices) {
        if (remaining <= lengths[i] || i == lengths.lastIndex) {
            val t = if (lengths[i] <= 0f) 0f else (remaining / lengths[i]).coerceIn(0f, 1f)
            return points[i] + (points[i + 1] - points[i]) * t
        }
        remaining -= lengths[i]
    }
    return points.last()
}

/**
 * The lights for one instant. [rates] and [phases] are each working creature's beat rate and phase by task id
 * (SpatialSignals.kt); a creature with no rate does not beat. Only a spore that is in the frame flips blue, and only
 * a connection that is in the scene carries a pulse. Sorted strongest first and cut at [MAX_SPATIAL_LIGHTS].
 */
internal fun spatialLights(
    nowEpochMillis: Long, scene: SpatialHiveScene, frame: SpatialRenderFrame,
    uses: List<SporeUse>, pulses: List<TransferPulse>, rates: Map<String, Float>, phases: Map<String, Float>,
    bursts: List<CompletionBurst> = emptyList(), pulseRates: Map<String, Float> = rates,
): List<SpatialLight> {
    val bodies = frame.bodies.associateBy { it.id }
    val lights = mutableListOf<SpatialLight>()
    // A used memory flips from its warm glow to blue, and only that memory.
    val spores = frame.spores.associateBy { it.id }
    for ((id, mix) in blueSpores(nowEpochMillis, uses, spores.keys)) {
        val spore = spores.getValue(id)
        lights += SpatialLight(spore.position, maxOf(.2f, spore.radius * 2.4f), BLUE_R, BLUE_G, BLUE_B, 1.7f * mix)
    }
    // A creature's own light beats at its rate.
    for ((id, rate) in rates) {
        val body = bodies[id] ?: continue
        if (rate <= 0f) continue
        val glow = beatLight(phases[id] ?: 0f)
        if (glow > .01f) lights += SpatialLight(body.position, body.radius * 2.2f, 1f, .72f, .35f, .5f * glow)
    }
    // Pulses leave on the sender's beats and travel down the connection toward the one it is talking to.
    for (pulse in pulses) {
        val a = bodies[pulse.from.value]?.position ?: continue
        val b = bodies[pulse.to.value]?.position ?: continue
        if (scene.connections.none { it.from == pulse.from.value && it.to == pulse.to.value }) continue
        val rate = pulseRates[pulse.from.value] ?: continue
        val path = spatialHyphaPoints("link:" + pulse.from.value + ":" + pulse.to.value, a, b)
        for (fraction in pulsesInFlight(nowEpochMillis, pulse, rate)) {
            lights += SpatialLight(pointAlong(path, fraction), .42f, 1f, .86f, .55f, 1.3f)
        }
    }
    // A finishing creature's light bursts.
    for (burst in bursts) {
        val body = bodies[burst.task.value] ?: continue
        val flash = burstLight(nowEpochMillis, burst)
        if (flash > .01f) lights += SpatialLight(body.position, body.radius * 3.4f, 1f, .85f, .6f, 1f * flash)
    }
    return lights.sortedByDescending { it.strength }.take(MAX_SPATIAL_LIGHTS)
}
