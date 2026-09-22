package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hierarchical 2D puppet rig for the approved workflow mascots.
 *
 * The 2D character artwork is canonical. Animation is expressed as bone transforms against the
 * 200x200 design-space artboard; there is no 3D reconstruction step anywhere in this runtime.
 */
internal object MascotPuppetRig {
    const val Root = "root"
    const val Head = "head"
    const val TorsoUpper = "torso.upper"
    const val TorsoMid = "torso.mid"
    const val Pelvis = "pelvis"

    const val LeftLegUpper = "leg.left.upper"
    const val LeftLegLower = "leg.left.lower"
    const val LeftFoot = "leg.left.foot"
    const val RightLegUpper = "leg.right.upper"
    const val RightLegLower = "leg.right.lower"
    const val RightFoot = "leg.right.foot"

    const val Prop = "prop"
    const val LeftEye = "face.eye.left"
    const val RightEye = "face.eye.right"
    const val LeftBrow = "face.brow.left"
    const val RightBrow = "face.brow.right"
    const val Mouth = "face.mouth"

    const val Limb0 = "limb.0"
    const val Limb1 = "limb.1"
    const val Limb2 = "limb.2"

    fun antennaBase(index: Int): String = "antenna.$index.base"
    fun antennaMid(index: Int): String = "antenna.$index.mid"
    fun antennaTip(index: Int): String = "antenna.$index.tip"

    /** Compatibility alias for callers that only need the primary antenna control. */
    fun antenna(index: Int): String = antennaBase(index)

    fun skeleton(antennaCount: Int): MascotSkeleton {
        val bones = buildList {
            add(MascotBone(Root, null, 100f, 100f))
            add(MascotBone(Head, Root, 100f, 83f))
            add(MascotBone(TorsoUpper, Root, 100f, 120f))
            add(MascotBone(TorsoMid, TorsoUpper, 100f, 139f))
            add(MascotBone(Pelvis, TorsoMid, 100f, 155f))

            add(MascotBone(LeftLegUpper, Pelvis, 94f, 154f))
            add(MascotBone(LeftLegLower, LeftLegUpper, 84f, 166f))
            add(MascotBone(LeftFoot, LeftLegLower, 75f, 169f))
            add(MascotBone(RightLegUpper, Pelvis, 106f, 154f))
            add(MascotBone(RightLegLower, RightLegUpper, 116f, 166f))
            add(MascotBone(RightFoot, RightLegLower, 125f, 169f))

            add(MascotBone(Prop, Root, 134f, 104f))
            // Gestation limb: a 3-segment chain hanging off the body, used by automated-process
            // creatures that have no agent identity of their own.
            add(MascotBone(Limb0, Head, 122f, 108f))
            add(MascotBone(Limb1, Limb0, 138f, 128f))
            add(MascotBone(Limb2, Limb1, 152f, 146f))
            add(MascotBone(LeftEye, Head, 88f, 81f))
            add(MascotBone(RightEye, Head, 112f, 81f))
            add(MascotBone(LeftBrow, Head, 88f, 73f))
            add(MascotBone(RightBrow, Head, 112f, 73f))
            add(MascotBone(Mouth, Head, 100f, 98f))

            repeat(antennaCount.coerceAtLeast(1)) { index ->
                add(MascotBone(antennaBase(index), Head, 100f, 83f))
                add(MascotBone(antennaMid(index), antennaBase(index), 100f, 83f))
                add(MascotBone(antennaTip(index), antennaMid(index), 100f, 83f))
            }
        }
        return MascotSkeleton(bones)
    }

