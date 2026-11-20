package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.workflow.RunAmend
import com.hereliesaz.geministrator.workflow.RunAmendment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Amending a run that has started: added steps get their run state, started steps are never touched, nothing bypasses an approval. */
class RunAmendTest {
    private val definition = qDefinition(qTask("a"), qTask("b"))
    private val run = qRun(qTaskRun("a", TaskRunStatus.Running), qTaskRun("b", TaskRunStatus.Blocked))

    private fun withBlocker(templateId: String, byKeeper: Boolean): BlockerAssignment =
        Blockers.assignStep(definition, Blocker("b1", "A comparison table", templateId), byKeeper = byKeeper)

    @Test
    fun anAddedStepGetsItsRunStateAndStartedStepsKeepTheirs() {
        val assigned = assertIs<BlockerAssignment.Assigned>(withBlocker("sheet-table", byKeeper = false))
        val result = assertIs<RunAmendment.Amended>(RunAmend.amend(definition, run, assigned.definition.tasks, emptyList(), 10L))
        val stepId = TaskDefinitionId(assigned.blocker.stepId.orEmpty())
        assertEquals(assigned.definition.tasks, result.definition.tasks)
        assertEquals(setOf(stepId), result.addedTaskIds)
        assertEquals(TaskRunStatus.Running, result.run.taskRuns.getValue(TaskDefinitionId("a")).status)
        assertEquals(WorkflowRunStatus.Running, result.run.status)
        assertEquals(assigned.definition, result.run.definitionSnapshot)
        assertTrue(result.run.taskRuns.containsKey(stepId))
        if (result.run.taskRuns.getValue(stepId).status == TaskRunStatus.Ready) {
            assertTrue(result.events.any { it is TaskBecameReady && it.taskDefinitionId == stepId })
        }
    }

    @Test
    fun aStartedOrFinishedRunIsNotChangedAndNothingIsTakenAway() {
        val assigned = assertIs<BlockerAssignment.Assigned>(withBlocker("sheet-table", byKeeper = false))
        val tasks = assigned.definition.tasks
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed), tasks, emptyList(), 10L))
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, run, tasks.filter { it.id.value != "b" }, emptyList(), 10L))
        val changedRunning = tasks.map { if (it.id.value == "a") it.copy(objective = "Something else") else it }
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, run, changedRunning, emptyList(), 10L))
        // A step that has not started may only be given more to wait for: its objective, like every other field, stays as it was.
        val changedWaiting = tasks.map { if (it.id.value == "b") it.copy(objective = "Something else") else it }
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, run, changedWaiting, emptyList(), 10L))
        assertIs<RunAmendment.Amended>(RunAmend.amend(definition, run, tasks, emptyList(), 10L))
    }

    @Test
    fun anInjectedStepNeverLosesAnApprovalOrBypassesOne() {
        val effectful = assertIs<BlockerAssignment.Assigned>(withBlocker("change-connected-source", byKeeper = true))
        val made = effectful.definition.tasks.last()
        val noGate = effectful.definition.tasks.map { if (it.id == made.id) it.copy(approvalPolicy = com.hereliesaz.geministrator.domain.ApprovalPolicy.None) else it }
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, run, noGate, emptyList(), 10L))
    }

    @Test
    fun aSwarmThatFailsValidationIsRefused() {
        val assigned = assertIs<BlockerAssignment.Assigned>(withBlocker("sheet-table", byKeeper = false))
        val cyclic = assigned.definition.tasks.map { if (it.id.value == "b") it.copy(dependsOn = it.dependsOn + it.id) else it }
        assertIs<RunAmendment.Refused>(RunAmend.amend(definition, run, cyclic, emptyList(), 10L))
    }

    @Test
    fun theQueenNeverAssignsAnEffectfulTemplate() {
        assertIs<BlockerAssignment.Rejected>(withBlocker("change-connected-source", byKeeper = false))
    }
}
