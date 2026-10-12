package com.hereliesaz.conveyance.h2g2

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * h2g2's eight-step type scale. One typeface drives every step -- weight, size, line-height, and
 * letter-tracking differentiate them, not the font itself. Bigger steps run Black weight with
 * tight or negative tracking; small/label-like steps run ExtraBold with wide positive tracking
 * for a stamped, all-caps look (the call site is responsible for the case -- [TextStyle] alone
 * can't force it). [body] is the one lowercase-prose step.
 *
 * [family] defaults to [FontFamily.Default] -- h2g2's origin (HG2Gui) uses Jost specifically, but
 * shipping that typeface's font files is a per-host asset decision, not something this library
 * bundles. A host that wants the exact original look supplies its own Jost [FontFamily] here.
 */
data class H2g2Type(
    val hero: TextStyle,
    val section: TextStyle,
    val lead: TextStyle,
    val body: TextStyle,
    val capsule: TextStyle,
    val eyebrow: TextStyle,
    val endCap: TextStyle,
    val micro: TextStyle,
)

fun h2g2Type(family: FontFamily = FontFamily.Default): H2g2Type = H2g2Type(
    hero = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Black,
        fontSize = 58.sp, lineHeight = 49.sp, letterSpacing = (-0.03).em,
    ),
    section = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Black,
        fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em,
    ),
    lead = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp, lineHeight = 32.sp,
    ),
    body = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 25.5.sp,
    ),
    capsule = TextStyle(
        fontFamily = family, fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp, lineHeight = 15.sp, letterSpacing = 0.09.em,
    ),
    eyebrow = TextStyle(
        fontFamily = family, fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp, lineHeight = 11.sp, letterSpacing = 0.28.em,
    ),
    endCap = TextStyle(
        fontFamily = family, fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp, lineHeight = 10.sp, letterSpacing = 0.10.em,
    ),
    micro = TextStyle(
        fontFamily = family, fontWeight = FontWeight.ExtraBold,
        fontSize = 9.sp, lineHeight = 9.sp, letterSpacing = 0.18.em,
    ),
)

/** Looks up a step by the composable manifest's `scale` string (azphalt `spec/composable.md`). */
fun H2g2Type.step(name: String): TextStyle = when (name) {
    "hero" -> hero
    "section" -> section
    "lead" -> lead
    "body" -> body
    "capsule" -> capsule
    "eyebrow" -> eyebrow
    "endCap" -> endCap
    "micro" -> micro
    else -> body
}
