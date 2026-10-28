package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition

private val TissueCore = Color(0xFF2A231E)
private val TissueEdge = Color(0xFF141110)

/** The tissue ground: dark, lit from the centre, with each provider's blood supply (Vasculature.kt). */
internal fun DrawScope.drawTissueGround(
    tissue: TissueScene,
    zoom: Float = 1f,
    creatureRadius: Float = 0f,
    phase: Float = 0f,
    /** Whether a creature is working now: its provider's blood flows. */
    isFlowing: (String) -> Boolean = { false },
    creatureCentre: (String) -> Offset? = { null },
) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(TissueCore, TissueEdge),
            center = Offset(size.width / 2f, size.height * 0.45f),
            radius = maxOf(size.width, size.height) * 0.75f,
        ),
    )
    // Each provider's vascular tree, from its edge of the field to the creatures it feeds.
    val minWidth = 0.9.dp.toPx() * zoom
    tissue.capillaries.forEachIndexed { index, capillary ->
        val fed = tissue.providerOf.filterValues { it == capillary.providerId }.keys.sorted()
        val centres = fed.mapNotNull(creatureCentre)
        if (centres.isEmpty()) return@forEachIndexed
        val towards = Offset(centres.map { it.x }.average().toFloat(), centres.map { it.y }.average().toFloat())
        val seed = capillary.providerId.hashCode()
        val entry = vesselEntry(index, towards, size.width, size.height)
        val vessels = VesselCache.get(capillary.providerId, entry, centres, creatureRadius, size, zoom) {
            grownVasculature(
                entry = entry,
                targets = centres.map { it to creatureRadius },
                bounds = size,
                step = 7.dp.toPx() * zoom,
                minWidth = minWidth,
                maxWidth = 4.dp.toPx() * zoom,
                seed = seed,
            )
        }
        drawVasculature(vessels, flowing = fed.any(isFlowing), phase = phase, cell = 1.1.dp.toPx() * zoom)
    }
}

/**
 * The run as an egg. Every task being worked on feeds it through a tail from its creature; it swells
 * with completed tasks, glows yellow while gestating, and greys when the run fails.
 */
internal fun DrawScope.drawTissueEgg(
    egg: TissueEgg,
    zoom: Float,
    toScreen: (H2g2TerrariumPosition) -> Offset,
) {
    val at = toScreen(egg.position)
    val shell = Azphalt.Yellow
    val t = egg.growth.coerceIn(0f, 1f)
    val rx = (7f + 12f * t).dp.toPx() * zoom
    val ry = (9f + 15f * t).dp.toPx() * zoom
    drawOval(shell, topLeft = Offset(at.x - rx, at.y - ry), size = Size(rx * 2f, ry * 2f))
    val hx = (2f + 3f * t).dp.toPx() * zoom
    val hy = (3f + 4f * t).dp.toPx() * zoom
    drawOval(
        Color.White.copy(alpha = 0.5f),
        topLeft = Offset(at.x - rx * 0.3f - hx, at.y - ry * 0.35f - hy),
        size = Size(hx * 2f, hy * 2f),
    )
}

/**
 * Grown vasculature is costly, so each provider's is kept until its creatures, the field or the
 * zoom change (rounded, so a still field never regrows).
 */
private object VesselCache {
    private val cache = LinkedHashMap<String, List<Vessel>>()

    fun get(
        provider: String,
        entry: Offset,
        centres: List<Offset>,
        radius: Float,
        field: Size,
        zoom: Float,
        grow: () -> List<Vessel>,
    ): List<Vessel> {
        fun r(v: Float) = (v / 4f).toInt()
        val key = buildString {
            append(provider).append('|').append(r(entry.x)).append(',').append(r(entry.y))
            centres.forEach { append('|').append(r(it.x)).append(',').append(r(it.y)) }
            append('|').append(r(radius)).append('|').append(r(field.width)).append(',').append(r(field.height))
            append('|').append((zoom * 20).toInt())
        }
        cache[key]?.let { return it }
        val grown = grow()
        cache[key] = grown
        while (cache.size > 24) cache.remove(cache.keys.first())
        return grown
    }
}
