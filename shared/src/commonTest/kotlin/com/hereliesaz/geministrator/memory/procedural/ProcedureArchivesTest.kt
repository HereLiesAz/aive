package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcedureArchivesTest {
    private suspend fun sample(
        workflow: String = "workflow",
        episode: MemoryEpisodeId = MemoryEpisodeId("episode"),
    ): Pair<ProcedureArtifact, ProcedureDefinition> {
        val artifact = ProcedureArtifact.fromSource(workflow, "echo procedural source")
        val definition = ProcedureDefinition(
            ref = ProcedureReference(workflow, "verify-build", 1),
            kind = ProcedureKind.Reflex,
            summary = "Verify build with known test runner",
            contentDigest = artifact.digest,
            sourceEpisodeIds = setOf(episode),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("test", "TestRunner", "verified-task", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 42L,
        )
        return artifact to definition
    }

    @Test
    fun sha256DigestMatchesKnownVectorAndTamperingFails() = runBlocking<Unit> {
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ProcedureArtifact.fingerprint("abc"),
        )
        val artifact = ProcedureArtifact.fromSource("workflow", "safe")
        artifact.verify()
        assertFailsWith<IllegalArgumentException> {
            artifact.copy(source = "tampered").verify()
        }
    }

    @Test
    fun exportImportRequiresRealSourceEvidenceAndExactWorkflow() = runBlocking<Unit> {
        val (artifact, definition) = sample()
        val source = definition.sourceEpisodeIds.single()
        val snapshot = ProcedureSnapshot(
            revision = 8,
            definitions = listOf(definition),
            artifacts = listOf(artifact),
        )
        val exported = ProcedureArchives.export("workflow", snapshot)
        val decoded = ProcedureArchives.decode(exported, "workflow", setOf(source))
        assertEquals(snapshot, decoded)
        assertFailsWith<IllegalArgumentException> {
            ProcedureArchives.decode(exported, "other", setOf(source))
        }
        assertFailsWith<IllegalArgumentException> {
            ProcedureArchives.decode(exported, "workflow", emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            ProcedureArchives.decode(
                ProcedureArchives.export("workflow", snapshot.copy(artifacts = emptyList())),
                "workflow",
                setOf(source),
            )
        }
        val tampered = snapshot.copy(artifacts = listOf(artifact.copy(source = "altered")))
        assertFailsWith<IllegalArgumentException> {
            ProcedureArchives.decode(ProcedureArchives.export("workflow", tampered), "workflow", setOf(source))
        }
    }

    @Test
    fun forgetEpisodePhysicallyRemovesDerivedProceduresEvidenceTracesAndUnusedSources() = runBlocking {
        val store = InMemoryProcedureStore("workflow")
        val (firstSource, first) = sample()
        val (secondSource, second) = sample(
            episode = MemoryEpisodeId("other-source"),
        )
        val independent = second.copy(
            ref = ProcedureReference("workflow", "other-build", 1),
            contentDigest = ProcedureArtifact.fromSource("workflow", "different script").digest,
        )
        val artifact = ProcedureArtifact.fromSource("workflow", "different script")
        val trace = ProcedureTrace(
            workflowId = "workflow",
            projectId = "project",
            taskRunId = "task",
            taskDefinitionId = "task",
            attempt = 1,
            phase = ProcedureTracePhase.Reconcile,
            executorKind = "TestRunner",
            outcome = ProcedureTraceOutcome.Completed,
            recordedAtEpochMillis = 42,
        )
        val evidence = ProcedureEvidence(
            id = "first-evidence",
            procedure = first.ref,
            sourceEpisodeId = MemoryEpisodeId("episode"),
            taskRunId = "task",
            receiptId = "first-receipt",
            outcome = ProcedureEvidenceOutcome.Completed,
            recordedAtEpochMillis = 42,
        )
        assertTrue(store.commit(0, ProcedureMutation(
            definitionsToAdd = listOf(first, independent),
            evidenceToAdd = listOf(evidence),
            tracesToAdd = listOf(trace),
            artifactsToAdd = listOf(firstSource, artifact),
        )))
        assertTrue(store.forgetEpisode(1, MemoryEpisodeId("episode"), setOf(trace.receiptKey)))
        val after = store.read()
        assertEquals(listOf(independent.ref), after.definitions.map { it.ref })
        assertTrue(after.evidence.isEmpty())
        assertTrue(after.traces.isEmpty())
        assertEquals(listOf(artifact.digest), after.artifacts.map { it.digest })
        assertFalse(store.forgetEpisode(1, MemoryEpisodeId("episode")))
        assertEquals(2L, after.revision)
    }
}
