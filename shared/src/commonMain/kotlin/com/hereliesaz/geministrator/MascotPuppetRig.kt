package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Runtime 2D puppet rig for workflow mascots.
 *
 * The approved 2D character design is the source of truth. Animation is applied through named,
 * hierarchical bones rather than by regenerating a 3D approximation. Every major body region,
 * face control, prop anchor, leg, and antenna receives its own independently addressable bone.
 */
internal object MascotPuppetRig {
    const val Root = "root"
    const val Head = "head"
    const val TorsoUpper = "torso.upper"
    const val TorsoMid = "torso.mid"
    const val Pelvis = "pelvis"
    const val LeftLeg = "leg.left"
    const val RightLeg = "leg.right"
    const val Prop = "prop"
    const val LeftEye = "face.eye.left"
    const val RightEye = "face.eye.right"
    const val Mouth = "face.mouth"

    fun antenna(index: Int): String = "antenna.$index"

    fun skeleton(antennaCount: Int): MascotSkeleton {
        val bones = buildList {
            add(MascotBone(Root, null, 100f, 100f))
            add(MascotBone(Head, Root, 100f, 83f))
            add(MascotBone(TorsoUpper, Root, 100f, 120f))
            add(MascotBone(TorsoMid, TorsoUpper, 100f, 139f))
            add(MascotBone(Pelvis, TorsoMid, 100f, 155f))
            add(MascotBone(LeftLeg, Pelvis, 94f, 154f))
            add(MascotBone(RightLeg, Pelvis, 106f, 154f))
            add(MascotBone(Prop, Root, 134f, 104f))
            add(MascotBone(LeftEye, Head, 88f, 81f))
            add(MascotBone(RightEye, Head, 112f, 81f))
            add(MascotBone(Mouth, Head, 100f, 98f))
            repeat(antennaCount.coerceAtLeast(1)) { index ->
                add(MascotBone(antenna(index), Head, 100f, 83f))
            }
        }
        return MascotSkeleton(bones)
    }

    fun pose(
        role: NodeCreatureRoleKind,
        state: H2g2WorkflowState,
        phase: Float,
        antennaCount: Int,
    ): MascotPuppetPose {
        val wave = sin(phase * 2f * PI.toFloat())
        val fastWave = sin(phase * 4f * PI.toFloat())
        val blocked = state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed

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
        val propRotation = when {
            blocked -> 12f
            state == H2g2WorkflowState.Active -> -wave * 9f
            state == H2g2WorkflowState.Complete -> -8f - abs(wave) * 5f
            else -> -wave * 2f
        }

        val blink = when {
            blocked -> 0.72f
            phase > 0.92f -> 0.18f
            else -> 1f
        }
        val mouthScale = when {
            blocked -> 0.72f
            state == H2g2WorkflowState.Active -> 1f + abs(fastWave) * 0.20f
            state == H2g2WorkflowState.Complete -> 1.15f
            else -> 1f
        }

        val local = buildMap {
            put(Root, MascotBonePose())
            put(Head, MascotBonePose(y = headY, rotationDegrees = headRotation))
            put(TorsoUpper, MascotBonePose(rotationDegrees = torsoSway))
            put(TorsoMid, MascotBonePose(rotationDegrees = -torsoSway * 0.72f))
            put(Pelvis, MascotBonePose(rotationDegrees = torsoSway * 0.36f))
            put(LeftLeg, MascotBonePose(rotationDegrees = legSwing))
            put(RightLeg, MascotBonePose(rotationDegrees = -legSwing))
            put(Prop, MascotBonePose(rotationDegrees = propRotation))
            put(LeftEye, MascotBonePose(scaleY = blink))
            put(RightEye, MascotBonePose(scaleY = blink))
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
                val rotation = sin(
                    phase * 2f * PI.toFloat() +
                        index * 0.73f +
                        role.ordinal * 0.17f,
                ) * roleAmplitude * stateAmplitude * alternate
                put(antenna(index), MascotBonePose(rotationDegrees = rotation))
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
)

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
                "Mascot bone \${bone.id} references missing parent \${bone.parentId}."
            }
        }
    }

    fun bone(id: String): MascotBone =
        requireNotNull(byId[id]) { "Unknown mascot bone: $id" }
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

    /**
     * Maps a rest-pose design-space coordinate through a named puppet bone.
     *
     * Coordinates use the mascot's canonical 200x200 artboard.
     */
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
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return (x * c - y * s) to (x * s + y * c)
        }
    }
}

internal data class MascotRigPoint(
    val x: Float,
    val y: Float,
)
