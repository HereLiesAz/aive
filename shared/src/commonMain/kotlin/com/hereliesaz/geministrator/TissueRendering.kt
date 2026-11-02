package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
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
    /** Writes each provider's name along its capillary; null draws the vessels alone. */
    textMeasurer: TextMeasurer? = null,
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
    val minWidth = 1.6.dp.toPx() * zoom
    val courses = tissue.capillaryCourses(size.width, size.height, creatureCentre)
    courses.forEach { course ->
        val capillary = course.capillary
        val fed = course.fed
        val centres = course.centres
        val towards = course.towards
        val seed = capillary.providerId.hashCode()
        val entry = course.entry
        val vessels = VesselCache.get(capillary.providerId, entry, centres, creatureRadius, size, zoom) {
            grownVasculature(
                entry = entry,
                targets = centres.map { it to creatureRadius * 1.5f },
                bounds = size,
                step = 13.dp.toPx() * zoom,
                minWidth = minWidth,
                maxWidth = 8.dp.toPx() * zoom,
                seed = seed,
            )
        }
        drawVasculature(vessels, flowing = fed.any(isFlowing), phase = phase, cell = 2.dp.toPx() * zoom)
    }
    // Names go on after every vessel, so no provider's tree covers another's name.
    if (textMeasurer != null) courses.forEach { course -> drawCapillaryName(course, textMeasurer) }
}

/** One provider's capillary as laid on the field: where it enters and the creatures it reaches. */
internal data class CapillaryCourse(
    val capillary: TissueCapillary,
    /** The creatures it feeds, in id order. */
    val fed: List<String>,
    /** Where those of them on the field are, on screen. */
    val centres: List<Offset>,
    val entry: Offset,
    /** The centre of the creatures it feeds. */
    val towards: Offset,
) {
    /** Where its name sits: part way from the edge toward its creatures. */
    val labelAt: Offset get() = entry + (towards - entry) * CAPILLARY_LABEL_AT
}

/**
 * Every capillary that reaches a creature on the field, in the order they are drawn. Shared by the
 * vessels, their names and the semantics overlay, so all three agree on where each one runs.
 */
internal fun TissueScene.capillaryCourses(
    width: Float,
    height: Float,
    creatureCentre: (String) -> Offset?,
): List<CapillaryCourse> = capillaries.mapIndexedNotNull { index, capillary ->
    val fed = fedBy(capillary.providerId)
    val centres = fed.mapNotNull(creatureCentre)
    if (centres.isEmpty()) return@mapIndexedNotNull null
    val towards = Offset(centres.map { it.x }.average().toFloat(), centres.map { it.y }.average().toFloat())
    CapillaryCourse(
        capillary = capillary,
        fed = fed,
        centres = centres,
        entry = vesselEntry(index, towards, width, height),
        towards = towards,
    )
}

/** The provider's name, written along its capillary's course on a dark band so it reads on the tissue. */
private fun DrawScope.drawCapillaryName(course: CapillaryCourse, textMeasurer: TextMeasurer) {
    val run = course.towards - course.entry
    if (run.getDistance() < 1f) return
    val measured = textMeasurer.measure(
        text = capillaryName(course.capillary.providerId).uppercase(),
        style = AzphaltType.endCap.copy(color = CapillaryNameInk),
        maxLines = 1,
    )
    val at = course.labelAt
    val degrees = radiansToDegrees(atan2(run.y, run.x))
    val padX = 6.dp.toPx()
    val padY = 2.dp.toPx()
    val w = measured.size.width.toFloat()
    val h = measured.size.height.toFloat()
    rotate(capillaryLabelAngle(degrees), pivot = at) {
        drawRoundRect(
            color = TissueEdge.copy(alpha = 0.72f),
            topLeft = Offset(at.x - w / 2f - padX, at.y - h / 2f - padY),
            size = Size(w + padX * 2f, h + padY * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h),
        )
        drawText(measured, topLeft = Offset(at.x - w / 2f, at.y - h / 2f))
    }
}

private fun radiansToDegrees(radians: Float): Float = radians * (180f / kotlin.math.PI.toFloat())

/** The vessels' pale sheen, so a capillary's name reads as part of it on the dark tissue. */
private val CapillaryNameInk = Color(0xFFF2C9C2)

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
