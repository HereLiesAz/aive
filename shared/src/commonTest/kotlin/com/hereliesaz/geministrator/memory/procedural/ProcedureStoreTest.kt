package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryLineage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcedureStoreTest {
    private fun definition(
        workflow: String = "root",
        name: String = "android.verify-build",
        version: Int = 1,
        kind: ProcedureKind = ProcedureKind.Reflex,
    ): ProcedureDefinition {
        val first = ProcedureStep(
            id = "build",
            executorKind = "TestRunner",
            operationId = "gradle-test",
            sideEffect = ProcedureSideEffect.ReadOnly,
            parameterBindings = mapOf("module" to "module"),
        )
        val steps = if (kind == ProcedureKind.Reflex) listOf(first) else listOf(
            first,
            ProcedureStep(
                id = "check",
                executorKind = "TestRunner",
                operationId = "inspect-test-result",
                sideEffect = ProcedureSideEffect.ReadOnly,
                dependsOn = setOf("build"),
            ),
        )
        return ProcedureDefinition(
            ref = ProcedureReference(workflow, name, version),
            kind = kind,
            summary = "Verify an Android build",
            contentDigest = "sha256:" + "a".repeat(64),
            sourceEpisodeIds = setOf(MemoryEpisodeId("episode-1")),
            parameters = listOf(ProcedureParameter("module", ProcedureParameterType.Identifier)),
            steps = steps,
            verificationChecks = listOf("test-exit-zero"),
            createdAtEpochMillis = 42L,
        )
    }

    private fun evidence(procedure: ProcedureReference, id: String = "receipt-1") = ProcedureEvidence(
        id = id,
        procedure = procedure,
        sourceEpisodeId = MemoryEpisodeId("episode-1"),
        taskRunId = "run-1",
        receiptId = id,
        outcome = ProcedureEvidenceOutcome.Verified,
        recordedAtEpochMillis = 43L,
    )

    @Test
    fun candidateDefinitionsRemainNonExecutableEvenWithClaimedVerification() = runTest {
        val store = InMemoryProcedureStore("root")
        val def = definition()
        assertTrue(store.commit(0L, ProcedureMutation(definitionsToAdd = listOf(def))))
        assertTrue(store.commit(1L, ProcedureMutation(evidenceToAdd = listOf(evidence(def.ref)))))
        val candidate = ProcedureCandidate(store.read().definitions.single(), inherited = false)
        assertFalse(candidate.executable)
        assertEquals(ProcedureEvidenceOutcome.Verified, store.read().evidence.single().outcome)
        assertEquals(2L, store.read().revision)
    }

    @Test
    fun staleRevisionAndDuplicatesNeverOverwriteHistory() = runTest {
        val store = InMemoryProcedureStore("root")
        val first = definition()
        assertTrue(store.commit(0L, ProcedureMutation(definitionsToAdd = listOf(first))))
        assertFalse(store.commit(0L, ProcedureMutation(definitionsToAdd = listOf(definition(version = 2)))))
        assertFailsWith<IllegalArgumentException> {
            store.commit(1L, ProcedureMutation(definitionsToAdd = listOf(first)))
        }
        assertEquals(1L, store.read().revision)
        assertEquals(listOf(first.ref), store.read().definitions.map { it.ref })
        assertTrue(store.commit(1L, ProcedureMutation(definitionsToAdd = listOf(definition(version = 2)))))
        assertEquals(listOf(1, 2), store.read().definitions.map { it.ref.version })
    }

    @Test
    fun crossBankAndOrphanEvidenceAreRejectedBeforeCommit() = runTest {
        val root = InMemoryProcedureStore("root")
        val child = InMemoryProcedureStore("child")
        val def = definition()
        assertFailsWith<IllegalArgumentException> {
            child.commit(0L, ProcedureMutation(definitionsToAdd = listOf(def)))
        }
        assertFailsWith<IllegalArgumentException> {
            root.commit(0L, ProcedureMutation(evidenceToAdd = listOf(evidence(def.ref))))
        }
        assertEquals(0L, root.read().revision)
        assertEquals(0L, child.read().revision)
        assertTrue(root.commit(0L, ProcedureMutation(definitionsToAdd = listOf(def))))
        assertFailsWith<IllegalArgumentException> {
            root.commit(1L, ProcedureMutation(evidenceToAdd = listOf(evidence(
                ProcedureReference("child", "other", 1),
            ))))
        }
        assertEquals(1L, root.read().revision)
    }

    @Test
    fun duplicateReceiptIsRejectedEvenWithAnotherEvidenceId() = runTest {
        val store = InMemoryProcedureStore("root")
        val def = definition()
        assertTrue(store.commit(0L, ProcedureMutation(definitionsToAdd = listOf(def))))
        assertTrue(store.commit(1L, ProcedureMutation(evidenceToAdd = listOf(evidence(def.ref)))))
        assertFailsWith<IllegalArgumentException> {
            store.commit(2L, ProcedureMutation(evidenceToAdd = listOf(
                evidence(def.ref, id = "receipt-1").copy(id = "another-report"),
            )))
        }
        assertEquals(1, store.read().evidence.size)
    }

    @Test
    fun lineageReadDoesNotIncludeSiblingsOrOtherProjects() = runTest {
        val lineage = MemoryLineage()
        lineage.registerWorkflow("root", "project-a", atEpochMillis = 1L)
        lineage.registerWorkflow("child", "project-a", listOf("root"), atEpochMillis = 2L)
        lineage.registerWorkflow("sibling", "project-a", atEpochMillis = 3L)
        lineage.registerWorkflow("outside", "project-b", atEpochMillis = 4L)
        val catalog = ProcedureBankCatalog(lineage)
        val root = definition()
        val sibling = definition("sibling")
        val outside = definition("outside")
        assertTrue(catalog.own("root").commit(0L, ProcedureMutation(definitionsToAdd = listOf(root))))
        assertTrue(catalog.own("sibling").commit(0L, ProcedureMutation(definitionsToAdd = listOf(sibling))))
        assertTrue(catalog.own("outside").commit(0L, ProcedureMutation(definitionsToAdd = listOf(outside))))
        val visible = catalog.visible("child")
        assertEquals(listOf(root.ref), visible.map { it.definition.ref })
        assertTrue(visible.single().inherited)
        assertNotNull(catalog.lookup("child", root.ref))
        assertNull(catalog.lookup("child", sibling.ref))
        assertNull(catalog.lookup("child", outside.ref))
        assertFailsWith<IllegalArgumentException> {
            catalog.own("child").commit(0L, ProcedureMutation(evidenceToAdd = listOf(evidence(root.ref))))
        }
        assertEquals(1, catalog.visible("root").size)
    }

    @Test
    fun rejectsInvalidDigestCyclesAndReflexWithMultipleSteps() {
        val valid = definition()
        assertFailsWith<IllegalArgumentException> {
            valid.copy(contentDigest = "sha256:untrusted")
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(steps = valid.steps + valid.steps.single().copy(id = "second"))
        }
        val habit = definition(kind = ProcedureKind.Habit)
        assertEquals(2, habit.steps.size)
        assertFailsWith<IllegalArgumentException> {
            habit.copy(steps = habit.steps.reversed())
        }
        assertFailsWith<IllegalArgumentException> {
            habit.copy(steps = habit.steps.map { it.copy(dependsOn = setOf("check")) })
        }
    }

    @Test
    fun snapshotSerializesAndCanBeReconstructedWithoutPromoting() = runTest {
        val store = InMemoryProcedureStore("root")
        val def = definition(kind = ProcedureKind.Habit)
        assertTrue(store.commit(0L, ProcedureMutation(definitionsToAdd = listOf(def))))
        val json = Json.encodeToString(ProcedureSnapshot.serializer(), store.read())
        val decoded = Json.decodeFromString(ProcedureSnapshot.serializer(), json)
        val reopened = InMemoryProcedureStore("root", decoded)
        assertEquals(store.read(), reopened.read())
        assertFalse(ProcedureCandidate(reopened.read().definitions.single(), false).executable)
    }
}
