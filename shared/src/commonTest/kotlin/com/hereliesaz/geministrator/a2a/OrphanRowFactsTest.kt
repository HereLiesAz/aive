package com.hereliesaz.geministrator.a2a

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The honesty facts on one directory row (vocabulary-and-first-run, Part 5): what the row may and may not claim. */
class OrphanRowFactsTest {
    private fun entry(cardUrl: String, declaredSource: String? = null, hosting: String? = null, connected: String? = null) =
        AgentDirectoryEntry(
            cardUrl = cardUrl,
            name = "Meteorology Agent",
            description = "Rain",
            skills = listOf("Atmospheric science"),
            source = "SearchA2A · semantic",
            connectedProviderId = connected,
            declaredSource = declaredSource,
            declaredHosting = hosting,
        )

    @Test
    fun theHostIsTheEndpointHostnameWithNoPortAndNoGuessedVendor() {
        val facts = orphanRowFacts(entry("https://API.Example.org:8443/.well-known/agent-card.json"))
        assertEquals("api.example.org", facts.host)
        assertNull(facts.declaredHosting)
    }

    @Test
    fun withoutBackingFieldsTheSourceIsNotStatedAndNoVendorIsInferred() {
        // 'openai.example.org' must never be read as 'Source: OpenAI'.
        val facts = orphanRowFacts(entry("https://openai.example.org/card"))
        assertNull(facts.declaredSource)
        assertEquals("openai.example.org", facts.host)
    }

    @Test
    fun aDeclaredSourceAndHostingAreCarriedAsDeclared() {
        val facts = orphanRowFacts(entry("https://x.example.org/card", declaredSource = "OpenAI", hosting = "Cloudflare"))
        assertEquals("OpenAI", facts.declaredSource)
        assertEquals("Cloudflare", facts.declaredHosting)
        // The declared host replaces the hostname on the Host line only when the directory supplies it.
        assertEquals("Cloudflare", facts.hostLine)
    }

    @Test
    fun everyOrphanIsUnverifiedAndLivesElsewhereUnlessItIsOnThisComputer() {
        assertTrue(orphanRowFacts(entry("https://x.example.org/card")).livesElsewhere)
        assertFalse(orphanRowFacts(entry("http://localhost:9000/card")).livesElsewhere)
        assertFalse(orphanRowFacts(entry("http://127.0.0.1:9000/card")).livesElsewhere)
        assertFalse(orphanRowFacts(entry("http://[::1]:9000/card")).livesElsewhere)
    }

    @Test
    fun theRegistryNoteAppearsOnlyWhenThereIsData() {
        val tested = entry("https://x.example.org/card").copy(registryReportsTaskTested = true)
        assertTrue(orphanRowFacts(tested).registryTriedATest)
        assertFalse(orphanRowFacts(entry("https://x.example.org/card")).registryTriedATest)
    }
}
