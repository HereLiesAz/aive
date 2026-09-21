package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch5Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.haive.role.geolocation-analyst" to listOf("Geolocation Analyst"),
        "com.hereliesaz.haive.role.incident-commander" to listOf("Incident Commander"),
        "com.hereliesaz.haive.role.osint-verifier" to listOf("OSINT Verifier"),
        "com.hereliesaz.haive.role.ux-researcher" to listOf("UX Researcher"),
        "com.hereliesaz.haive.root-cause-repair" to listOf("Failure Diagnostician", "Repair Verifier"),
        "com.hereliesaz.haive.scenario-planning" to listOf("Scenario Planner"),
        "com.hereliesaz.haive.scientific-literature-review" to listOf("Review Scientist"),
        "com.hereliesaz.haive.search-visibility" to listOf("Search Strategist"),
        "com.hereliesaz.haive.sop-process-engineering" to listOf("Process Engineer"),
        "com.hereliesaz.haive.structured-hiring" to listOf("Selection Designer"),
        "com.hereliesaz.haive.supplier-due-diligence" to listOf("Supplier Analyst"),
        "com.hereliesaz.haive.survey-research" to listOf("Survey Methodologist"),
        "com.hereliesaz.haive.technical-documentation" to listOf("Technical Writer"),
        "com.hereliesaz.haive.ux-research-to-design" to listOf("UX Researcher", "Interaction Designer"),
        "com.hereliesaz.haive.vendor-evaluation" to listOf("Vendor Evaluator"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
