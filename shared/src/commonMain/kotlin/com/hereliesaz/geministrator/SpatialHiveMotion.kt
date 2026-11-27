package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** One local oscillator clock; its wrapping boundary has no discontinuity in the pose. */
internal const val SPATIAL_MOTION_PERIOD = 64f

/** Presentation time is not workflow time. Reduced motion keeps the resting geometry. */
internal fun advanceSpatialMotion(phase: Float, seconds: Float, reduced: Boolean): Float {
    if (reduced) return 0f
    if (!seconds.isFinite() || seconds <= 0f) return phase
    return (phase + seconds * (2f * PI.toFloat() / SPATIAL_MOTION_PERIOD)) % (2f * PI.toFloat())
}

/**
 * Continuous displacement field. Each location has different local amplitudes and phases, with
 * several independent axes and frequencies. The nucleus center is pinned. Coincident membrane,
 * hair roots, cord endpoints and fused branch samples remain coincident. Nothing is persisted
 * or interpreted as agent work. The GLSL equivalent is in the same file for GPU evaluation.
 */
internal fun spatialMotionPoint(p: SpatialPoint, phase: Float): SpatialPoint {
    if (phase == 0f || !phase.isFinite()) return p
    val a = p.x*1.73f + p.y*.91f - p.z*.67f
    val b = p.x*(-.83f) + p.y*1.61f + p.z*1.37f
    val c = p.x*.59f - p.y*1.29f + p.z*2.11f
    val envelope = (p.length()*.65f).coerceIn(0f,1f)
    val x = (.065f+.033f*cos(b))*(sin(a+phase*5f)-sin(a)) +
        .022f*(sin(c+phase*11f)-sin(c))
    val y = (.055f+.025f*sin(c))*(sin(b+phase*3f)-sin(b)) +
        .017f*(sin(a+phase*13f)-sin(a))
    val z = (.071f+.030f*cos(a))*(sin(c+phase*7f)-sin(c)) +
        .019f*(sin(b+phase*17f)-sin(b))
    // Small higher-spatial-frequency ripples add flexible local movement without detaching roots.
    val fine = p.x*8.2f+p.y*6.1f-p.z*4.7f
    val ripple = .008f*(sin(fine+phase*19f)-sin(fine))
    return p + SpatialPoint(x+ripple,y-ripple*.6f,z+ripple*.4f)*envelope
}

/** Match [spatialMotionPoint] exactly for rendering, selection and camera focus. */
internal const val SPATIAL_MOTION_GLSL = """
vec3 deformSpatial(vec3 p,float phase){
    if(phase==0.)return p;
    float a=p.x*1.73+p.y*.91-p.z*.67;
    float b=p.x*(-.83)+p.y*1.61+p.z*1.37;
    float c=p.x*.59-p.y*1.29+p.z*2.11;
    float envelope=clamp(length(p)*.65,0.,1.);
    float x=(.065+.033*cos(b))*(sin(a+phase*5.)-sin(a))+.022*(sin(c+phase*11.)-sin(c));
    float y=(.055+.025*sin(c))*(sin(b+phase*3.)-sin(b))+.017*(sin(a+phase*13.)-sin(a));
    float z=(.071+.030*cos(a))*(sin(c+phase*7.)-sin(c))+.019*(sin(b+phase*17.)-sin(b));
    float fine=p.x*8.2+p.y*6.1-p.z*4.7;
    float ripple=.008*(sin(fine+phase*19.)-sin(fine));
    return p+vec3(x+ripple,y-ripple*.6,z+ripple*.4)*envelope;
}
"""
