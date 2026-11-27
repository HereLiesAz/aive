package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Visual anatomy recovered from the original organismMorphology.ts prototype. The sequence
 * and proportions are stable across platforms, and say nothing about a creature's capabilities.
 */
internal enum class SpatialMorphology(
    val shape: SpatialPoint, val lobes: Int, val relief: Float,
    val eyes: Int, val eye: Float, val stalks: Int, val roots: Int,
    val hair: Float, val crown: Float,
) {
    Compact(SpatialPoint(1.05f,.93f,.94f),3,.15f,4,.23f,0,7,.85f,.48f),
    Elongated(SpatialPoint(.70f,1.56f,.79f),2,.12f,3,.20f,1,4,.90f,.62f),
    Knotted(SpatialPoint(1.09f,1.10f,.98f),5,.25f,6,.19f,0,6,1.16f,.55f),
    Canopy(SpatialPoint(1.65f,.43f,1.22f),4,.19f,2,.17f,1,5,1.13f,.20f),
    Swept(SpatialPoint(1.06f,.95f,1.14f),3,.18f,4,.30f,0,5,1.40f,.78f),
    Lobed(SpatialPoint(1.31f,.81f,1.02f),6,.29f,5,.21f,2,6,.77f,.42f),
    Slender(SpatialPoint(.67f,1.22f,.69f),2,.13f,2,.23f,2,3,.63f,.60f),
}

private val morphologySequence = listOf(
    SpatialMorphology.Knotted, SpatialMorphology.Compact, SpatialMorphology.Elongated,
    SpatialMorphology.Canopy, SpatialMorphology.Lobed, SpatialMorphology.Slender,
    SpatialMorphology.Knotted, SpatialMorphology.Swept, SpatialMorphology.Compact,
    SpatialMorphology.Swept, SpatialMorphology.Lobed, SpatialMorphology.Canopy,
    SpatialMorphology.Elongated, SpatialMorphology.Compact, SpatialMorphology.Slender,
    SpatialMorphology.Elongated,
)

/** Negative seeds retain the prototype's mathematical modulo convention. */
internal fun spatialMorphology(seed: Int): SpatialMorphology =
    morphologySequence[((seed % morphologySequence.size) + morphologySequence.size) % morphologySequence.size]

/** Broad rooted ends and an asymmetric, irregular, narrow midsection. */
internal fun spatialNeuriteProfile(fraction: Float, phase: Float): Float {
    val t = fraction.coerceIn(0f,1f)
    val a = .14f + .045f*sin(phase)
    val b = .16f + .055f*cos(phase+1.3f)
    val left = exp(-(t/a).pow(1.7f))
    val right = exp(-((1f-t)/b).pow(1.7f))
    val neck = .19f+.10f*(.5f+.5f*sin(phase*1.7f))
    return (neck+(1f-neck)*maxOf(left,right))*(1f+.18f*sin(PI.toFloat()*t)*sin(phase+t*11f))
}
