package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Camera context changes within the same world, not a destination or a new application screen. */
internal enum class SpatialCameraMode { Colony, Memory }

/** Y is north. Orbit center and gaze are deliberately independent. */
internal data class SpatialCameraPose(
    val azimuth: Float = 0f,
    val elevation: Float = 7f,
    val distance: Float = 9.2f,
    val center: SpatialPoint = SpatialPoint.Origin,
    val gaze: SpatialPoint = SpatialPoint.Origin,
) {
    /** World-space eye, using angles in degrees. */
    fun eye(): SpatialPoint {
        val a = azimuth * PI.toFloat() / 180f
        val e = elevation * PI.toFloat() / 180f
        return center + SpatialPoint(sin(a) * cos(e), sin(e), cos(a) * cos(e)) * distance
    }
}

/**
 * Presentation-only camera controller. Direct gestures cancel automatic reframing; explicit focus
 * and context changes can resume it. The colony orbit stays centered on the nucleus by default
 * even while the gaze follows a creature. Memory looks down at the same world.
 */
internal data class SpatialCameraState(
    val pose: SpatialCameraPose = SpatialCameraPose(),
    val target: SpatialCameraPose = pose,
    val mode: SpatialCameraMode = SpatialCameraMode.Colony,
    val colonyReturn: SpatialCameraPose? = null,
    val manual: Boolean = false,
    val selected: Boolean = false,
) {
    /** A keeper's selection takes precedence over ambient interaction focus. */
    fun focus(point: SpatialPoint, explicit: Boolean): SpatialCameraState {
        if (!point.finite() || (!explicit && (manual || selected || mode == SpatialCameraMode.Memory))) return this
        return copy(target = target.copy(gaze = point), manual = false, selected = explicit)
    }

    /** Release explicit focus without changing camera position. */
    fun clearFocus(): SpatialCameraState = copy(target = target.copy(gaze = target.center), selected = false)

    /** Swing above the north pole, widening to fit actual spore positions. */
    fun memory(spores: List<SpatialPoint>): SpatialCameraState {
        if (mode == SpatialCameraMode.Memory) return this
        val finite = spores.filter(SpatialPoint::finite)
        val bed = if (finite.isEmpty()) SpatialPoint(0f, -2.3f, 0f)
            else finite.fold(SpatialPoint.Origin) { sum, p -> sum + p } * (1f / finite.size)
        val extent = finite.maxOfOrNull { (it - bed).length() } ?: 0f
        return copy(
            mode = SpatialCameraMode.Memory,
            colonyReturn = pose,
            target = SpatialCameraPose(pose.azimuth, 87f, maxOf(13f, extent * 2.8f + 3f), SpatialPoint.Origin, bed),
            manual = false, selected = false,
        )
    }

    /** Restore the previously viewed colony composition. */
    fun colony(): SpatialCameraState {
        if (mode == SpatialCameraMode.Colony) return this
        return copy(mode = SpatialCameraMode.Colony, target = colonyReturn ?: SpatialCameraPose(),
            colonyReturn = null, manual = false, selected = false)
    }

    /** Direct input is applied to the current pose, not to an unfinished transition target. */
    fun orbit(deltaAzimuth: Float, deltaElevation: Float): SpatialCameraState {
        if (!deltaAzimuth.isFinite() || !deltaElevation.isFinite()) return this
        return interrupt(pose.copy(azimuth = wrap(pose.azimuth + deltaAzimuth),
            elevation = (pose.elevation + deltaElevation).coerceIn(-87f, 87f)))
    }

    /** Pan translates eye and gaze by the same world-space displacement. */
    fun pan(delta: SpatialPoint): SpatialCameraState =
        if (!delta.finite()) this else interrupt(pose.copy(center = pose.center + delta, gaze = pose.gaze + delta))

    /** A multiplicative zoom keeps distance positive and bounds the closest approach. */
    fun zoom(factor: Float): SpatialCameraState =
        if (!factor.isFinite() || factor <= 0f) this else interrupt(pose.copy(distance = (pose.distance * factor).coerceIn(2.6f, 80f)))

    private fun interrupt(next: SpatialCameraPose) = copy(pose = next, target = next, manual = true, selected = false)

    /** Frame-rate-independent easing. Reduced motion applies the target without animation. */
    fun advance(seconds: Float, reducedMotion: Boolean): SpatialCameraState {
        if (reducedMotion) return copy(pose = target)
        if (!seconds.isFinite() || seconds <= 0f) return this
        val t = (1f - exp(-seconds * 5f)).coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = if (abs(a - b) < .0001f) b else a + (b - a) * t
        fun mixPoint(a: SpatialPoint, b: SpatialPoint) = SpatialPoint(mix(a.x, b.x), mix(a.y, b.y), mix(a.z, b.z))
        val angle = wrap(pose.azimuth + wrap(target.azimuth - pose.azimuth) * t)
        return copy(pose = SpatialCameraPose(angle, mix(pose.elevation, target.elevation),
            mix(pose.distance, target.distance), mixPoint(pose.center, target.center), mixPoint(pose.gaze, target.gaze)))
    }

    private fun wrap(degrees: Float): Float = ((degrees % 360f + 540f) % 360f) - 180f
}
