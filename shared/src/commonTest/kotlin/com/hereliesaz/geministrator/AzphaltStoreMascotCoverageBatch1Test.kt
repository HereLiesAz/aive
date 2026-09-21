package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch1Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.haive.accessibility-audit" to listOf("Accessibility Auditor"),
        "com.hereliesaz.haive.adaptive-tutoring" to listOf("Learning Coach"),
        "com.hereliesaz.haive.agentic-ai-threat-model" to listOf("Agent Security Architect", "Agent Red Teamer"),
        "com.hereliesaz.haive.ai-feature-film-production" to listOf("Film Showrunner", "Film Continuity Supervisor"),
        "com.hereliesaz.haive.ai-graphic-novel-production" to listOf("Graphic Novel Director", "Graphic Novel Continuity Editor"),
        "com.hereliesaz.haive.ai-system-evaluation" to listOf("AI Evaluation Scientist"),
        "com.hereliesaz.haive.brand-identity-system" to listOf("Brand Strategist", "Identity Director"),
        "com.hereliesaz.haive.campaign-planning" to listOf("Campaign Strategist"),
        "com.hereliesaz.haive.competitive-intelligence" to listOf("Competitive Analyst"),
        "com.hereliesaz.haive.content-strategy" to listOf("Content Strategist"),
        "com.hereliesaz.haive.contract-issue-spotting" to listOf("Contract Reviewer"),
        "com.hereliesaz.haive.crisis-communications" to listOf("Crisis Communicator"),
        "com.hereliesaz.haive.curriculum-design" to listOf("Curriculum Architect"),
        "com.hereliesaz.haive.customer-support-escalation" to listOf("Support Escalation Lead"),
        "com.hereliesaz.haive.data-analysis-investigation" to listOf("Data Investigator", "Statistical Skeptic"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
