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

    @Test fun inheritedAnatomyAndFunctionalPigmentReachActualGpuMesh() {
        val genome=SpatialAgentGenome(19,3,1,2,SpatialCellSignal.Excitatory,SpatialCellSignal.Inhibitory)
        val queen=SpatialCreature("queen","Queen",null,SpatialPoint.Origin,1,H2g2WorkflowState.Pending,SpatialForm.Nucleus)
        val creature=SpatialCreature("agent","Agent",null,SpatialPoint(3f,0f,0f),3,H2g2WorkflowState.Pending,
            SpatialForm.Creature,genome=genome)
        val world=SpatialHiveScene("run","workflow",queen,listOf(creature),emptyList(),SpatialMemoryProjection(emptyList(),emptyList()))
        val body=world.renderFrame(SpatialTissueJournal("run")).bodies.single { it.id=="agent" }
        assertEquals(genome,body.genome)
        val frame=SpatialRenderFrame(listOf(body),emptyList(),emptyList())
        val mesh=frame.triangles()
        val other=frame.copy(bodies=listOf(body.copy(genome=genome.copy(arborSeed=7)))).triangles()
        assertTrue(mesh.contentEquals(frame.triangles()))
        assertTrue(mesh.size!=other.size)
        val colors=(6 until mesh.size step 16).map { Triple(mesh[it],mesh[it+1],mesh[it+2]) }.toSet()
        assertTrue(Triple(.24f,.35f,.86f) in colors)
    }
}
