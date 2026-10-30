package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.InMemoryMemoryStore
import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryConsolidationQueue
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.MemorySessionEnvelope
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcedureControllerLifecycleTest {
    @Test
    fun controllerImportAndIndividualForgettingRetainContextualAuthority() = runBlocking<Unit> {
        val episodes = InMemoryMemoryStore()
        val lineage = MemoryLineage()
        lineage.registerWorkflow("workflow", "project", atEpochMillis = 1L)
        val procedures = ProcedureBankCatalog(lineage)
        val source = MemoryConsolidationQueue(episodes).enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "session",
                projectId = "project",
                workflowRunId = "workflow",
                userPrompt = "The tests passed",
                parts = emptyList(),
                closedAtEpochMillis = 10L,
            ),
        ).episodeId
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("workflow" to episodes)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            lineage = lineage,
            procedureCatalog = procedures,
            nowEpochMillis = { 11L },
        )
        controller.selectBank("workflow")
        val artifact = ProcedureArtifact.fromSource("workflow", "test-only script")
        val def = ProcedureDefinition(
            ref = ProcedureReference("workflow", "check-build", 1),
            kind = ProcedureKind.Reflex,
            summary = "Check build result",
            contentDigest = artifact.digest,
            sourceEpisodeIds = setOf(source),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("check", "TestRunner", "test", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 11L,
        )
        assertTrue(procedures.own("workflow").commit(0, ProcedureMutation(
            definitionsToAdd = listOf(def),
            artifactsToAdd = listOf(artifact),
        )))
        val exported = controller.exportProceduresJson()
        assertTrue(procedures.own("workflow").clear(1))
        controller.importProceduresJson(exported)
        assertEquals(1, procedures.own("workflow").read().definitions.size)
        assertEquals(1, procedures.own("workflow").read().artifacts.size)
        controller.forgetEpisode(source)
        assertTrue(procedures.own("workflow").read().definitions.isEmpty())
        assertTrue(procedures.own("workflow").read().artifacts.isEmpty())
        assertTrue(episodes.read().episodes.isEmpty())
        assertFailsWith<IllegalArgumentException> { controller.importProceduresJson(exported) }
    }

    @Test
    fun forgettingAncestorPhysicallyErasesDescendantProcedures() = runBlocking {
        val rootMemories = InMemoryMemoryStore()
        val lineage = MemoryLineage()
        lineage.registerWorkflow("root", "project", atEpochMillis = 1L)
        lineage.registerWorkflow("child", "project", parents = listOf("root"), atEpochMillis = 2L)
        val catalog = ProcedureBankCatalog(lineage)
        val episodeId = MemoryConsolidationQueue(rootMemories).enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "root-session",
                projectId = "project",
                workflowRunId = "root",
                userPrompt = "Check root build",
                parts = emptyList(),
                closedAtEpochMillis = 3L,
            ),
        ).episodeId
        val artifact = ProcedureArtifact.fromSource("child", "reflex inherited from root context")
        val candidate = ProcedureDefinition(
            ref = ProcedureReference("child", "root-check", 1),
            kind = ProcedureKind.Reflex,
            summary = "Reusable root check",
            contentDigest = artifact.digest,
            sourceEpisodeIds = setOf(episodeId),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("check", "TestRunner", "test", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 3L,
        )
        assertTrue(catalog.own("child").commit(0, ProcedureMutation(
            definitionsToAdd = listOf(candidate),
            artifactsToAdd = listOf(artifact),
        )))
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("root" to rootMemories)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            lineage = lineage,
            procedureCatalog = catalog,
        )
        controller.selectBank("root")
        controller.forgetEpisode(episodeId)
        assertTrue(catalog.own("child").read().definitions.isEmpty())
        assertTrue(catalog.own("child").read().artifacts.isEmpty())
    }
}
