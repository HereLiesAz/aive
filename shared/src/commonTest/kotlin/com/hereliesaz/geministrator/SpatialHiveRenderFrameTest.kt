package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.memory.WebGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialHiveRenderFrameTest {
    @Test fun rendererReceivesOnlyCurrentWorldAndValidatedInputs() {
        val queen = SpatialCreature("queen", "Queen", null, SpatialPoint.Origin, 1, H2g2WorkflowState.Pending, SpatialForm.Nucleus)
        val spore = SpatialSpore("memory-one", WebGroup.Phrase, "Phrase", 1f, SpatialPoint(1f,-2f,0f))
        val world = SpatialHiveScene("run", "workflow", queen, emptyList(), emptyList(),
            SpatialMemoryProjection(listOf(spore), emptyList()))
        val frame = world.renderFrame(SpatialTissueJournal("run"))
        assertEquals("Nucleus", frame.bodies.single().form)
        assertEquals(SpatialPoint(0f,0f,0f), frame.bodies.single().position)
        assertEquals("memory-one", frame.spores.single().id)
        assertTrue(frame.accepts(SpatialRenderInput("agent","queen")))
        assertTrue(frame.accepts(SpatialRenderInput("memory","memory-one")))
        assertFalse(frame.accepts(SpatialRenderInput("agent","memory-one")))
        assertFalse(frame.accepts(SpatialRenderInput("memory","old-run-memory")))
        assertFalse(frame.accepts(SpatialRenderInput("approve","queen")))
        assertFalse(frame.accepts(SpatialRenderInput("orbit",x=Float.NaN)))
    }
}
