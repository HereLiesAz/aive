package com.hereliesaz.geministrator.memory.procedural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitLogTest {
    private val op = "github-action:ci.yml"

    private fun receipt(
        run: String,
        at: Long,
        phase: ProcedureTracePhase = ProcedureTracePhase.Reconcile,
        outcome: ProcedureTraceOutcome = ProcedureTraceOutcome.Completed,
        operation: String? = op,
        environment: String? = null,
        verification: ProcedureTraceVerification = ProcedureTraceVerification.Unknown,
        flag: ProcedureTraceFlag? = null,
        deliberation: ProcedureTraceDeliberation? = null,
    ) = ProcedureTrace(
        workflowId = run, projectId = "p", taskRunId = "tr-$run-$at", taskDefinitionId = "build", attempt = 1,
        phase = phase, executorKind = "GitHubAction", outcome = outcome, recordedAtEpochMillis = at,
        operation = operation, environment = environment, verification = verification, flag = flag,
        deliberation = deliberation,
    )

    @Test
    fun everyKindOfReceiptReadsAsOneLineNewestFirst() {
        val log = HabitLog.entries(
            listOf(
                receipt("r1", 1_000, ProcedureTracePhase.Dispatch, ProcedureTraceOutcome.Running, environment = "acme/web"),
                receipt("r1", 2_000, environment = "acme/web"),
                receipt("r1", 3_000, ProcedureTracePhase.Verification, verification = ProcedureTraceVerification.Rejected),
                receipt(
                    "r1", 4_000, ProcedureTracePhase.Flag, ProcedureTraceOutcome.Running,
                    flag = ProcedureTraceFlag(ProcedureFlagSource.Handled, "qa", "It assumed a clean tree.", tainted = true),
                ),
                receipt(
                    "r1", 5_000, ProcedureTracePhase.Deliberation, ProcedureTraceOutcome.Running,
                    deliberation = ProcedureTraceDeliberation(
                        ProcedureDeliberationOutcome.Overrides, "keeper", "Wrong stage.", "Use v2.", "github-action:ci-v2.yml",
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                HabitLogEntry.Kind.Decision, HabitLogEntry.Kind.Flag, HabitLogEntry.Kind.Reviewed,
                HabitLogEntry.Kind.Finished, HabitLogEntry.Kind.Started,
            ),
            log.map { it.kind },
        )
        val text = HabitLog.render(log)
        assertTrue("1970-01-01 00:00 UTC  decision $op  overrides by keeper: \"Wrong stage.\" Instead: \"Use v2.\" (github-action:ci-v2.yml)" in text, text)
        assertTrue("handled flag by qa: \"It assumed a clean tree.\" (written with untrusted content in view)" in text, text)
        assertTrue("a reviewer turned it back" in text, text)
        assertTrue("completed in acme/web, run r1" in text, text)
        assertTrue("started in acme/web, run r1" in text, text)
    }

    @Test
    fun aFilterALimitDuplicatesAndUnnamedReceiptsAreHandled() {
        val other = "deployment:production"
        val receipts = listOf(
            receipt("r1", 1_000), receipt("r1", 1_000), receipt("r2", 2_000),
            receipt("r3", 3_000, operation = other), receipt("r4", 4_000, operation = null),
        )
        assertEquals(listOf(2_000L, 1_000L), HabitLog.entries(receipts, op).map { it.atEpochMillis })
        assertEquals(setOf(op, other), HabitLog.entries(receipts).map { it.operation }.toSet())
        assertEquals(1, HabitLog.entries(receipts, limit = 1).size)
        assertEquals("", HabitLog.render(emptyList()))
    }
}