    /**
     * Samples the built-in workflow-state motion and optionally layers arbitrary puppet controls
     * over it. [overrides] is the hook used by future keyframe editors, scripted motion and direct
     * manipulation without changing the renderer.
     */
    fun pose(
        role: NodeCreatureRoleKind,
        state: H2g2WorkflowState,
        phase: Float,
        antennaCount: Int,
        overrides: Map<String, MascotBonePose> = emptyMap(),
    ): MascotPuppetPose {
        val cycle = phase - kotlin.math.floor(phase)
        val wave = sin(cycle * 2f * PI.toFloat())
        val fastWave = sin(cycle * 4f * PI.toFloat())
        val blocked = state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed

        val rootY = when {
            blocked -> 2.2f
            state == H2g2WorkflowState.Active -> -3.2f * wave
            state == H2g2WorkflowState.Complete -> -2.0f * abs(wave)
            state == H2g2WorkflowState.Gate -> -1.6f * wave
            else -> -0.8f * wave
        }
        val rootRotation = when {
            blocked -> -3f
            state == H2g2WorkflowState.Active -> wave * 2.2f
            state == H2g2WorkflowState.Complete -> wave * 1.2f
            else -> wave * 0.6f
        }
        val rootScale = when {
            state == H2g2WorkflowState.Complete -> 1f + 0.018f * abs(wave)
            blocked -> 0.975f
            else -> 1f
        }
        val headRotation = when {
            blocked -> -7f
            state == H2g2WorkflowState.Active -> wave * 4.5f
            state == H2g2WorkflowState.Complete -> wave * 2.2f
            else -> wave * 1.2f
        }
        val headY = when {
            blocked -> 3.5f
            state == H2g2WorkflowState.Active -> -abs(wave) * 2.4f
            else -> 0f
        }
        val torsoSway = when {
            blocked -> -4f
            state == H2g2WorkflowState.Active -> wave * 5f
            else -> wave * 1.8f
        }
        val legSwing = when {
            blocked -> 2f
            state == H2g2WorkflowState.Active -> fastWave * 10f
            state == H2g2WorkflowState.Complete -> wave * 5f
            else -> wave * 2.5f
        }
        val kneeFlex = when {
            blocked -> 12f
            state == H2g2WorkflowState.Active -> abs(fastWave) * 14f
            state == H2g2WorkflowState.Complete -> abs(wave) * 7f
            else -> abs(wave) * 3f
        }
        val propRotation = when {
            blocked -> 12f
            state == H2g2WorkflowState.Active -> -wave * 9f
            state == H2g2WorkflowState.Complete -> -8f - abs(wave) * 5f
            else -> -wave * 2f
        }

        val blink = when {
            blocked -> 0.72f
            cycle > 0.92f -> 0.18f
            else -> 1f
        }
        val mouthScale = when {
            blocked -> 0.72f
            state == H2g2WorkflowState.Active -> 1f + abs(fastWave) * 0.20f
            state == H2g2WorkflowState.Complete -> 1.15f
            else -> 1f
        }
        val browTilt = when {
            blocked -> 12f
            role == NodeCreatureRoleKind.AdversarialReviewer ||
                role == NodeCreatureRoleKind.Antagonist ||
                role == NodeCreatureRoleKind.ImplementationEngineer -> -10f
            state == H2g2WorkflowState.Active -> wave * 3f
            else -> 0f
        }

        val local = buildMap {
            put(Root, MascotBonePose(
                y = rootY,
                rotationDegrees = rootRotation,
                scaleX = rootScale,
                scaleY = rootScale,
            ))
            put(Head, MascotBonePose(y = headY, rotationDegrees = headRotation))
            put(TorsoUpper, MascotBonePose(rotationDegrees = torsoSway))
            put(TorsoMid, MascotBonePose(rotationDegrees = -torsoSway * 0.72f))
            put(Pelvis, MascotBonePose(rotationDegrees = torsoSway * 0.36f))

            put(LeftLegUpper, MascotBonePose(rotationDegrees = legSwing))
            put(LeftLegLower, MascotBonePose(rotationDegrees = kneeFlex))
            put(LeftFoot, MascotBonePose(rotationDegrees = -legSwing * 0.28f))
            put(RightLegUpper, MascotBonePose(rotationDegrees = -legSwing))
            put(RightLegLower, MascotBonePose(rotationDegrees = kneeFlex))
            put(RightFoot, MascotBonePose(rotationDegrees = legSwing * 0.28f))

            put(Prop, MascotBonePose(rotationDegrees = propRotation))

            val limbAmplitude = when {
                blocked -> 2f
                state == H2g2WorkflowState.Active -> 14f
                state == H2g2WorkflowState.Complete -> 6f
                else -> 3f
            }
            put(Limb0, MascotBonePose(rotationDegrees = sin(cycle * 2f * PI.toFloat()) * limbAmplitude))
            put(
                Limb1,
                MascotBonePose(
                    rotationDegrees = sin(cycle * 2f * PI.toFloat() + 0.8f) * limbAmplitude * 0.7f,
                ),
            )
            put(
                Limb2,
                MascotBonePose(
                    rotationDegrees = sin(cycle * 2f * PI.toFloat() + 1.6f) * limbAmplitude * 0.45f,
                ),
            )
            put(LeftEye, MascotBonePose(scaleY = blink))
            put(RightEye, MascotBonePose(scaleY = blink))
            put(LeftBrow, MascotBonePose(rotationDegrees = browTilt))
            put(RightBrow, MascotBonePose(rotationDegrees = -browTilt))
            put(Mouth, MascotBonePose(scaleY = mouthScale))

            repeat(antennaCount.coerceAtLeast(1)) { index ->
                val alternate = if (index % 2 == 0) 1f else -1f
                val roleAmplitude = when (role) {
                    NodeCreatureRoleKind.CrashTestDummy -> 12f
                    NodeCreatureRoleKind.UxDesigner,
                    NodeCreatureRoleKind.ProductManager -> 8f
                    NodeCreatureRoleKind.AdversarialReviewer,
                    NodeCreatureRoleKind.Antagonist -> 4f
                    else -> 6f
                }
                val stateAmplitude = when {
                    blocked -> 0.35f
                    state == H2g2WorkflowState.Active -> 1f
                    state == H2g2WorkflowState.Complete -> 0.72f
                    else -> 0.32f
                }
                val baseRotation = sin(
                    cycle * 2f * PI.toFloat() +
                        index * 0.73f +
                        role.ordinal * 0.17f,
                ) * roleAmplitude * stateAmplitude * alternate
                val midRotation = sin(
                    cycle * 2f * PI.toFloat() +
                        index * 0.73f +
                        0.8f,
                ) * roleAmplitude * 0.55f * stateAmplitude * -alternate
                val tipRotation = sin(
                    cycle * 2f * PI.toFloat() +
                        index * 0.73f +
                        1.6f,
                ) * roleAmplitude * 0.35f * stateAmplitude * alternate

                put(antennaBase(index), MascotBonePose(rotationDegrees = baseRotation))
                put(antennaMid(index), MascotBonePose(rotationDegrees = midRotation))
                put(antennaTip(index), MascotBonePose(rotationDegrees = tipRotation))
            }

            overrides.forEach { (boneId, override) ->
                val base = get(boneId) ?: MascotBonePose()
                put(boneId, base + override)
            }
        }

        return MascotPuppetPose(
            skeleton = skeleton(antennaCount),
            localPose = local,
        )
    }
}

