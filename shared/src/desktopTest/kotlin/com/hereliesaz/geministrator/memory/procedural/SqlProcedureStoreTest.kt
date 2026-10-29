package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryLineage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlProcedureStoreTest {
    private fun directory(): File = createTempDirectory("aive-procedures").toFile()

    private fun definition(workflow: String = "root", version: Int = 1): ProcedureDefinition =
        ProcedureDefinition(
            ref = ProcedureReference(workflow, "android.verify-build", version),
            kind = ProcedureKind.Reflex,
            summary = "Verify an Android build",
            contentDigest = "sha256:" + "d".repeat(64),
            sourceEpisodeIds = setOf(MemoryEpisodeId("source")),
            parameters = emptyList(),
            steps = listOf(ProcedureStep("check", "TestRunner", "gradle-test", ProcedureSideEffect.ReadOnly)),
            createdAtEpochMillis = 10,
        )

    private fun trace(workflow: String = "root", phase: ProcedureTracePhase = ProcedureTracePhase.Dispatch) =
        ProcedureTrace(
            workflowId = workflow,
            projectId = "project",
            taskRunId = "task-1",
            taskDefinitionId = "task",
            attempt = 1,
            phase = phase,
            executorKind = "TestRunner",
            outcome = ProcedureTraceOutcome.Completed,
            recordedAtEpochMillis = 12,
        )

    @Test
    fun candidateEvidenceAndStructuredTraceSurviveARealDatabaseReopen() = runBlocking {
        val directory = directory()
        val first = desktopSqlProcedureStore("root", directory)
        val proposal = definition()
        val reported = ProcedureEvidence(
            id = "evidence-1",
            procedure = proposal.ref,
            sourceEpisodeId = MemoryEpisodeId("source"),
            taskRunId = "task-1",
            receiptId = "receipt-1",
            outcome = ProcedureEvidenceOutcome.Verified,
            recordedAtEpochMillis = 11,
        )
        assertTrue(first.commit(0, ProcedureMutation(definitionsToAdd = listOf(proposal))))
        assertTrue(first.commit(1, ProcedureMutation(evidenceToAdd = listOf(reported), tracesToAdd = listOf(trace()))))
        val reopened = desktopSqlProcedureStore("root", directory)
        assertEquals(first.read(), reopened.read())
        assertEquals(2L, reopened.read().revision)
        assertEquals(1, reopened.read().traces.size)
        assertFalse(ProcedureCandidate(reopened.read().definitions.single(), false).executable)
    }

    @Test
    fun staleWriterAndDuplicateReceiptsAreRejectedWithoutPartialWrites() = runBlocking {
        val dir = directory()
        val first = desktopSqlProcedureStore("root", dir)
        val second = desktopSqlProcedureStore("root", dir)
        assertTrue(first.commit(0, ProcedureMutation(definitionsToAdd = listOf(definition()))))
        assertFalse(second.commit(0, ProcedureMutation(tracesToAdd = listOf(trace()))))
        assertTrue(second.commit(1, ProcedureMutation(tracesToAdd = listOf(trace()))))
        assertFailsWith<IllegalArgumentException> {
            first.commit(2, ProcedureMutation(
                definitionsToAdd = listOf(definition(version = 2)),
                tracesToAdd = listOf(trace()),
            ))
        }
        assertEquals(2L, desktopSqlProcedureStore("root", dir).read().revision)
        assertEquals(1, first.read().definitions.size)
        assertEquals(1, first.read().traces.size)
    }

    @Test
    fun workflowOwnershipPreventsCrossBankWritesAndVisibility() = runBlocking {
        val dir = directory()
        val lineage = MemoryLineage()
        lineage.registerWorkflow("root", "a", atEpochMillis = 1L)
        lineage.registerWorkflow("child", "a", listOf("root"), atEpochMillis = 2L)
        lineage.registerWorkflow("other", "b", atEpochMillis = 3L)
        val catalog = desktopSqlProcedureCatalog(lineage, dir)
        assertTrue(catalog.own("root").commit(0, ProcedureMutation(definitionsToAdd = listOf(definition()))))
        assertFailsWith<IllegalArgumentException> {
            catalog.own("child").commit(0, ProcedureMutation(definitionsToAdd = listOf(definition())))
        }
        val restarted = desktopSqlProcedureCatalog(lineage, dir)
        assertEquals(1, restarted.visible("child").size)
        assertTrue(restarted.visible("child").single().inherited)
        assertTrue(restarted.visible("other").isEmpty())
        assertTrue(restarted.own("other").read().definitions.isEmpty())
    }

    @Test
    fun clearingBankPersistsAndRejectsPreClearRevision() = runBlocking {
        val dir = directory()
        val store = desktopSqlProcedureStore("root", dir)
        assertTrue(store.commit(0L, ProcedureMutation(
            definitionsToAdd = listOf(definition()),
            tracesToAdd = listOf(trace()),
        )))
        assertFalse(store.clear(0L))
        assertTrue(store.clear(1L))
        val reopened = desktopSqlProcedureStore("root", dir)
        assertEquals(2L, reopened.read().revision)
        assertTrue(reopened.read().definitions.isEmpty())
        assertTrue(reopened.read().traces.isEmpty())
        assertFalse(reopened.commit(1L, ProcedureMutation(tracesToAdd = listOf(trace()))))
        assertTrue(reopened.commit(2L, ProcedureMutation(tracesToAdd = listOf(trace()))))
        assertEquals(1, desktopSqlProcedureStore("root", dir).read().traces.size)
    }

    @Test
    fun invalidTraceIsNotRecordedAndDoesNotBumpRevision() = runBlocking {
        val dir = directory()
        val store = desktopSqlProcedureStore("root", dir)
        assertFailsWith<IllegalArgumentException> {
            store.commit(0, ProcedureMutation(tracesToAdd = listOf(trace(workflow = "foreign"))))
        }
        assertEquals(0L, desktopSqlProcedureStore("root", dir).read().revision)
        assertTrue(store.read().traces.isEmpty())
    }
}
