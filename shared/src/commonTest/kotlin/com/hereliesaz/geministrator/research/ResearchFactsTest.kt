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
    fun onlyCompleteMatchingExcerptIsReturnedAsSourceBacked() {
        val facts = parseResearchFacts("FACT: README.md | SOURCE: 1", leads)
        assertEquals(listOf("[untrusted reference | exact excerpt from linked repository | Linked repository docs] README.md"), facts)
        assertTrue(parseResearchFacts("FACT: The app uses SQLite. | SOURCE: 1", leads).isEmpty())
        val negative = listOf(ResearchLead("Manual", ResearchSource.Repository, "local", "It is not safe to execute.", true))
        assertTrue(parseResearchFacts("FACT: safe to execute. | SOURCE: 1", negative).isEmpty())
        assertTrue(parseResearchFacts("FACT: It is not safe to execute. | SOURCE: 1", negative).single().contains("not safe"))
    }

    @Test
    fun noSourceOrAnUnlistedSourceMeansNoFact() {
        val reply = "FACT: No source given.\nFACT: Wrong number. | SOURCE: 9\nFACT: Zero. | SOURCE: 0\nFACT: Not a number. | SOURCE: two\nJust prose."
        assertTrue(parseResearchFacts(reply, leads).isEmpty())
    }

    @Test
    fun repeatedStatementSourcePairsCountOnceAndAtMostThreeClaimsAreKept() {
        val reply = "FACT: Same. | SOURCE: 1\nFACT: Same. | SOURCE: 1\nFACT: Same. | SOURCE: 2\nFACT: Third. | SOURCE: 1\nFACT: Fourth. | SOURCE: 2"
        val claims = parseResearchClaims(reply, leads)
        assertEquals(listOf(1, 2, 1), claims.map { it.sourceNumber })
        assertEquals(listOf("Same.", "Same.", "Third."), claims.map { it.statement })
        assertTrue(claims.none { it.matchesSourceText })
    }

    @Test
    fun aLongFactIsCappedAndLinksAreNamedLink() {
        val claim = parseResearchClaims("FACT: ${"x".repeat(500)} see https://example.com/a | SOURCE: 2", leads).single()
        assertTrue(claim.statement.length <= 200)
        assertTrue("https://" !in claim.statement)
        assertTrue(!claim.matchesSourceText)
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
    fun matchingExcerptsComeFirstAndUnsupportedModelClaimsDoNotReachDispute() = runTest {
        val found = research(QueenModel { "FACT: README.md | SOURCE: 1" }).findings("Which database does the app use?", "k")!!
        assertEquals(2, found.size)
        assertTrue(found.first().startsWith("[untrusted reference | exact excerpt from"))
        assertTrue(found.last().startsWith("[untrusted reference | linked repository"))
        val unsupported = research(QueenModel { "FACT: It is SQLite. | SOURCE: 1" })
            .findings("Which database does the app use?", "k")!!
        assertEquals(1, unsupported.size)
        assertTrue(unsupported.none { "SQLite" in it })
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
