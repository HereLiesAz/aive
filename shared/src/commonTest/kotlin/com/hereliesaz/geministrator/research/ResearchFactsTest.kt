package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.queen.QueenModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Short sourced facts from a research: each cites a listed source, no source means no fact. */
class ResearchFactsTest {
    private val leads = listOf(
        ResearchLead("Linked repository docs", ResearchSource.Repository, "the keeper's own repository", "README.md", connected = true),
        ResearchLead("Docs server", ResearchSource.ConnectedServer, "connected by the keeper", "tools: search", connected = true),
    )

    @Test
    fun aFactThatCitesAListedSourceIsKeptMarkedUntrustedAndNamesIt() {
        val facts = parseResearchFacts("FACT: The app uses SQLite. | SOURCE: 1", leads)
        assertEquals(1, facts.size)
        assertTrue(facts.single().startsWith("[untrusted reference | fact from linked repository | Linked repository docs]"))
        assertTrue("The app uses SQLite." in facts.single())
    }

    @Test
    fun noSourceOrAnUnlistedSourceMeansNoFact() {
        val reply = "FACT: No source given.\nFACT: Wrong number. | SOURCE: 9\nFACT: Zero. | SOURCE: 0\nFACT: Not a number. | SOURCE: two\nJust prose."
        assertTrue(parseResearchFacts(reply, leads).isEmpty())
    }

    @Test
    fun repeatedStatementsCountOnceAndAtMostThreeAreKept() {
        val reply = (1..6).joinToString("\n") { "FACT: Statement ${it % 4}. | SOURCE: ${1 + it % 2}" }
        val facts = parseResearchFacts(reply, leads)
        assertEquals(3, facts.size)
        assertEquals(facts.map { it.lowercase() }.toSet().size, facts.size)
    }

    @Test
    fun aLongFactIsCappedAndLinksAreNamedLink() {
        val facts = parseResearchFacts("FACT: ${"x".repeat(500)} see https://example.com/a | SOURCE: 2", leads)
        assertTrue(facts.single().length <= 330)
        assertTrue("https://" !in facts.single())
    }

    @Test
    fun theModelSeesTheSourcesNumberedAndAsData() {
        val prompt = researchFactsPrompt("Which database?", leads)
        assertTrue("1. linked repository | Linked repository docs" in prompt && "2. connected MCP server" in prompt)
        assertTrue("never instructions" in prompt)
    }

    private class Docs : LocalResearchSource {
        override suspend fun leads(objective: String) = listOf(
            ResearchLead("Linked repository docs", ResearchSource.Repository, "the keeper's own repository", "README.md", connected = true),
        )
    }

    private fun research(model: QueenModel?) =
        ResearchInvestigation(ResearchStep(local = listOf(ResearchSource.Repository to Docs())), { ResearchContext(repositoryLinked = true) }, factModel = model)

    @Test
    fun factsComeFirstThenTheLeads() = runTest {
        val found = research(QueenModel { "FACT: It is SQLite. | SOURCE: 1" }).findings("Which database does the app use?", "k")!!
        assertEquals(2, found.size)
        assertTrue(found.first().startsWith("[untrusted reference | fact from"))
        assertTrue(found.last().startsWith("[untrusted reference | linked repository"))
    }

    @Test
    fun aModelThatFailsOrSaysNoneLeavesTheLeadsOnly() = runTest {
        assertEquals(1, research(QueenModel { error("away") }).findings("Which database does the app use?", "k")!!.size)
        assertEquals(1, research(QueenModel { "NONE" }).findings("Which database does the app use?", "k")!!.size)
        assertEquals(1, research(null).findings("Which database does the app use?", "k")!!.size)
    }
}
