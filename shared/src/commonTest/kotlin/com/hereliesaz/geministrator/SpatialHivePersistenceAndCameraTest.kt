package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.testing.PreferencesLimitedSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpatialHivePersistenceAndCameraTest {
    @Test fun layoutPersistsAcrossStoreInstancesAndLargeDesktopValues() {
        val settings = PreferencesLimitedSettings()
        val id = WorkflowDefinitionId("a-long-definition-identifier")
        val ids = (1..100).map { "long-subject-identifier-$it" }
        val first = SpatialHiveLayoutStore(settings).resolve(id, ids, "queen")
        val reopened = SpatialHiveLayoutStore(settings).resolve(id, ids.reversed() + "new", "queen")
        assertEquals(101, first.size)
        assertEquals(102, reopened.size)
        assertEquals(SpatialPoint(0f, 0f, 0f), reopened["queen"])
        first.forEach { (key, point) -> assertEquals(point, reopened[key]) }
        assertTrue(settings.keys.all { it.length <= PreferencesLimitedSettings.MAX_KEY_LENGTH })
    }

    @Test fun memoryCameraReturnsToTheSameColony() {
        val colony = SpatialCameraState().orbit(32f, 5f)
        val memory = colony.memory(listOf(SpatialPoint(0f, -2f, 0f))).advance(1f, true)
        assertEquals(SpatialCameraMode.Memory, memory.mode)
        assertTrue(memory.pose.eye().y > 12f)
        assertTrue(memory.pose.distance > colony.pose.distance)
        assertEquals(SpatialPoint(0f, -2f, 0f), memory.pose.gaze)
        assertEquals(colony.pose, memory.colony().advance(1f, true).pose)
    }

    @Test fun directOrbitCancelsAnAutomaticTransition() {
        val focus = SpatialPoint(3f, 1f, 0f)
        val camera = SpatialCameraState().focus(focus, explicit = true)
        val manual = camera.orbit(25f, -2f)
        assertEquals(manual.pose, manual.advance(10f, false).pose)
        assertEquals(manual, manual.focus(SpatialPoint(0f, 1f, 4f), explicit = false))
        assertEquals(focus, manual.focus(focus, explicit = true).advance(0f, true).pose.gaze)
    }

    @Test fun pathClearanceChecksBetweenSamples() {
        assertEquals(2f, spatialSegmentClearance(SpatialPoint(0f, 0f, 0f), SpatialPoint(4f, 0f, 0f), SpatialPoint(2f, 2f, 0f)))
        assertEquals(1f, spatialSegmentClearance(SpatialPoint(0f, 0f, 0f), SpatialPoint(4f, 0f, 0f), SpatialPoint(5f, 0f, 0f)))
    }

    private fun scene(runId: String): SpatialHiveScene {
        val queen = SpatialCreature("queen", "Queen", null, SpatialPoint(0f, 0f, 0f), 1, H2g2WorkflowState.Pending, SpatialForm.Nucleus)
        val a = SpatialCreature("a", "A", null, SpatialPoint(3f, 0f, 0f), 2, H2g2WorkflowState.Pending, SpatialForm.Creature)
        val b = SpatialCreature("b", "B", null, SpatialPoint(0f, 0f, 4f), 3, H2g2WorkflowState.Pending, SpatialForm.Creature)
        return SpatialHiveScene(runId, "workflow", queen, listOf(a, b), emptyList(), SpatialMemoryProjection(emptyList(), emptyList()))
    }

    @Test fun tissueReopensWithoutReplayingOrReplacingCenterlines() {
        val settings = PreferencesLimitedSettings()
        val world = scene("run-one")
        val first = SpatialTissueStore(settings).advance(world, "opening-one")
        assertEquals(setOf("a", "b"), first.branches.mapNotNull { it.to.organismId }.toSet())
        val store = SpatialTissueStore(settings)
        assertEquals(first, store.load("run-one"))
        assertEquals(first, store.advance(world, "opening-one"))
        val second = store.advance(world, "opening-two")
        assertEquals(2, second.events.size)
        assertEquals(first.events.single(), second.events.first())
        assertTrue(second.branches.size > first.branches.size)
        assertEquals(second, SpatialTissueStore(settings).load("run-one"))
        assertNotEquals(first.branches.map { it.points }, store.advance(scene("run-two"), "opening-one").branches.map { it.points })
    }
}
