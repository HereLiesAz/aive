package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HallMonitorWorkflowFragmentFactoryTest {
    @Test
    fun fragmentOrdersHallMonitorBeforeColdAntagonistReview() {
        val fragment = HallMonitorWorkflowFragmentFactory.create()
        val report = fragment.tasks.single { it.roleId == HallMonitorRole.id }
        val review = fragment.tasks.single { it.roleId == BuiltInRoles.Antagonist.id }

        assertEquals(setOf(report.id), review.dependsOn)
        assertEquals(setOf(ArtifactKind.HallMonitorReport), report.requiredArtifacts)
        assertEquals(setOf(ArtifactKind.HallMonitorReview), review.requiredArtifacts)
        assertEquals(setOf(report.id), fragment.entryPoints)
        assertEquals(setOf(review.id), fragment.exitPoints)
        assertTrue(report.objective.contains("memory layer"))
        assertTrue(report.objective.contains("counter-evidence"))
        assertTrue(review.objective.contains("pass"))
    }
}
