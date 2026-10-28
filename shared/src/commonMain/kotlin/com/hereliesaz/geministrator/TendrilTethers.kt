package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
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
    /** Bodies closer than this push apart, so creatures never sit on top of each other. */
    minPx: Float = 0f,
    /** Linked bodies may come this close (their tendrils are short); unlinked keep [minPx]. */
    linkedMinPx: Float = minPx,
    /** Bodies stay this far inside the field's edges, so none is cut off. */
    marginPx: Float = 0f,
): Map<String, H2g2TerrariumPosition> {
    if ((links.isEmpty() && minPx <= 0f) || widthPx <= 0f || heightPx <= 0f) return positions
    val x = positions.mapValues { it.value.x * widthPx }.toMutableMap()
    val y = positions.mapValues { it.value.y * heightPx }.toMutableMap()
    val ids = positions.keys.toList()
    val linked = links.flatMap { (a, b) -> listOf(a to b, b to a) }.toSet()
    fun share(a: String, b: String) = when (pinned) {
        a -> 0f to 1f
        b -> 1f to 0f
        else -> 0.5f to 0.5f
    }
    repeat(iterations) {
        if (minPx > 0f) {
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val a = ids[i]; val b = ids[j]
                val dx = x.getValue(b) - x.getValue(a)
                val dy = y.getValue(b) - y.getValue(a)
                val d = hypot(dx, dy)
                val floor = if ((a to b) in linked) linkedMinPx else minPx
                if (d >= floor) continue
                // Coincident bodies part sideways.
                val (ux, uy) = if (d == 0f) 1f to 0f else dx / d to dy / d
                val gap = floor - d
                val (sa, sb) = share(a, b)
                x[a] = x.getValue(a) - ux * gap * sa; y[a] = y.getValue(a) - uy * gap * sa
                x[b] = x.getValue(b) + ux * gap * sb; y[b] = y.getValue(b) + uy * gap * sb
            }
        }
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
        if (marginPx > 0f) ids.forEach { id ->
            if (id == pinned) return@forEach
            x[id] = x.getValue(id).coerceIn(minOf(marginPx, widthPx / 2f), maxOf(widthPx - marginPx, widthPx / 2f))
            y[id] = y.getValue(id).coerceIn(minOf(marginPx, heightPx / 2f), maxOf(heightPx - marginPx, heightPx / 2f))
        }
    }
    return positions.mapValues { (id, _) -> H2g2TerrariumPosition(x.getValue(id) / widthPx, y.getValue(id) / heightPx).clamped() }
}

/** How far a tendril is stretched past its rest length, 0 (slack) to 1 (at its limit). */
internal fun tendrilStretch(distancePx: Float, restPx: Float, maxPx: Float): Float =
    ((distancePx - restPx) / (maxPx - restPx)).coerceIn(0f, 1f)

/**
 * A clasp between two tendrils (docs/Swarm-OS.md): the energy pulse running through it, coloured
 * by what the downstream creature waits for (its knot), and the clasp's own glow, coloured by how
 * the upstream work came out.
 */
internal enum class ClaspOutcome {
    /** No result yet. */
    None,
    /** The upstream work succeeded: green. */
    Succeeded,
    /** It failed, or the far side is blocked: red. */
    Failed,
}

internal enum class ClaspFlow {
    /** Nothing runs. */
    Still,
    /** The pulse runs base to clasp to base: waiting on the upstream, or its result crossing. */
    Through,
    /** Blocked: the pulse runs up to the clasp and stops there. */
    ToClasp,
}

internal data class Clasp(val outcome: ClaspOutcome, val flow: ClaspFlow)

private val Working = setOf(H2g2WorkflowState.Active, H2g2WorkflowState.Gate)

internal fun clasp(upstream: H2g2WorkflowState, downstream: H2g2WorkflowState): Clasp {
    val outcome = when {
        downstream == H2g2WorkflowState.Blocked || upstream == H2g2WorkflowState.Failed -> ClaspOutcome.Failed
        upstream == H2g2WorkflowState.Complete -> ClaspOutcome.Succeeded
        else -> ClaspOutcome.None
    }
    val flow = when {
        downstream == H2g2WorkflowState.Blocked -> ClaspFlow.ToClasp
        // The downstream waits while the upstream works, and its result crosses as the downstream picks up.
        upstream in Working && downstream !in setOf(H2g2WorkflowState.Complete, H2g2WorkflowState.Failed) -> ClaspFlow.Through
        upstream in setOf(H2g2WorkflowState.Complete, H2g2WorkflowState.Failed) &&
            downstream in Working + H2g2WorkflowState.Ready -> ClaspFlow.Through
        else -> ClaspFlow.Still
    }
    return Clasp(outcome, flow)
}

/**
 * Where the pulse is on each side at clock [t] (0..1): first the upstream tendril, base to tip,
 * then the downstream one, tip to base. Positions run 0 (base) to 1 (tip); null when the pulse is
 * on the other side or nothing runs.
 */
internal fun claspPulse(flow: ClaspFlow, t: Float): Pair<Float?, Float?> = when (flow) {
    ClaspFlow.Through -> (2f * t).let { p -> if (p < 1f) p to null else null to (2f - p) }
    ClaspFlow.ToClasp -> t to null
    ClaspFlow.Still -> null to null
}
