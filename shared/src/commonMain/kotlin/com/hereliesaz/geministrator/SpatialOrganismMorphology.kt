package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt
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

/** Nonplanar asymmetric eyelid opening: radial extent and axial height. */
internal fun spatialEyelidProfile(angle: Float, phase: Float): Pair<Float,Float> =
    (.86f+.045f*sin(angle+phase)+.026f*cos(3f*angle-phase)) to
        (.43f+.085f*sin(angle-phase)+.035f*sin(2f*angle+phase))

/**
 * A direction-space sculpt shared by soma vertices and their anatomical attachments.
 * Root cones pull the membrane out into flared shoulders; ocular cones form depressions
 * under embedded eyes. Direction-space relief has no UV seam or pinched pole. All
 * coordinates are local to the body, with no authority over its persisted world site.
 */
internal fun spatialSomaOffset(
    direction: SpatialPoint, shape: SpatialPoint, lobes: Int, relief: Float, phase: Float,
    roots: List<SpatialPoint>, sockets: List<SpatialPoint>,
): SpatialPoint {
    val n=direction.unit()
    if(n==SpatialPoint.Origin) return n
    val theta=atan2(n.z,n.x)
    val equator=sqrt((1f-n.y*n.y).coerceAtLeast(0f))
    val broad=sin(theta*lobes+phase)*equator*(.62f-.42f*n.y*n.y)
    val folds=.065f*sin(n.x*19f+n.y*11f+phase)*cos(n.z*13f-n.y*8f)
    val pits=.045f*sin(n.x*31f-n.z*23f+phase)*sin(n.y*27f+n.z*17f)
    fun influence(axis: SpatialPoint, threshold: Float): Float {
        val alignment=n.x*axis.x+n.y*axis.y+n.z*axis.z
        val t=((alignment-threshold)/(1f-threshold)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
    val shoulder=roots.maxOfOrNull { influence(it,.77f) } ?: 0f
    val recess=sockets.maxOfOrNull { influence(it,.88f) } ?: 0f
    val contour=(1f+relief*broad+folds+pits+.27f*shoulder-.19f*recess).coerceIn(.65f,1.48f)
    val skew=.09f*n.y*n.y*sin(phase)
    return SpatialPoint((n.x*contour+skew)*shape.x,n.y*contour*shape.y,
        (n.z*contour+.055f*n.y*cos(phase))*shape.z)
}

/** Broad living root, with a narrow neck and local swellings rather than a straight cable. */
internal fun spatialRootProfile(t: Float, phase: Float): Float {
    val u=t.coerceIn(0f,1f)
    val remaining=1f-u
    return .025f+.975f*remaining.pow(1.65f)+
        .09f*sin(PI.toFloat()*u).pow(2)*(.5f+.5f*sin(phase+u*17f))
}
