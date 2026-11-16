package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch5Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.aive.role.geolocation-analyst" to listOf("Geolocation Analyst"),
        "com.hereliesaz.aive.role.incident-commander" to listOf("Incident Commander"),
        "com.hereliesaz.aive.role.osint-verifier" to listOf("OSINT Verifier"),
        "com.hereliesaz.aive.role.ux-researcher" to listOf("UX Researcher"),
        "com.hereliesaz.aive.root-cause-repair" to listOf("Failure Diagnostician", "Repair Verifier"),
        "com.hereliesaz.aive.scenario-planning" to listOf("Scenario Planner"),
        "com.hereliesaz.aive.scientific-literature-review" to listOf("Review Scientist"),
        "com.hereliesaz.aive.search-visibility" to listOf("Search Strategist"),
        "com.hereliesaz.aive.sop-process-engineering" to listOf("Process Engineer"),
        "com.hereliesaz.aive.structured-hiring" to listOf("Selection Designer"),
        "com.hereliesaz.aive.supplier-due-diligence" to listOf("Supplier Analyst"),
        "com.hereliesaz.aive.survey-research" to listOf("Survey Methodologist"),
        "com.hereliesaz.aive.technical-documentation" to listOf("Technical Writer"),
        "com.hereliesaz.aive.ux-research-to-design" to listOf("UX Researcher", "Interaction Designer"),
        "com.hereliesaz.aive.vendor-evaluation" to listOf("Vendor Evaluator"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
