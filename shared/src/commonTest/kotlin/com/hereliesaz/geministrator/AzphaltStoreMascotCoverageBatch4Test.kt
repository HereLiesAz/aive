package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch4Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.aive.pricing-packaging-research" to listOf("Pricing Researcher"),
        "com.hereliesaz.aive.privacy-impact-assessment" to listOf("Privacy Risk Analyst"),
        "com.hereliesaz.aive.process-redesign" to listOf("Operations Designer"),
        "com.hereliesaz.aive.product-launch" to listOf("Launch Strategist"),
        "com.hereliesaz.aive.production-incident-response" to listOf("Incident Commander", "Incident Investigator"),
        "com.hereliesaz.aive.rag-knowledge-evaluation" to listOf("Knowledge System Evaluator"),
        "com.hereliesaz.aive.rfp-response" to listOf("Proposal Lead"),
        "com.hereliesaz.aive.role.agent-security-architect" to listOf("Agent Security Architect"),
        "com.hereliesaz.aive.role.continuity-supervisor" to listOf("Continuity Supervisor"),
        "com.hereliesaz.aive.role.corporate-investigator" to listOf("Corporate Investigator"),
        "com.hereliesaz.aive.role.data-investigator" to listOf("Data Investigator"),
        "com.hereliesaz.aive.role.deep-researcher" to listOf("Deep Researcher"),
        "com.hereliesaz.aive.role.evidence-skeptic" to listOf("Evidence Skeptic"),
        "com.hereliesaz.aive.role.failure-diagnostician" to listOf("Failure Diagnostician"),
        "com.hereliesaz.aive.role.feature-reviewer" to listOf("Feature Reviewer"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
