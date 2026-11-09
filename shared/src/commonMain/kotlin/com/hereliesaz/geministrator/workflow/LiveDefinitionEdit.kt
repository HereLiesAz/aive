package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/** The outcome of offering a saved hive to its live run. */
internal sealed interface LiveEditMerge {
    /** No creature the run has not started took any change; the run keeps its definition. */
    data object Nothing : LiveEditMerge

    /** [definition] is the run's definition with [taken] creatures' edits merged in. */
    data class Merged(val definition: WorkflowDefinition, val taken: Int) : LiveEditMerge

    /** The merge would have broken the hive's graph; nothing was applied. [reason] says why. */
    data class Rejected(val reason: String) : LiveEditMerge
}

/**
 * Offers a saved hive to its live run (docs/Keepers-Room.md, Pause, change, resume). Only a creature the
 * run has not started takes anything, and only its job and acceptance criteria: its place in the graph,
 * its condition, its role and executor, its approval, retry and escalation policies and its placement
 * stay as the run has them and change from the next run. The merged hive is checked as a graph before it
 * is offered back, so an edit can never leave the run with creatures that wait on each other.
 */
internal object LiveDefinitionEdit {
    /** A creature the run has not started: only these can take an edit, because the engine reads a task when it starts it. */
    private val OPEN = setOf(TaskRunStatus.Created, TaskRunStatus.Blocked, TaskRunStatus.Ready, TaskRunStatus.Retrying)

    fun merge(
        current: WorkflowDefinition,
        edited: WorkflowDefinition,
        run: WorkflowRun,
        take: (old: TaskDefinition, new: TaskDefinition) -> TaskDefinition = ::jobAndCriteria,
    ): LiveEditMerge {
        var taken = 0
        val tasks = current.tasks.map { old ->
            val new = edited.tasks.firstOrNull { it.id == old.id }
            if (new != null && run.taskRuns[old.id]?.status in OPEN) {
                val merged = take(old, new)
                if (merged != old) taken += 1
                merged
            } else {
                old
            }
        }
        if (taken == 0) return LiveEditMerge.Nothing
        val merged = current.copy(tasks = tasks)
        val before = WorkflowGraphValidator.validate(current).toSet()
        val introduced = WorkflowGraphValidator.validate(merged).filterNot { it in before }
        if (introduced.isNotEmpty()) {
            return LiveEditMerge.Rejected(
                "The change to the running hive was not applied; it stays as it was until the next run. " +
                    introduced.joinToString(" ") { it.humanReadable() },
            )
        }
        return LiveEditMerge.Merged(merged, taken)
    }

    private fun jobAndCriteria(old: TaskDefinition, new: TaskDefinition): TaskDefinition =
        old.copy(objective = new.objective, acceptanceCriteria = new.acceptanceCriteria)
}
