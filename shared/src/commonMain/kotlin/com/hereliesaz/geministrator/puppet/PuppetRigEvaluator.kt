package com.hereliesaz.geministrator.puppet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 2D affine transform `[a c tx; b d ty; 0 0 1]` (column vectors), artboard px, y-down.
 * Positive rotation is clockwise on screen, matching Compose `rotate`.
 */
data class PuppetAffine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    /** `this * other`: applies [other] first, then this. */
    operator fun times(o: PuppetAffine): PuppetAffine = PuppetAffine(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty,
    )

    fun apply(x: Float, y: Float): PuppetPoint = PuppetPoint(a * x + c * y + tx, b * x + d * y + ty)

    companion object {
        val Identity = PuppetAffine()

        /** `T(x, y) · R(degrees) · S(sx, sy)`. */
        fun trs(x: Float, y: Float, degrees: Float, sx: Float, sy: Float): PuppetAffine {
            val r = degrees * PI.toFloat() / 180f
            val cs = cos(r)
            val sn = sin(r)
            return PuppetAffine(a = cs * sx, b = sn * sx, c = -sn * sy, d = cs * sy, tx = x, ty = y)
        }
    }
}

/**
 * Evaluated part: [transform] maps part-local px (origin at the pivot) to artboard px. A limb's
 * [transform] keeps its rest direction; [bend] (degrees) is how far it curves base to tip.
 */
data class PuppetPartPose(
    val part: PuppetPart,
    val transform: PuppetAffine,
    val alpha: Float,
    val bend: Float = 0f,
) {
    /** Top-left of the drawn box in part-local px. */
    val boxLeft: Float get() = -part.pivot.x * part.drawnWidth
    val boxTop: Float get() = -part.pivot.y * part.drawnHeight
    val pivotWorld: PuppetPoint get() = transform.apply(0f, 0f)
}

data class PuppetPose(
    /** Every part, sorted in draw order (z ascending, ties by declaration order). */
    val parts: List<PuppetPartPose>,
    /** Attachment id to artboard position. */
    val attachments: Map<String, PuppetPoint>,
    /** The state whose motion was actually used (after fallback), or null for rest pose. */
    val resolvedState: String?,
) {
    private val byId by lazy { parts.associateBy { it.part.id } }
    fun part(id: String): PuppetPartPose? = byId[id]
}

/** Pure evaluator: (rig, state, phase) to world transforms. No Compose dependency. */
object PuppetRigEvaluator {

