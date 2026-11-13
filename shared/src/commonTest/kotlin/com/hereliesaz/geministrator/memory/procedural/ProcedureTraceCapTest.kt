package com.hereliesaz.geministrator.memory.procedural

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcedureTraceCapTest {
    private fun trace(n: Int) = ProcedureTrace(
        workflowId = "w", projectId = "p", taskRunId = "run-$n", taskDefinitionId = "t", attempt = 1,
        phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = n.toLong(),
    )

    @Test
    fun belowTheCapNothingIsDropped() {
        val traces = (1..10).map(::trace)
        assertEquals(traces, InMemoryProcedureStore.capTraces(traces))
    }

    @Test
    fun overTheCapTheOldestAreDropped() {
        val over = InMemoryProcedureStore.MAX_TRACES + 3
        val kept = InMemoryProcedureStore.capTraces((1..over).map(::trace))
        assertEquals(InMemoryProcedureStore.MAX_TRACES, kept.size)
        assertEquals(4L, kept.first().recordedAtEpochMillis)
        assertEquals(over.toLong(), kept.last().recordedAtEpochMillis)
    }

    @Test
    fun aCommitPastTheCapKeepsTheNewestReceipts() = runTest {
        val store = InMemoryProcedureStore("w")
        val first = (1..InMemoryProcedureStore.MAX_TRACES).map(::trace)
        assertTrue(store.commit(0L, ProcedureMutation(tracesToAdd = first)))
        val extra = trace(InMemoryProcedureStore.MAX_TRACES + 1)
        assertTrue(store.commit(1L, ProcedureMutation(tracesToAdd = listOf(extra))))
        val traces = store.read().traces
        assertEquals(InMemoryProcedureStore.MAX_TRACES, traces.size)
        assertEquals(extra.receiptKey, traces.last().receiptKey)
        assertTrue(traces.none { it.taskRunId == "run-1" })
    }
}
