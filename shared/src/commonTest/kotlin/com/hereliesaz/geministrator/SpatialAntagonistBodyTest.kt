package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Antagonist's resting body: a required agent's presence, not a stage, a host or a takeover. */
class SpatialAntagonistBodyTest {
    private val queen = SpatialCreature("queen", "Queen", null, SpatialPoint.Origin, 1, H2g2WorkflowState.Pending, SpatialForm.Nucleus)
    private val worm = SpatialCreature(SPATIAL_ANTAGONIST_ID, "Antagonist", null, SpatialPoint(3f, -2f, 0f), spatialSeed(SPATIAL_ANTAGONIST_ID),
        H2g2WorkflowState.Pending, SpatialForm.Antagonist)

    @Test
    fun theBodyIsInTheFrameAsItsOwnIdentityAndMeshesToFiniteVolume() {
        val scene = SpatialHiveScene("run", "workflow", queen, listOf(worm), emptyList(), SpatialMemoryProjection(emptyList(), emptyList()))
        val frame = scene.renderFrame(SpatialTissueJournal("run"))
        val body = frame.bodies.single { it.id == SPATIAL_ANTAGONIST_ID }
        assertEquals("Antagonist", body.form)
        assertEquals(SpatialPoint(3f, -2f, 0f), body.position)
        assertEquals(.5f, body.radius)
        val data = SpatialRenderFrame(listOf(body), emptyList(), emptyList()).triangles()
        assertTrue(data.isNotEmpty() && data.size % 48 == 0)
        assertTrue(data.all(Float::isFinite))
        // It lies in a coil about its own site, nowhere near the queen's origin.
        val xs = (0 until data.size step 16).map { data[it] }
        assertTrue(xs.min() > -1.5f && xs.max() > 4.5f, "x from ${xs.min()} to ${xs.max()}")
        // Dark wet translucent membrane for the body; solid pale teeth.
        val materials = (0 until data.size step 16).map { Triple(data[it + 13], data[it + 14], data[it + 15]) }.toSet()
        assertTrue(Triple(.23f, .72f, 0f) in materials)
        assertTrue(Triple(.58f, 0f, 0f) in materials)
    }

    @Test
    fun theBodyIsPickableByItsIdentityButIsNotATask() {
        val frame = SpatialRenderFrame(listOf(SpatialRenderBody(SPATIAL_ANTAGONIST_ID, SpatialPoint.Origin, 1, "Antagonist", "Pending", 0f, .5f)),
            emptyList(), emptyList())
        assertTrue(frame.accepts(SpatialRenderInput("agent", SPATIAL_ANTAGONIST_ID)))
        assertTrue(SPATIAL_ANTAGONIST_ID.startsWith("role:")) // never the id of a task or a host
    }
}
