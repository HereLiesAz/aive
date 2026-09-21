package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MascotPuppetRigTest {
    @Test
    fun everyMajorCharacterRegionHasAnAddressableBone() {
        val skeleton = MascotPuppetRig.skeleton(8)
        val ids = skeleton.bones.map(MascotBone::id).toSet()

        assertTrue(MascotPuppetRig.Root in ids)
        assertTrue(MascotPuppetRig.Head in ids)
        assertTrue(MascotPuppetRig.TorsoUpper in ids)
        assertTrue(MascotPuppetRig.TorsoMid in ids)
        assertTrue(MascotPuppetRig.Pelvis in ids)
        assertTrue(MascotPuppetRig.LeftLeg in ids)
        assertTrue(MascotPuppetRig.RightLeg in ids)
        assertTrue(MascotPuppetRig.Prop in ids)
        assertTrue(MascotPuppetRig.LeftEye in ids)
        assertTrue(MascotPuppetRig.RightEye in ids)
        assertTrue(MascotPuppetRig.Mouth in ids)
        repeat(8) { index ->
            assertTrue(MascotPuppetRig.antenna(index) in ids)
        }
    }

    @Test
    fun antennaBonesAreIndependentlyAnimated() {
        val pose = MascotPuppetRig.pose(
            role = NodeCreatureRoleKind.Orchestrator,
            state = H2g2WorkflowState.Active,
            phase = 0.31f,
            antennaCount = 8,
        )

        val rotations = (0 until 8).map { index ->
            pose.local(MascotPuppetRig.antenna(index)).rotationDegrees
        }

        assertTrue(rotations.distinct().size > 2)
    }

    @Test
    fun stateChangesProduceDifferentPuppetPoses() {
        val ready = MascotPuppetRig.pose(
            role = NodeCreatureRoleKind.ImplementationEngineer,
            state = H2g2WorkflowState.Ready,
            phase = 0.37f,
            antennaCount = 8,
        )
        val active = MascotPuppetRig.pose(
            role = NodeCreatureRoleKind.ImplementationEngineer,
            state = H2g2WorkflowState.Active,
            phase = 0.37f,
            antennaCount = 8,
        )
        val blocked = MascotPuppetRig.pose(
            role = NodeCreatureRoleKind.ImplementationEngineer,
            state = H2g2WorkflowState.Blocked,
            phase = 0.37f,
            antennaCount = 8,
        )

        assertNotEquals(
            ready.local(MascotPuppetRig.Head).rotationDegrees,
            active.local(MascotPuppetRig.Head).rotationDegrees,
        )
        assertNotEquals(
            active.local(MascotPuppetRig.Prop).rotationDegrees,
            blocked.local(MascotPuppetRig.Prop).rotationDegrees,
        )
    }

    @Test
    fun childBoneFollowsParentTransform() {
        val pose = MascotPuppetRig.pose(
            role = NodeCreatureRoleKind.Researcher,
            state = H2g2WorkflowState.Active,
            phase = 0.25f,
            antennaCount = 5,
        )
        val head = pose.world(MascotPuppetRig.Head)
        val eye = pose.world(MascotPuppetRig.LeftEye)

        assertNotEquals(100f, head.pivotY)
        assertTrue(eye.pivotY < 100f)
        assertEquals(head.rotationDegrees, eye.rotationDegrees)
    }
}
