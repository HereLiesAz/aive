package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HaiveNodeTerrariumTest {
    @Test
    fun rolesMapToDistinctVisualArchetypes() {
        assertEquals(HaiveNodeArchetype.Orchestrator, haiveNodeArchetype("Orchestrator"))
        assertEquals(HaiveNodeArchetype.Builder, haiveNodeArchetype("Implementation Engineer"))
        assertEquals(HaiveNodeArchetype.Tester, haiveNodeArchetype("Crash Test Dummy"))
        assertEquals(HaiveNodeArchetype.Inspector, haiveNodeArchetype("QA Engineer"))
        assertEquals(HaiveNodeArchetype.Reviewer, haiveNodeArchetype("Code Reviewer"))
        assertEquals(HaiveNodeArchetype.Planner, haiveNodeArchetype("Task Planner"))
        assertEquals(HaiveNodeArchetype.Researcher, haiveNodeArchetype("Research Analyst"))
    }

    @Test
    fun everyCreatureHasThreeToTenAntennae() {
        val labels = listOf(
            "Orchestrator",
            "Implementation Engineer",
            "Crash Test Dummy",
            "QA Engineer",
            "Code Reviewer",
            "Task Planner",
            "Research Analyst",
            "Unknown Specialist",
        )

        labels.forEachIndexed { index, label ->
            val count = haiveNodeAntennaCount(label, "seed-$index")
            assertTrue(count in 3..10, "$label should have 3–10 antennae, got $count")
        }
    }

    @Test
    fun activeRoleCommunicatesItsCurrentOperation() {
        assertEquals("ROUTING", haiveNodeActivityVerb(HaiveNodeArchetype.Orchestrator, H2g2WorkflowState.Active))
        assertEquals("BUILDING", haiveNodeActivityVerb(HaiveNodeArchetype.Builder, H2g2WorkflowState.Active))
        assertEquals("STRESS-TESTING", haiveNodeActivityVerb(HaiveNodeArchetype.Tester, H2g2WorkflowState.Active))
        assertEquals("VERIFYING", haiveNodeActivityVerb(HaiveNodeArchetype.Inspector, H2g2WorkflowState.Active))
        assertEquals("REVIEWING", haiveNodeActivityVerb(HaiveNodeArchetype.Reviewer, H2g2WorkflowState.Active))
        assertEquals("BLOCKED", haiveNodeActivityVerb(HaiveNodeArchetype.Reviewer, H2g2WorkflowState.Blocked))
    }

    @Test
    fun relationshipAnchorsSelectAntennaeFacingTheirPeer() {
        val right = haiveNodeTerminalAnchor("Implementation Engineer", "builder", Offset(1f, 0f))
        val left = haiveNodeTerminalAnchor("Implementation Engineer", "builder", Offset(-1f, 0f))
        val up = haiveNodeTerminalAnchor("QA Engineer", "qa", Offset(0f, -1f))
        val down = haiveNodeTerminalAnchor("QA Engineer", "qa", Offset(0f, 1f))

        assertTrue(right.x > 0f)
        assertTrue(left.x < 0f)
        assertTrue(up.y < 0f)
        assertTrue(down.y > 0f)
    }
}
