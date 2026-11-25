package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.memory.MemoryBankRequest
import com.hereliesaz.geministrator.memory.MemorySourceKind
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
    @Test
    fun factModelReceivesScopedTagsButLookupDoesNot() = runTest {
        val queries = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val staged = mutableListOf<MemoryBankRequest>()
        val released = mutableListOf<String>()
        val provider = object : com.hereliesaz.geministrator.memory.MemoryPromptContextProvider {
            override suspend fun recallFor(request: com.hereliesaz.geministrator.providers.AgentTaskRequest,
                queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan) =
                error("Direct chat must not retrieve passages")
            override suspend fun directChatTags(workflowIds: List<String>, roleId: String, text: String): List<String> {
                assertEquals(listOf("authorized-run"), workflowIds)
                assertEquals(com.hereliesaz.geministrator.domain.BuiltInRoles.Orchestrator.id.value, roleId)
                return listOf("#sqlite")
            }
            override suspend fun stageRunDirectReply(workflowId: String, request: MemoryBankRequest) {
                assertEquals("authorized-run", workflowId)
                assertTrue(released.isEmpty())
                staged += request
            }
            override suspend fun bankRunDirectReplies(workflowId: String, sourceIds: List<String>) {
                assertEquals("authorized-run", workflowId)
                assertEquals(staged.map { it.sourceSessionId }, sourceIds)
                released += sourceIds
            }
        }
        val source = LocalResearchSource { objective -> queries += objective; leads }
        val investigation = ResearchInvestigation(
            ResearchStep(local = listOf(ResearchSource.Repository to source)),
            { ResearchContext(repositoryLinked = true) },
            memoryProvider = provider,
        ).withFactModel(QueenModel { prompts += it; "FACT: Unsupported. | SOURCE: 9" })
        val found = investigation.findings("Which database does the app use?", "external-key",
            memoryRunIds = listOf("authorized-run"))!!
        assertTrue(prompts.single().contains("#sqlite"))
        assertTrue(queries.none { "#sqlite" in it })
        assertTrue(found.none { "Unsupported" in it || "#sqlite" in it })
        assertTrue(staged.isEmpty(), "Read access alone is not capture authority")
        val captured = investigation.findings("Which database does the app use?", "external-key",
            memoryRunIds = listOf("authorized-run"), captureWorkflowId = "authorized-run")!!
        assertTrue(captured.none { "Unsupported" in it || "#sqlite" in it })
        val note = staged.single()
        assertEquals(MemorySourceKind.AgentNote, note.sourceKind)
        assertEquals("authorized-run", note.scope.workflowRunId)
        assertEquals("FACT: Unsupported. | SOURCE: 9", note.text)
        assertTrue("#sqlite" !in note.text && "external-key" !in note.sourceSessionId)
        assertEquals(listOf(note.sourceSessionId), released)
    }
}