internal data class MascotBone(
    val id: String,
    val parentId: String?,
    val pivotX: Float,
    val pivotY: Float,
)

internal data class MascotBonePose(
    val x: Float = 0f,
    val y: Float = 0f,
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    operator fun plus(other: MascotBonePose): MascotBonePose = MascotBonePose(
        x = x + other.x,
        y = y + other.y,
        rotationDegrees = rotationDegrees + other.rotationDegrees,
        scaleX = scaleX * other.scaleX,
        scaleY = scaleY * other.scaleY,
    )

    fun lerp(other: MascotBonePose, amount: Float): MascotBonePose {
        val t = amount.coerceIn(0f, 1f)
        return MascotBonePose(
            x = x + (other.x - x) * t,
            y = y + (other.y - y) * t,
            rotationDegrees = shortestRotation(rotationDegrees, other.rotationDegrees, t),
            scaleX = scaleX + (other.scaleX - scaleX) * t,
            scaleY = scaleY + (other.scaleY - scaleY) * t,
        )
    }

    private fun shortestRotation(from: Float, to: Float, amount: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return from + delta * amount
    }
}

internal data class MascotWorldBone(
    val pivotX: Float,
    val pivotY: Float,
    val rotationDegrees: Float,
    val scaleX: Float,
    val scaleY: Float,
)

internal class MascotSkeleton(
    val bones: List<MascotBone>,
) {
    private val byId = bones.associateBy(MascotBone::id)

    init {
        require(byId.size == bones.size) { "Mascot skeleton contains duplicate bone IDs." }
        bones.forEach { bone ->
            require(bone.parentId == null || byId.containsKey(bone.parentId)) {
                "Mascot bone ${bone.id} references missing parent ${bone.parentId}."
            }
        }
    }

    fun bone(id: String): MascotBone =
        requireNotNull(byId[id]) { "Unknown mascot bone: $id" }

    fun contains(id: String): Boolean = byId.containsKey(id)
}

