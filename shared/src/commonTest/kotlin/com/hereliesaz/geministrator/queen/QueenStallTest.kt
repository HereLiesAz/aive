package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.TaskRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueenStallTest {
    private val definition = qDefinition(qTask("a"), qTask("b"))
    private fun idleRun(vararg statuses: TaskRunStatus) = qRun(*statuses.mapIndexed { i, s -> qTaskRun("ab"[i].toString(), s) }.toTypedArray())

    @Test
    fun theFirstSightingIsKeptAndProgressResetsIt() {
        val run = idleRun(TaskRunStatus.Blocked, TaskRunStatus.Blocked)
        val first = observeProgress(emptyList(), definition, run, 1_000L)
        assertEquals(1_000L, first.single().firstSeenAtEpochMillis)
        assertEquals(first, observeProgress(first, definition, run, 9_000L), "Unchanged progress keeps the first time")
        val moved = observeProgress(first, definition, idleRun(TaskRunStatus.Completed, TaskRunStatus.Blocked), 9_000L)
        assertEquals(9_000L, moved.single().firstSeenAtEpochMillis)
    }

    @Test
    fun aRunThatStoodStillLongEnoughWithNothingWorkingIsStalled() {
        val run = idleRun(TaskRunStatus.Blocked, TaskRunStatus.Blocked)
        val seen = observeProgress(emptyList(), definition, run, 0L)
        assertNull(stalledMinutes(definition, run, seen, STALL_MILLIS - 1))
        assertEquals(10, stalledMinutes(definition, run, seen, STALL_MILLIS))
    }

    @Test
    fun aWorkingCreatureOrAWaitingKeeperOrARestIsNeverAStall() {
        fun stalled(run: com.hereliesaz.geministrator.domain.WorkflowRun) =
            stalledMinutes(definition, run, observeProgress(emptyList(), definition, run, 0L), STALL_MILLIS * 3)
        assertNull(stalled(idleRun(TaskRunStatus.Running, TaskRunStatus.Blocked)))
        assertNull(stalled(idleRun(TaskRunStatus.AwaitingApproval, TaskRunStatus.Blocked)))
        assertNull(stalled(idleRun(TaskRunStatus.Completed, TaskRunStatus.Completed).copy(status = com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed)))
    }

    @Test
    fun stalledAndRepeatedBlockBecomeSignalsAndTheQueenSaysThem() {
        val blocked = BlockingReason("no-source", "x")
        val run = qRun(
            qTaskRun("a", TaskRunStatus.Blocked).copy(blockingReason = blocked),
            qTaskRun("b", TaskRunStatus.Blocked).copy(blockingReason = blocked),
        )
        val signals = queenSignals(definition, run, emptyList(), stalledForMinutes = 12)
        assertTrue(signals.any { it is QueenSignal.RepeatedBlock && it.steps == 2 && it.code == "no-source" })
        assertTrue(signals.any { it is QueenSignal.Stalled && it.minutes == 12 })
        assertEquals(QueenVoice.WatchRepeatedBlock, watchRelay(definition, run, signals)?.voice)
        val onlyStall = signals.filterIsInstance<QueenSignal.Stalled>()
        assertEquals(QueenVoice.WatchStalled, watchRelay(definition, run, onlyStall)?.voice)
    }
}
