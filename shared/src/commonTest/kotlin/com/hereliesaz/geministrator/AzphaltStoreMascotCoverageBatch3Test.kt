package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AzphaltStoreMascotCoverageBatch3Test {
    @Test
    fun currentStoreWorkflowAndRolePersonasHaveCharacters() {
        val packages = mapOf(
        "com.hereliesaz.aive.long-form-book-production" to listOf("Book Editor", "Manuscript Continuity Editor"),
        "com.hereliesaz.aive.meeting-to-execution" to listOf("Execution Secretary"),
        "com.hereliesaz.aive.music-release" to listOf("Release Producer"),
        "com.hereliesaz.aive.negotiation-preparation" to listOf("Negotiation Planner"),
        "com.hereliesaz.aive.osint-corporate-ownership" to listOf("OSINT Corporate Investigator", "OSINT Entity Resolution Auditor"),
        "com.hereliesaz.aive.osint-event-chronology" to listOf("OSINT Timeline Analyst", "OSINT Chronology Auditor"),
        "com.hereliesaz.aive.osint-geospatial-change" to listOf("OSINT Earth Observation Analyst", "OSINT Change Auditor"),
        "com.hereliesaz.aive.osint-maritime-aviation" to listOf("OSINT Mobility Analyst", "OSINT Track Auditor"),
        "com.hereliesaz.aive.osint-media-geolocation" to listOf("OSINT Geolocation Analyst", "OSINT Location Skeptic"),
        "com.hereliesaz.aive.osint-passive-web-infrastructure" to listOf("OSINT Infrastructure Analyst", "OSINT Attribution Skeptic"),
        "com.hereliesaz.aive.osint-sanctions-procurement" to listOf("OSINT Risk Researcher", "OSINT Screening Auditor"),
        "com.hereliesaz.aive.osint-source-verification" to listOf("OSINT Provenance Analyst", "OSINT Verification Editor"),
        "com.hereliesaz.aive.paperplanes-stratified-reconstruction" to listOf("PaperPlanes Scene Director", "PaperPlanes Geometry Engineer", "PaperPlanes Occlusion Architect", "PaperPlanes Layer Engineer", "PaperPlanes Visual QC"),
        "com.hereliesaz.aive.podcast-production" to listOf("Podcast Producer", "Podcast Fact Editor"),
        "com.hereliesaz.aive.presentation-production" to listOf("Presentation Editor"),
        )
        packages.forEach { (packageId, roleNames) ->
            assertTrue(roleNames.isNotEmpty(), "$packageId exposes no role/persona")
            roleNames.forEach { roleName ->
                assertNotEquals(NodeCreatureRoleKind.Generic, classifyNodeCreatureRole(roleName), "$packageId / $roleName fell back to the generic mascot")
            }
        }
    }
}
