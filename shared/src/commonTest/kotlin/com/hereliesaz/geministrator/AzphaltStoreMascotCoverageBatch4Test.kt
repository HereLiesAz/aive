package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch4Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.haive.pricing-packaging-research" to listOf("Pricing Researcher"),
        "com.hereliesaz.haive.privacy-impact-assessment" to listOf("Privacy Risk Analyst"),
        "com.hereliesaz.haive.process-redesign" to listOf("Operations Designer"),
        "com.hereliesaz.haive.product-launch" to listOf("Launch Strategist"),
        "com.hereliesaz.haive.production-incident-response" to listOf("Incident Commander", "Incident Investigator"),
        "com.hereliesaz.haive.rag-knowledge-evaluation" to listOf("Knowledge System Evaluator"),
        "com.hereliesaz.haive.rfp-response" to listOf("Proposal Lead"),
        "com.hereliesaz.haive.role.agent-security-architect" to listOf("Agent Security Architect"),
        "com.hereliesaz.haive.role.continuity-supervisor" to listOf("Continuity Supervisor"),
        "com.hereliesaz.haive.role.corporate-investigator" to listOf("Corporate Investigator"),
        "com.hereliesaz.haive.role.data-investigator" to listOf("Data Investigator"),
        "com.hereliesaz.haive.role.deep-researcher" to listOf("Deep Researcher"),
        "com.hereliesaz.haive.role.evidence-skeptic" to listOf("Evidence Skeptic"),
        "com.hereliesaz.haive.role.failure-diagnostician" to listOf("Failure Diagnostician"),
        "com.hereliesaz.haive.role.feature-reviewer" to listOf("Feature Reviewer"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
