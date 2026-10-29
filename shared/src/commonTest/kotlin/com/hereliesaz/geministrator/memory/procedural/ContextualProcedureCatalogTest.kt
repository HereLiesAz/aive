package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.MemoryStoreMutation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextualProcedureCatalogTest {
    @Test
    fun missingOrForgottenContextualSourceDoesNotSurfaceCandidate() = runTest {
        val lineage = MemoryLineage()
        lineage.registerWorkflow("root", "project-a", atEpochMillis = 1L)
        lineage.registerWorkflow("child", "project-a", listOf("root"), atEpochMillis = 2L)
        val memories = MemoryBanks.inMemory()
        val catalog = ProcedureBankCatalog(lineage)
        val contextual = ContextualProcedureCatalog(catalog, memories, lineage)
        val source = MemoryEpisode(
            id = MemoryEpisodeId("source-root"),
            sourceSessionId = "session-one",
            workflowRunId = "root",
            userPrompt = "Verify an Android build",
            chunks = emptyList(),
            createdAtEpochMillis = 1L,
        )
        val procedure = ProcedureDefinition(
            ref = ProcedureReference("root", "android.verify-build", 1),
            kind = ProcedureKind.Reflex,
            summary = "Verify an Android build",
            contentDigest = "sha256:" + "b".repeat(64),
            sourceEpisodeIds = setOf(source.id),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("check", "TestRunner", "gradle-test", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 2L,
        )
        assertTrue(catalog.own("root").commit(0, ProcedureMutation(definitionsToAdd = listOf(procedure))))
        assertTrue(contextual.sourceBackedCandidates("child").isEmpty())

        val rootBank = memories.store("root")
        assertTrue(rootBank.commit(0, MemoryStoreMutation(episodesToAdd = listOf(source))))
        val found = contextual.sourceBackedCandidates("child")
        assertEquals(listOf(procedure.ref), found.map { it.definition.ref })
        assertTrue(found.single().inherited)
        assertTrue(found.none { it.executable })

        // Contextual forgetting immediately removes the proposal from verified-source lookup.
        rootBank.replace(MemorySnapshot())
        assertTrue(contextual.sourceBackedCandidates("child").isEmpty())
    }

    @Test
    fun otherProjectEpisodeCannotBackfillMissingProvenance() = runTest {
        val lineage = MemoryLineage()
        lineage.registerWorkflow("root", "project-a", atEpochMillis = 1L)
        lineage.registerWorkflow("unrelated", "project-b", atEpochMillis = 1L)
        val memories = MemoryBanks.inMemory()
        val catalog = ProcedureBankCatalog(lineage)
        val sourceId = MemoryEpisodeId("shared-id")
        val proposal = ProcedureDefinition(
            ref = ProcedureReference("root", "check", 1),
            kind = ProcedureKind.Reflex,
            summary = "Check repository",
            contentDigest = "sha256:" + "c".repeat(64),
            sourceEpisodeIds = setOf(sourceId),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("check", "TestRunner", "test", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 2L,
        )
        assertTrue(catalog.own("root").commit(0, ProcedureMutation(definitionsToAdd = listOf(proposal))))
        assertTrue(memories.store("unrelated").commit(0, MemoryStoreMutation(episodesToAdd = listOf(
            MemoryEpisode(sourceId, "foreign-session", workflowRunId = "unrelated", userPrompt = "Test",
                chunks = emptyList(), createdAtEpochMillis = 1L),
        ))))
        assertTrue(ContextualProcedureCatalog(catalog, memories, lineage).sourceBackedCandidates("root").isEmpty())
    }
}
