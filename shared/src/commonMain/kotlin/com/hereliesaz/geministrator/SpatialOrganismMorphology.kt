package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Visual anatomy recovered from the orbital prototype, not a breed, role or genetic simulation. */
internal enum class SpatialMorphologyFamily { Compact, Elongated, Knotted, Canopy, Swept, Lobed, Slender }

/** Seed-stable proportions and organ counts, independent of camera and execution state. */
internal data class SpatialMorphology(
    val family: SpatialMorphologyFamily, val axes: SpatialPoint, val lobes: Int,
    val relief: Float, val eyes: Int, val eyeSize: Float, val stalks: Int,
    val roots: Int, val hair: Float, val crown: Float,
)

private val families = listOf(
    SpatialMorphology(SpatialMorphologyFamily.Compact,SpatialPoint(1.05f,.93f,.94f),3,.15f,4,.23f,0,7,.85f,.48f),
    SpatialMorphology(SpatialMorphologyFamily.Elongated,SpatialPoint(.70f,1.56f,.79f),2,.12f,3,.20f,1,4,.90f,.62f),
    SpatialMorphology(SpatialMorphologyFamily.Knotted,SpatialPoint(1.09f,1.10f,.98f),5,.25f,6,.19f,0,6,1.16f,.55f),
    SpatialMorphology(SpatialMorphologyFamily.Canopy,SpatialPoint(1.65f,.43f,1.22f),4,.19f,2,.17f,1,5,1.13f,.20f),
    SpatialMorphology(SpatialMorphologyFamily.Swept,SpatialPoint(1.06f,.95f,1.14f),3,.18f,4,.30f,0,5,1.40f,.78f),
    SpatialMorphology(SpatialMorphologyFamily.Lobed,SpatialPoint(1.31f,.81f,1.02f),6,.29f,5,.21f,2,6,.77f,.42f),
    SpatialMorphology(SpatialMorphologyFamily.Slender,SpatialPoint(.67f,1.22f,.69f),2,.13f,2,.23f,2,3,.63f,.60f),
)
private val sequence = intArrayOf(2,0,1,3,5,6,2,4,0,4,5,3,1,0,6,1)

/** Preserve the recovered prototype's family sequence for positive and negative seeds. */
internal fun spatialOrganismMorphology(seed: Int): SpatialMorphology =
    families[sequence[((seed % sequence.size) + sequence.size) % sequence.size]]

/** Broad attachments and an irregular narrow neck, matching the prototype's neurite profile. */
internal fun spatialNeuriteProfile(t: Float, phase: Float): Float {
    val u=t.coerceIn(0f,1f)
    val a=.14f+.045f*sin(phase)
    val b=.16f+.055f*cos(phase+1.3f)
    val left=exp(-(u/a).pow(1.7f))
    val right=exp(-((1f-u)/b).pow(1.7f))
    val neck=.19f+.10f*(.5f+.5f*sin(phase*1.7f))
    return (neck+(1f-neck)*maxOf(left,right))*(1f+.18f*sin(PI.toFloat()*u)*sin(phase+u*11f))
}

/** Nonplanar asymmetric eyelid opening: radial extent and axial height. */
internal fun spatialEyelidProfile(angle: Float, phase: Float): Pair<Float,Float> =
    (.86f+.045f*sin(angle+phase)+.026f*cos(3f*angle-phase)) to
        (.43f+.085f*sin(angle-phase)+.035f*sin(2f*angle+phase))
