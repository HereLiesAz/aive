package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

/*
 * The colour of an egg (docs/Node-Creatures.md, Birth). It starts as a shell tinted from its own task,
 * the same every time, and takes on the colours of the creatures that gestate it as each one works
 * (Planning and on), in proportion to how far its share of the gestation has come. Nothing here
 * rolls dice: the seed is the egg's task, the pull is the run's recorded progress.
 */

/** The shell an egg begins with: a pale tint whose hue is set by [seed] alone. */
internal fun eggBaseTint(seed: String): Color {
    val hue = abs(CreatureRigMapping.stableHash(seed) % 360).toFloat()
    return Color.hsv(hue, saturation = 0.32f, value = 0.9f)
}

/** How hard a gestator pulls the egg toward its own colour, against the shell's own weight of 1. */
private const val CONTRIBUTION_PULL = 3f

/**
 * The egg's colour: [base] blended with each gestator's colour, in OKLab so the blend is even to the
 * eye. [contributions] pairs a gestator's colour with how much it has contributed (0..1); one that
 * has not begun (0) leaves the colour as it was.
 */
internal fun blendEggTint(base: Color, contributions: List<Pair<Color, Float>>): Color {
    var weightSum = 1f
    val (l0, a0, b0) = toOkLab(base)
    var l = l0
    var a = a0
    var b = b0
    for ((colour, share) in contributions) {
        val weight = CONTRIBUTION_PULL * share.coerceIn(0f, 1f)
        if (weight <= 0f) continue
        val (cl, ca, cb) = toOkLab(colour)
        l += cl * weight
        a += ca * weight
        b += cb * weight
        weightSum += weight
    }
    return fromOkLab(l / weightSum, a / weightSum, b / weightSum)
}

/** The colour of a creature's body: the average of the opaque pixels of its rig's soma, or null if it has none. */
internal fun somaColour(loaded: LoadedCreatureRig): Color? {
    val cached = somaColours.getOrPut(loaded.slug) { sampleSoma(loaded) }
    return cached.takeUnless { it == Color.Unspecified }
}

// Per slug, once: a rig's painting never changes. Unspecified remembers "no soma to read".
private val somaColours = HashMap<String, Color>()

private fun sampleSoma(loaded: LoadedCreatureRig): Color {
    val rect = loaded.rig.parts.firstOrNull { it.id == "soma" }?.rect ?: return Color.Unspecified
    val atlas = loaded.atlas
    val x = rect.x.coerceIn(0, atlas.width)
    val y = rect.y.coerceIn(0, atlas.height)
    val w = minOf(rect.w, atlas.width - x)
    val h = minOf(rect.h, atlas.height - y)
    if (w <= 0 || h <= 0) return Color.Unspecified
    val pixels = IntArray(w * h)
    atlas.readPixels(pixels, startX = x, startY = y, width = w, height = h)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (pixel in pixels) {
        if ((pixel ushr 24) < OPAQUE_ENOUGH) continue
        r += (pixel shr 16) and 0xFF
        g += (pixel shr 8) and 0xFF
        b += pixel and 0xFF
        count++
    }
    if (count == 0L) return Color.Unspecified
    return Color(red = r / (255f * count), green = g / (255f * count), blue = b / (255f * count))
}

private const val OPAQUE_ENOUGH = 128

private data class OkLab(val l: Float, val a: Float, val b: Float)

private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun fromLinear(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

private fun cbrt(x: Float): Float = x.coerceAtLeast(0f).pow(1f / 3f)

private fun toOkLab(colour: Color): OkLab {
    val r = toLinear(colour.red)
    val g = toLinear(colour.green)
    val bl = toLinear(colour.blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * bl)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * bl)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * bl)
    return OkLab(
        l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun fromOkLab(l: Float, a: Float, b: Float): Color {
    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b
    val lc = l_ * l_ * l_
    val mc = m_ * m_ * m_
    val sc = s_ * s_ * s_
    return Color(
        red = fromLinear(4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc),
        green = fromLinear(-1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc),
        blue = fromLinear(-0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc),
    )
}
