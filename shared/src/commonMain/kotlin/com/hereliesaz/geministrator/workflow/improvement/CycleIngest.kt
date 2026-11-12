package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * Reads finished self-improvement cycles into the keeper's ledger (docs/architecture/SELF_IMPROVEMENT.md §3a). A cycle
 * is read when it finishes while the app is showing it, and again, from the run history, each time the app starts, so
 * a cycle that finished while the app was closed, or while another run was on screen, is not lost. Reading is
 * idempotent (the ledger takes a cycle once, by its run id) and changes nothing but the list of proposals.
 */
object CycleIngest {
    /** At most this many finished cycles are read back from history in one go, newest first, so a long history is not a flood of old proposals. */
    const val MAX_BACKFILL = 10

    fun isFinishedCycle(run: WorkflowRun): Boolean =
        run.workflowDefinitionId.value == SelfImprovementLoop.HIVE_ID && run.status == WorkflowRunStatus.Completed

    /** The proposal text a cycle's last step wrote. */
    fun finalText(run: WorkflowRun): String =
        run.taskRuns[TaskDefinitionId(SelfImprovementLoop.FINAL_STEP)]?.artifacts?.mapNotNull { it.textContent }?.joinToString("\n").orEmpty()

    /** Reads [run] into [store], stamped with when it finished. True when it was new. */
    suspend fun ingest(store: ImprovementStore, run: WorkflowRun): Boolean {
        val (cycle, proposals) = readCycle(run.id.value, finalText(run), run.updatedAtEpochMillis)
        return store.ingest(cycle, proposals)
    }

    /**
     * Reads every finished cycle in [history] that the ledger has not seen, oldest first, and returns the runs it added.
     * A finished cycle whose last step left no stored text is skipped, not recorded as a cycle that proposed nothing.
     */
    suspend fun backfill(store: ImprovementStore, history: List<WorkflowRun>): List<WorkflowRun> {
        val state = store.state()
        return history
            .filter { isFinishedCycle(it) && !state.hasIngested(it.id.value) && finalText(it).isNotBlank() }
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(MAX_BACKFILL)
            .sortedBy { it.updatedAtEpochMillis }
            .filter { ingest(store, it) }
    }
}
