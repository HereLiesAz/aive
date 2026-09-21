package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch2Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.haive.data-storytelling" to listOf("Data Story Editor"),
        "com.hereliesaz.haive.decision-analysis" to listOf("Decision Analyst"),
        "com.hereliesaz.haive.deep-research-report" to listOf("Deep Research Lead", "Evidence Skeptic"),
        "com.hereliesaz.haive.documentary-production" to listOf("Documentary Producer"),
        "com.hereliesaz.haive.event-production" to listOf("Event Producer"),
        "com.hereliesaz.haive.evidence-synthesis" to listOf("Evidence Synthesis Lead", "Methods Reviewer"),
        "com.hereliesaz.haive.exhibition-production" to listOf("Exhibition Producer"),
        "com.hereliesaz.haive.experimental-design" to listOf("Experimental Methodologist"),
        "com.hereliesaz.haive.feature-delivery" to listOf("Feature Engineer", "Feature Reviewer"),
        "com.hereliesaz.haive.financial-model-qa" to listOf("Financial Model Auditor"),
        "com.hereliesaz.haive.game-vertical-slice" to listOf("Game Director", "Playtest Analyst"),
        "com.hereliesaz.haive.grant-application" to listOf("Grant Strategist"),
        "com.hereliesaz.haive.interview-synthesis" to listOf("Qualitative Synthesist"),
        "com.hereliesaz.haive.investigative-journalism" to listOf("Investigative Reporter"),
        "com.hereliesaz.haive.localization-release" to listOf("Localization Lead"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
