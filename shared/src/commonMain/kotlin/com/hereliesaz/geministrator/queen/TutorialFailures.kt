package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.tour.TEACHING_WORKFLOW_ID_PREFIX
import com.hereliesaz.geministrator.workflow.OPTIONAL_STEP_SKIPPED

/** The most failure lines one run shows at once: one plain line each, not a list of everything that went wrong. */
private const val MAX_FAILURE_LINES = 2

/**
 * What the queen says in Messages when a step could not do its work and nothing else already says so (pure, from the run):
 * - an optional creature that failed for good and was set aside ([OPTIONAL_STEP_SKIPPED]): one line naming the step, leading to its creature;
 * - in a teaching run, the first lesson creature that failed or was escalated: one line naming the lesson and its tries, leading to its
 *   creature's Needs, where Retry and its Source are.
 * Each is one plain line with the existing place to fix it as its link; no text from the run is in the sentence (the name is a bounded slot).
 */
internal fun tutorialFailureNotes(definition: WorkflowDefinition, run: WorkflowRun): List<QueenNote> {
    val notes = mutableListOf<QueenNote>()
    val teaching = definition.id.value.startsWith("$TEACHING_WORKFLOW_ID_PREFIX-")
    definition.tasks.forEachIndexed { index, task ->
        val taskRun = run.taskRuns[task.id] ?: return@forEachIndexed
        val number = (index + 1).toString()
        val skipped = taskRun.status == TaskRunStatus.Failed && taskRun.blockingReason?.code == OPTIONAL_STEP_SKIPPED
        val lessonFailed = teaching && !task.optional &&
            (taskRun.status == TaskRunStatus.Failed || taskRun.status == TaskRunStatus.Escalated)
        when {
            skipped -> notes += QueenNote(
                queenNoteId(run.id.value, "step-skipped", task.id.value),
                QueenVoice.StepSkipped,
                listOf(number, queenSlot(task.name)),
                QueenLink.Creature(task.id.value, CreatureTab.Work),
            )
            lessonFailed && notes.none { it.voice == QueenVoice.TutorialLessonFailed } -> notes += QueenNote(
                queenNoteId(run.id.value, "lesson-failed", task.id.value, taskRun.attempt.toString()),
                QueenVoice.TutorialLessonFailed,
                listOf(number, taskRun.attempt.toString()),
                QueenLink.Creature(task.id.value, CreatureTab.Needs),
            )
        }
    }
    return notes.take(MAX_FAILURE_LINES)
}
