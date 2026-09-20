package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrariumFullCastVisualProofTest {
    @Test
    fun visualProofProjectsEveryWorkflowPersonalityAndEveryStateFamily() {
        val fixture = terrariumVisualProofFixture()
        val projection = projectWorkflowTerrarium(
            definition = fixture.definition,
            run = fixture.run,
            roles = fixture.roles,
        )

        assertEquals(
            setOf(
                "Orchestrator",
                "Product Manager",
                "Researcher",
                "Architect",
                "EPA Representative",
                "UX Designer",
                "Implementation Engineer",
                "Crash Test Dummy",
                "QA Engineer",
                "Adversarial Reviewer",
                "Code Reviewer",
                "Recovery Engineer",
                "Release Engineer",
                "Antagonist",
                "Hall Monitor",
            ),
            projection.subjects.map { it.node.label }.toSet(),
        )
        assertEquals(15, projection.subjects.size)
        assertTrue(projection.relationships.isNotEmpty())
        assertTrue(
            projection.subjects.map { it.node.state }.toSet().containsAll(
                setOf(
                    H2g2WorkflowState.Pending,
                    H2g2WorkflowState.Ready,
                    H2g2WorkflowState.Active,
                    H2g2WorkflowState.Blocked,
                    H2g2WorkflowState.Failed,
                    H2g2WorkflowState.Complete,
                    H2g2WorkflowState.Gate,
                ),
            ),
        )
    }
}
