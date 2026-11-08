package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskExecutor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcedureTraceOperationTest {
    private fun trace(
        operation: String? = null,
        slots: List<ProcedureTraceSlot> = emptyList(),
        verification: ProcedureTraceVerification = ProcedureTraceVerification.Unknown,
    ) = ProcedureTrace(
        workflowId = "w",
        projectId = "p",
        taskRunId = "r",
        taskDefinitionId = "t",
        attempt = 1,
        phase = ProcedureTracePhase.Dispatch,
        executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed,
        recordedAtEpochMillis = 1L,
        operation = operation,
        slots = slots,
        verification = verification,
    )

    @Test
    fun anExecutorIsNamedOnlyByAPlainConfiguredName() {
        val of = ProcedureTraceRedactor::operationOf
        assertEquals("github-action:ci.yml", of(TaskExecutor.GitHubAction("ci.yml")))
        assertEquals("github-action:workflows/ci.yml", of(TaskExecutor.GitHubAction("workflows/ci.yml")))
        assertEquals("deployment:production", of(TaskExecutor.Deployment("production")))
        assertEquals("repository:merge", of(TaskExecutor.RepositoryOperation("merge")))
        assertEquals("service:slack:post", of(TaskExecutor.ExternalService("slack", "post")))
        assertEquals("service:slack", of(TaskExecutor.ExternalService("slack")))
        assertEquals(
            "github-action:ci.yml",
            of(TaskExecutor.Distributed(TaskExecutor.GitHubAction("ci.yml"))),
        )
        // No name, or not a plain one: nothing is kept.
        assertNull(of(TaskExecutor.TestRunner("run the suite now")))
        assertNull(of(TaskExecutor.Script(ScriptLanguage.JavaScript, "console.log(1)", ScriptRunner.LocalSandbox)))
        assertNull(of(TaskExecutor.GitHubAction("a workflow with spaces.yml")))
        assertNull(of(TaskExecutor.GitHubAction("../escape.yml")))
        assertNull(of(TaskExecutor.Deployment("prod; rm -rf /")))
    }

    @Test
    fun onlyTypedShortValuesUnderPlainNamesBecomeSlots() {
        val action = TaskExecutor.GitHubAction(
            workflow = "ci.yml",
            ref = "main",
            contextInput = "aive_context",
            inputs = mapOf(
                "build-type" to "debug",
                "retries" to "3",
                "verbose" to "true",
                "aive_context" to "a whole blob of context text",
                "message" to "fix the bug now please",
                "api_key" to "plain",
                "build_id" to "A1b2C3d4E5f6G7h8I9j0K1",
                "path" to "../../etc/passwd",
            ),
        )
        val slots = ProcedureTraceRedactor.slotsOf(action)
        assertEquals(
            listOf(
                ProcedureTraceSlot("build_type", ProcedureParameterType.Identifier, "debug"),
                ProcedureTraceSlot("ref", ProcedureParameterType.Identifier, "main"),
                ProcedureTraceSlot("retries", ProcedureParameterType.Integer, "3"),
                ProcedureTraceSlot("verbose", ProcedureParameterType.Boolean, "true"),
            ),
            slots,
        )
        assertFalse(slots.any { it.name in setOf("message", "api_key", "build_id", "aive_context") })
        assertTrue(ProcedureTraceRedactor.slotsOf(TaskExecutor.Deployment("production")).isEmpty())
    }

    @Test
    fun aSlotRefusesWhatItIsNotAllowedToHold() {
        assertFailsWith<IllegalArgumentException> { ProcedureTraceSlot("Bad", ProcedureParameterType.Identifier, "x") }
        assertFailsWith<IllegalArgumentException> { ProcedureTraceSlot("a", ProcedureParameterType.Text, "x") }
        assertFailsWith<IllegalArgumentException> { ProcedureTraceSlot("a", ProcedureParameterType.Path, "/x") }
        assertFailsWith<IllegalArgumentException> { ProcedureTraceSlot("a", ProcedureParameterType.Integer, "1.5") }
        assertFailsWith<IllegalArgumentException> { ProcedureTraceSlot("a", ProcedureParameterType.Identifier, "two words") }
        assertFailsWith<IllegalArgumentException> {
            ProcedureTraceSlot("a", ProcedureParameterType.Identifier, "A1b2C3d4E5f6G7h8I9j0K1")
        }
        assertFailsWith<IllegalArgumentException> { trace(operation = "Not A Name") }
        assertFailsWith<IllegalArgumentException> {
            trace(slots = List(ProcedureTraceSlot.MAX_SLOTS + 1) { ProcedureTraceSlot("s$it", ProcedureParameterType.Integer, "1") })
        }
        assertFailsWith<IllegalArgumentException> {
            trace(slots = listOf(
                ProcedureTraceSlot("a", ProcedureParameterType.Integer, "1"),
                ProcedureTraceSlot("a", ProcedureParameterType.Integer, "2"),
            ))
        }
    }

    @Test
    fun theBankedSummaryCarriesTheFrameAndFillersAndIsUnchangedWithoutThem() {
        assertEquals(
            "System executor GitHubAction dispatch reported completed. " +
                "Verification: unknown. Artifact count: 0; kinds: .",
            trace().contextualSummary(),
        )
        val summary = trace(
            operation = "github-action:ci.yml",
            slots = listOf(
                ProcedureTraceSlot("ref", ProcedureParameterType.Identifier, "main"),
                ProcedureTraceSlot("retries", ProcedureParameterType.Integer, "3"),
            ),
            verification = ProcedureTraceVerification.Verified,
        ).contextualSummary()
        assertTrue("Operation: github-action:ci.yml." in summary)
        assertTrue("Slots: ref=main; retries=3." in summary)
        assertTrue("Verification: verified." in summary)
    }

    @Test
    fun receiptsStoredBeforeTheseFieldsExistStillDecode() {
        val old = """{"workflowId":"w","projectId":"p","taskRunId":"r","taskDefinitionId":"t","attempt":1,
            "phase":"Dispatch","executorKind":"GitHubAction","outcome":"Completed","recordedAtEpochMillis":1}"""
        val decoded = Json.decodeFromString(ProcedureTrace.serializer(), old)
        assertNull(decoded.operation)
        assertEquals(emptyList(), decoded.slots)
        assertEquals(ProcedureTraceVerification.Unknown, decoded.verification)
        assertEquals("w:r:1:Dispatch:Completed", decoded.receiptKey)
    }
}