internal class MascotPuppetPose(
    val skeleton: MascotSkeleton,
    private val localPose: Map<String, MascotBonePose>,
) {
    private val resolved = mutableMapOf<String, MascotWorldBone>()

    fun local(id: String): MascotBonePose = localPose[id] ?: MascotBonePose()

    fun world(id: String): MascotWorldBone = resolved.getOrPut(id) {
        val bone = skeleton.bone(id)
        val local = local(id)
        val parentId = bone.parentId
        if (parentId == null) {
            MascotWorldBone(
                pivotX = bone.pivotX + local.x,
                pivotY = bone.pivotY + local.y,
                rotationDegrees = local.rotationDegrees,
                scaleX = local.scaleX,
                scaleY = local.scaleY,
            )
        } else {
            val parent = world(parentId)
            val parentBone = skeleton.bone(parentId)
            val relativeX = bone.pivotX - parentBone.pivotX
            val relativeY = bone.pivotY - parentBone.pivotY
            val rotated = rotate(
                x = relativeX * parent.scaleX,
                y = relativeY * parent.scaleY,
                degrees = parent.rotationDegrees,
            )
            val translated = rotate(
                x = local.x * parent.scaleX,
                y = local.y * parent.scaleY,
                degrees = parent.rotationDegrees,
            )
            MascotWorldBone(
                pivotX = parent.pivotX + rotated.first + translated.first,
                pivotY = parent.pivotY + rotated.second + translated.second,
                rotationDegrees = parent.rotationDegrees + local.rotationDegrees,
                scaleX = parent.scaleX * local.scaleX,
                scaleY = parent.scaleY * local.scaleY,
            )
        }
    }

    /** Maps a rest-pose artboard coordinate through a named puppet bone. */
    fun map(id: String, x: Float, y: Float): MascotRigPoint {
        val bone = skeleton.bone(id)
        val world = world(id)
        val deltaX = (x - bone.pivotX) * world.scaleX
        val deltaY = (y - bone.pivotY) * world.scaleY
        val rotated = rotate(deltaX, deltaY, world.rotationDegrees)
        return MascotRigPoint(
            x = world.pivotX + rotated.first,
            y = world.pivotY + rotated.second,
        )
    }

    private companion object {
        fun rotate(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
            if (degrees == 0f) return x to y
            val radians = degrees * PI.toFloat() / 180f
            val c = cos(radians)
            val s = sin(radians)
            return (x * c - y * s) to (x * s + y * c)
        }
    }
}

internal data class MascotRigPoint(
    val x: Float,
    val y: Float,
)

/**
 * Generic keyframe format for scripted, edited or generated mascot motion.
 *
 * Clips target the same named bones used by the renderer, so workflow-state motion and custom
 * animation can be mixed without introducing a second animation system.
 */
internal data class MascotAnimationKeyframe(
    val progress: Float,
    val pose: MascotBonePose,
)

internal data class MascotAnimationTrack(
    val boneId: String,
    val keyframes: List<MascotAnimationKeyframe>,
) {
    init {
        require(keyframes.isNotEmpty()) { "Mascot animation track must contain a keyframe." }
    }

    fun sample(progress: Float): MascotBonePose {
        val sorted = keyframes.sortedBy(MascotAnimationKeyframe::progress)
        val t = progress.coerceIn(0f, 1f)
        val before = sorted.lastOrNull { it.progress <= t } ?: sorted.first()
        val after = sorted.firstOrNull { it.progress >= t } ?: sorted.last()
        if (before === after || before.progress == after.progress) return before.pose
        val local = (t - before.progress) / (after.progress - before.progress)
        return before.pose.lerp(after.pose, local)
    }
}

internal data class MascotAnimationClip(
    val name: String,
    val tracks: List<MascotAnimationTrack>,
    val loop: Boolean = true,
) {
    fun sample(progress: Float): Map<String, MascotBonePose> {
        val t = if (loop) {
            val wrapped = progress % 1f
            if (wrapped < 0f) wrapped + 1f else wrapped
        } else {
            progress.coerceIn(0f, 1f)
        }
        return tracks.associate { track -> track.boneId to track.sample(t) }
    }
}
