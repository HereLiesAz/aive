package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeCreatureRoleClassifierTest {
    @Test
    fun supportedAliasesMatchMascotRoleSemantics() {
        val aliases = mapOf(
            "Systems Monitor" to NodeCreatureRoleKind.HallMonitor,
            "Antagonist" to NodeCreatureRoleKind.Antagonist,
            "Adversarial Auditor" to NodeCreatureRoleKind.AdversarialReviewer,
            "Repair Engineer" to NodeCreatureRoleKind.RecoveryEngineer,
            "Publisher" to NodeCreatureRoleKind.ReleaseEngineer,
            "Code-Review Specialist" to NodeCreatureRoleKind.CodeReviewer,
            "Stress Test Agent" to NodeCreatureRoleKind.CrashTestDummy,
            "Inspector" to NodeCreatureRoleKind.QaEngineer,
            "Verification Engineer" to NodeCreatureRoleKind.QaEngineer,
            "Developer" to NodeCreatureRoleKind.ImplementationEngineer,
            "Coder" to NodeCreatureRoleKind.ImplementationEngineer,
            "Experience Designer" to NodeCreatureRoleKind.UxDesigner,
            "Environment Planner" to NodeCreatureRoleKind.EpaRepresentative,
            "Systems Design" to NodeCreatureRoleKind.Architect,
            "Research Analyst" to NodeCreatureRoleKind.Researcher,
            "Task Planner" to NodeCreatureRoleKind.ProductManager,
            "Swarm Lead" to NodeCreatureRoleKind.Orchestrator,
            "Swarm Coordinator" to NodeCreatureRoleKind.Orchestrator,
        )

        aliases.forEach { (label, expected) ->
            assertEquals(expected, classifyNodeCreatureRole(label), label)
        }
    }

    @Test
    fun activeLabelsFollowAliasClassification() {
        assertEquals(
            "VERIFYING",
            mascotActivityLabel("Inspector", H2g2WorkflowState.Active),
        )
        assertEquals(
            "BUILDING",
            mascotActivityLabel("Developer", H2g2WorkflowState.Active),
        )
        assertEquals(
            "BUILDING",
            mascotActivityLabel("Coder", H2g2WorkflowState.Active),
        )
        assertEquals(
            "RELEASING",
            mascotActivityLabel("Publisher", H2g2WorkflowState.Active),
        )
        assertEquals(
            "MONITORING",
            mascotActivityLabel("Systems Monitor", H2g2WorkflowState.Active),
        )
    }

    @Test
    fun workflowStateLabelsOverrideRoleActivity() {
        for ((state, expected) in listOf(
            H2g2WorkflowState.Pending to "QUEUED",
            H2g2WorkflowState.Ready to "READY",
            H2g2WorkflowState.Blocked to "BLOCKED",
            H2g2WorkflowState.Failed to "FAILED",
            H2g2WorkflowState.Complete to "COMPLETE",
            H2g2WorkflowState.Gate to "AWAITING GATE",
        )) {
            assertEquals(expected, mascotActivityLabel("Developer", state))
        }
    }
}
