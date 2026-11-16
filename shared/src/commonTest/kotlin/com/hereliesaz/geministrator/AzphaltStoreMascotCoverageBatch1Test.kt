package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch1Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.aive.accessibility-audit" to listOf("Accessibility Auditor"),
        "com.hereliesaz.aive.adaptive-tutoring" to listOf("Learning Coach"),
        "com.hereliesaz.aive.agentic-ai-threat-model" to listOf("Agent Security Architect", "Agent Red Teamer"),
        "com.hereliesaz.aive.ai-feature-film-production" to listOf("Film Showrunner", "Film Continuity Supervisor"),
        "com.hereliesaz.aive.ai-graphic-novel-production" to listOf("Graphic Novel Director", "Graphic Novel Continuity Editor"),
        "com.hereliesaz.aive.ai-system-evaluation" to listOf("AI Evaluation Scientist"),
        "com.hereliesaz.aive.brand-identity-system" to listOf("Brand Strategist", "Identity Director"),
        "com.hereliesaz.aive.campaign-planning" to listOf("Campaign Strategist"),
        "com.hereliesaz.aive.competitive-intelligence" to listOf("Competitive Analyst"),
        "com.hereliesaz.aive.content-strategy" to listOf("Content Strategist"),
        "com.hereliesaz.aive.contract-issue-spotting" to listOf("Contract Reviewer"),
        "com.hereliesaz.aive.crisis-communications" to listOf("Crisis Communicator"),
        "com.hereliesaz.aive.curriculum-design" to listOf("Curriculum Architect"),
        "com.hereliesaz.aive.customer-support-escalation" to listOf("Support Escalation Lead"),
        "com.hereliesaz.aive.data-analysis-investigation" to listOf("Data Investigator", "Statistical Skeptic"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
