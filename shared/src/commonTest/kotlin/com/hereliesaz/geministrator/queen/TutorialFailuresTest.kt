package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.workflow.OPTIONAL_STEP_SKIPPED
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TutorialFailuresTest {
    private fun definition(idPrefix: String, optionalSecond: Boolean = false) = WorkflowDefinition(
        id = WorkflowDefinitionId("$idPrefix-1"),
        name = "Lessons",
        tasks = listOf(
            TaskDefinition(TaskDefinitionId("a"), "First", "Do a", BuiltInRoles.Worker.id),
            TaskDefinition(TaskDefinitionId("b"), "Second", "Do b", BuiltInRoles.Worker.id, optional = optionalSecond),
        ),
    )

    private fun run(def: WorkflowDefinition, status: Map<String, TaskRunStatus>, reason: Map<String, BlockingReason> = emptyMap()): WorkflowRun {
        val created = WorkflowRunFactory.create(
            definition = def,
            workflowRunId = WorkflowRunId("r"),
            projectId = ProjectId("p"),
            objective = "Teach me",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("r-${id.value}") },
        )
        return created.copy(
            taskRuns = created.taskRuns.mapValues { (id, taskRun) ->
                taskRun.copy(status = status[id.value] ?: taskRun.status, blockingReason = reason[id.value] ?: taskRun.blockingReason, attempt = 3)
            },
        )
    }

    @Test
    fun aTeachingRunsFailedLessonIsOnePlainLineLeadingToItsNeeds() {
        val def = definition("tutorial")
        val notes = tutorialFailureNotes(def, run(def, mapOf("b" to TaskRunStatus.Escalated)))

        assertEquals(1, notes.size)
        assertEquals(QueenVoice.TutorialLessonFailed, notes.single().voice)
        assertEquals(listOf("2", "3"), notes.single().args)
        assertEquals(QueenLink.Creature("b", CreatureTab.Needs), notes.single().link)
    }

    @Test
    fun aFailureInAnOrdinaryRunIsNotWordedAsALesson() {
        val def = definition("workflow")
        assertTrue(tutorialFailureNotes(def, run(def, mapOf("b" to TaskRunStatus.Escalated))).isEmpty())
    }

    @Test
    fun anOptionalStepSetAsideIsSaidInAnyRun() {
        val def = definition("workflow", optionalSecond = true)
        val skipped = run(def, mapOf("b" to TaskRunStatus.Failed), mapOf("b" to BlockingReason(OPTIONAL_STEP_SKIPPED, "no reply")))

        val notes = tutorialFailureNotes(def, skipped)

        assertEquals(listOf(QueenVoice.StepSkipped), notes.map { it.voice })
        assertEquals(QueenLink.Creature("b", CreatureTab.Work), notes.single().link)
    }

    @Test
    fun aHealthyRunSaysNothing() {
        val def = definition("tutorial")
        assertTrue(tutorialFailureNotes(def, run(def, mapOf("a" to TaskRunStatus.Completed))).isEmpty())
    }
}
