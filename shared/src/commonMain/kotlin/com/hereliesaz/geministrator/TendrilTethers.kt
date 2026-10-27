package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import kotlin.math.hypot

/**
 * Creatures are joined by their tendrils (docs/Node-Creature-Genome.md, Wiring), and a tendril only
 * stretches so far. Positions are normalised; [widthPx] and [heightPx] turn them into pixels so the
 * limit is a real length. Any pair farther apart than [maxPx] is drawn back together; [pinned]
 * (the creature being dragged) never moves, so its partners are pulled along behind it.
 */
internal fun constrainTendrils(
    positions: Map<String, H2g2TerrariumPosition>,
    links: List<Pair<String, String>>,
    widthPx: Float,
    heightPx: Float,
    maxPx: Float,
    pinned: String? = null,
    iterations: Int = 6,
): Map<String, H2g2TerrariumPosition> {
    if (links.isEmpty() || widthPx <= 0f || heightPx <= 0f) return positions
    val x = positions.mapValues { it.value.x * widthPx }.toMutableMap()
    val y = positions.mapValues { it.value.y * heightPx }.toMutableMap()
    repeat(iterations) {
        for ((a, b) in links) {
            val ax = x[a] ?: continue
            val ay = y[a] ?: continue
            val bx = x[b] ?: continue
            val by = y[b] ?: continue
            val d = hypot(bx - ax, by - ay)
            if (d <= maxPx || d == 0f) continue
            val excess = d - maxPx
            val ux = (bx - ax) / d
            val uy = (by - ay) / d
            // Share the correction, unless one end is held.
            val (shareA, shareB) = when (pinned) {
                a -> 0f to 1f
                b -> 1f to 0f
                else -> 0.5f to 0.5f
            }
            x[a] = ax + ux * excess * shareA
            y[a] = ay + uy * excess * shareA
            x[b] = bx - ux * excess * shareB
            y[b] = by - uy * excess * shareB
        }
    }
    return positions.mapValues { (id, _) -> H2g2TerrariumPosition(x.getValue(id) / widthPx, y.getValue(id) / heightPx).clamped() }
}

/** How far a tendril is stretched past its rest length, 0 (slack) to 1 (at its limit). */
internal fun tendrilStretch(distancePx: Float, restPx: Float, maxPx: Float): Float =
    ((distancePx - restPx) / (maxPx - restPx)).coerceIn(0f, 1f)