    fun evaluate(rig: PuppetRig, state: String, phase: Float): PuppetPose {
        val resolved = when {
            rig.states.containsKey(state) -> state
            rig.fallbackState != null && rig.states.containsKey(rig.fallbackState) -> rig.fallbackState
            else -> null
        }
        val motion = resolved?.let { rig.states[it] }
        val cycle = phase - floor(phase)
        val byId = rig.parts.associateBy { it.id }
        val attachmentsById = rig.attachments.associateBy { it.id }
        val world = HashMap<String, Pair<PuppetAffine, Float>>()
        val bends = HashMap<String, Float>()
        val bent = FloatArray(3)

        fun resolve(part: PuppetPart): Pair<PuppetAffine, Float> {
            world[part.id]?.let { return it }
            val parentPart0 = part.parent?.let(byId::get)
            val parent = parentPart0?.let(::resolve) ?: (PuppetAffine.Identity to 1f)
            val m = motion?.parts?.get(part.id)
            val flexibility = 1f - part.rigidity
            fun ch(name: String): Float {
                val identity = PuppetChannel.identity(name)
                return identity + (channelValue(m, name, cycle) - identity) * flexibility
            }
            val parentPart = part.parent?.let(byId::get)
            val socket = part.connectTo?.let(attachmentsById::get)
            val anchorX = if (parentPart != null && socket?.part == parentPart.id) {
                (socket.x - parentPart.pivot.x) * parentPart.drawnWidth
            } else 0f
            val anchorY = if (parentPart != null && socket?.part == parentPart.id) {
                (socket.y - parentPart.pivot.y) * parentPart.drawnHeight
            } else 0f
            val rest = part.rest
            // A limb never rotates: its rotation channel is its bend.
            val turn = ch(PuppetChannel.Rotation)
            if (part.isLimb) bends[part.id] = LimbBend.bendOf(turn)
            var x = anchorX + rest.x + ch(PuppetChannel.X)
            var y = anchorY + rest.y + ch(PuppetChannel.Y)
            var follow = 0f
            // On a bent limb, a child rides the curve: placed on it, turned with it.
            if (parentPart0 != null && parentPart0.isLimb) {
                LimbBend.placeLocal(parentPart0, x, y, bends[parentPart0.id] ?: 0f, 1f, 1, 0f, bent)
                x = bent[0]
                y = bent[1]
                follow = bent[2] * 180f / PI.toFloat()
            }
            val local = PuppetAffine.trs(
                x = x,
                y = y,
                degrees = rest.rotation + follow + if (part.isLimb) 0f else turn,
                sx = rest.scaleX * ch(PuppetChannel.ScaleX),
                sy = rest.scaleY * ch(PuppetChannel.ScaleY),
            )
            val alpha = (parent.second * rest.alpha * channelValue(m, PuppetChannel.Alpha, cycle)).coerceIn(0f, 1f)
            val result = (parent.first * local) to alpha
            world[part.id] = result
            return result
        }

        val poses = rig.parts.withIndex()
            .sortedWith(compareBy({ it.value.z }, { it.index }))
            .map { (_, part) -> resolve(part).let { (t, a) -> PuppetPartPose(part, t, a, bends[part.id] ?: 0f) } }
        val poseById = poses.associateBy { it.part.id }
        val attachments = rig.attachments.associate { att ->
            val pose = poseById.getValue(att.part)
            val p = pose.part
            val lx = (att.x - p.pivot.x) * p.drawnWidth
            val ly = (att.y - p.pivot.y) * p.drawnHeight
            if (p.isLimb) {
                LimbBend.placeLocal(p, lx, ly, pose.bend, 1f, 1, 0f, bent)
                att.id to pose.transform.apply(bent[0], bent[1])
            } else {
                att.id to pose.transform.apply(lx, ly)
            }
        }
        return PuppetPose(poses, attachments, resolved)
    }

    /** Keyframed value (or channel identity) plus all sine contributions. */
    fun channelValue(motion: PuppetPartMotion?, channel: String, cycle: Float): Float {
        if (motion == null) return PuppetChannel.identity(channel)
        val keyed = motion.keys[channel]?.takeIf { it.isNotEmpty() }?.let { sampleKeys(it, cycle) }
            ?: PuppetChannel.identity(channel)
        val wave = motion.sine[channel]?.let { s ->
            s.amplitude * sin(2f * PI.toFloat() * (s.frequency * cycle + s.phase))
        } ?: 0f
        return keyed + wave
    }

    /** Samples looping keys at [cycle] in [0,1). The last key wraps to the first at t+1. */
    fun sampleKeys(keys: List<PuppetKeyframe>, cycle: Float): Float {
        val sorted = keys.sortedBy { it.t }
        if (sorted.size == 1) return sorted[0].v
        var from = sorted.last()
        var to = sorted.first()
        var fromT = from.t - 1f
        var toT = to.t
        for (i in sorted.indices) {
            val next = sorted[(i + 1) % sorted.size]
            val nextT = if (i + 1 < sorted.size) next.t else next.t + 1f
            if (cycle >= sorted[i].t && cycle < nextT) {
                from = sorted[i]; to = next; fromT = sorted[i].t; toT = nextT
                break
            }
        }
        // cycle before the first key: segment is (last - 1) -> first, already initialised.
        val span = toT - fromT
        val u = if (span <= 0f) 0f else ((cycle - fromT) / span).coerceIn(0f, 1f)
        return from.v + (to.v - from.v) * ease(from.ease, u)
    }

    fun ease(ease: PuppetEase, u: Float): Float = when (ease) {
        PuppetEase.Linear -> u
        PuppetEase.Step -> 0f
        PuppetEase.EaseIn -> u * u
        PuppetEase.EaseOut -> 1f - (1f - u) * (1f - u)
        PuppetEase.EaseInOut -> if (u < 0.5f) 2f * u * u else 1f - 2f * (1f - u) * (1f - u)
    }
}
