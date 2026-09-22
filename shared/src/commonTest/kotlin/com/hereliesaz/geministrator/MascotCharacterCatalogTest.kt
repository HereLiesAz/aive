package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MascotCharacterCatalogTest {
    @Test
    fun everyCurrentAzphaltPersonaOwnsADistinctCharacter() {
        val expectedRoles = setOf(
            "AI Evaluation Scientist",
            "Accessibility Auditor",
            "Agent Red Teamer",
            "Agent Security Architect",
            "Book Editor",
            "Brand Strategist",
            "Campaign Strategist",
            "Competitive Analyst",
            "Content Strategist",
            "Continuity Supervisor",
            "Contract Reviewer",
            "Corporate Investigator",
            "Crisis Communicator",
            "Curriculum Architect",
            "Data Investigator",
            "Data Story Editor",
            "Decision Analyst",
            "Deep Research Lead",
            "Deep Researcher",
            "Documentary Producer",
            "Event Producer",
            "Evidence Skeptic",
            "Evidence Synthesis Lead",
            "Execution Secretary",
            "Exhibition Producer",
            "Experimental Methodologist",
            "Failure Diagnostician",
            "Feature Engineer",
            "Feature Reviewer",
            "Film Continuity Supervisor",
            "Film Showrunner",
            "Financial Model Auditor",
            "Game Director",
            "Geolocation Analyst",
            "Grant Strategist",
            "Graphic Novel Continuity Editor",
            "Graphic Novel Director",
            "Identity Director",
            "Incident Commander",
            "Incident Investigator",
            "Interaction Designer",
            "Investigative Reporter",
            "Knowledge System Evaluator",
            "Launch Strategist",
            "Learning Coach",
            "Localization Lead",
            "Manuscript Continuity Editor",
            "Methods Reviewer",
            "Negotiation Planner",
            "OSINT Attribution Skeptic",
            "OSINT Change Auditor",
            "OSINT Chronology Auditor",
            "OSINT Corporate Investigator",
            "OSINT Earth Observation Analyst",
            "OSINT Entity Resolution Auditor",
            "OSINT Geolocation Analyst",
            "OSINT Infrastructure Analyst",
            "OSINT Location Skeptic",
            "OSINT Mobility Analyst",
            "OSINT Provenance Analyst",
            "OSINT Risk Researcher",
            "OSINT Screening Auditor",
            "OSINT Timeline Analyst",
            "OSINT Track Auditor",
            "OSINT Verification Editor",
            "OSINT Verifier",
            "Operations Designer",
            "PaperPlanes Geometry Engineer",
            "PaperPlanes Layer Engineer",
            "PaperPlanes Occlusion Architect",
            "PaperPlanes Scene Director",
            "PaperPlanes Visual QC",
            "Playtest Analyst",
            "Podcast Fact Editor",
            "Podcast Producer",
            "Presentation Editor",
            "Pricing Researcher",
            "Privacy Risk Analyst",
            "Process Engineer",
            "Proposal Lead",
            "Qualitative Synthesist",
            "Release Producer",
            "Repair Verifier",
            "Review Scientist",
            "Scenario Planner",
            "Search Strategist",
            "Selection Designer",
            "Statistical Skeptic",
            "Supplier Analyst",
            "Support Escalation Lead",
            "Survey Methodologist",
            "Technical Writer",
            "UX Researcher",
            "Vendor Evaluator",
        )

        val characters = MascotCharacterCatalog.explicitCharacters
        assertEquals(expectedRoles, characters.map { it.roleLabel }.toSet())
        assertEquals(expectedRoles.size, characters.size)
        assertEquals(characters.size, characters.map { it.id }.toSet().size)
        assertEquals(characters.size, characters.map { it.phenotypeIndex }.toSet().size)
        assertEquals(
            characters.size,
            characters.map { it.designFingerprint }.toSet().size,
            "Store roles must remain one-character-per-role rather than collapsing into mascot families",
        )

        expectedRoles.forEach { roleName ->
            assertNotNull(
                MascotCharacterCatalog.explicitForRole(roleName),
                "$roleName has no explicit Azphalt node creature",
            )
        }
    }

    @Test
    fun unrelatedCustomRolesReceiveTheirOwnStableCharacterInsteadOfGenericVisualIdentity() {
        val first = assertNotNull(MascotCharacterCatalog.forRole("Ceramics Kiln Wrangler"))
        val again = assertNotNull(MascotCharacterCatalog.forRole("Ceramics Kiln Wrangler"))
        val other = assertNotNull(MascotCharacterCatalog.forRole("Archive Cartographer"))

        assertEquals(first, again)
        kotlin.test.assertNotEquals(first.id, other.id)
        kotlin.test.assertNotEquals(first.phenotypeIndex, other.phenotypeIndex)
    }
}
