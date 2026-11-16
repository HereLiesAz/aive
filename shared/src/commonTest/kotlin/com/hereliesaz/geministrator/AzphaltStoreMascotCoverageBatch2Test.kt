package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch2Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.aive.data-storytelling" to listOf("Data Story Editor"),
        "com.hereliesaz.aive.decision-analysis" to listOf("Decision Analyst"),
        "com.hereliesaz.aive.deep-research-report" to listOf("Deep Research Lead", "Evidence Skeptic"),
        "com.hereliesaz.aive.documentary-production" to listOf("Documentary Producer"),
        "com.hereliesaz.aive.event-production" to listOf("Event Producer"),
        "com.hereliesaz.aive.evidence-synthesis" to listOf("Evidence Synthesis Lead", "Methods Reviewer"),
        "com.hereliesaz.aive.exhibition-production" to listOf("Exhibition Producer"),
        "com.hereliesaz.aive.experimental-design" to listOf("Experimental Methodologist"),
        "com.hereliesaz.aive.feature-delivery" to listOf("Feature Engineer", "Feature Reviewer"),
        "com.hereliesaz.aive.financial-model-qa" to listOf("Financial Model Auditor"),
        "com.hereliesaz.aive.game-vertical-slice" to listOf("Game Director", "Playtest Analyst"),
        "com.hereliesaz.aive.grant-application" to listOf("Grant Strategist"),
        "com.hereliesaz.aive.interview-synthesis" to listOf("Qualitative Synthesist"),
        "com.hereliesaz.aive.investigative-journalism" to listOf("Investigative Reporter"),
        "com.hereliesaz.aive.localization-release" to listOf("Localization Lead"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
