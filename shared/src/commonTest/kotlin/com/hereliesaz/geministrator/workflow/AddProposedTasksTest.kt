package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.memory.procedural.FormedSequence
import com.hereliesaz.geministrator.memory.procedural.HabitFormation
import com.hereliesaz.geministrator.memory.procedural.HabitInvocation
import com.hereliesaz.geministrator.memory.procedural.HabitSequences
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceOutcome
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddProposedTasksTest {
    private val service = WorkflowCompositionService(InMemoryWorkflowPersistence())
    private val a = "github-action:ci.yml"
    private val b = "deployment:production"

    private fun step(run: String, at: Long, operation: String) = ProcedureTrace(
        workflowId = run, projectId = "p", taskRunId = "tr-$run-$operation", taskDefinitionId = operation, attempt = 1,
        phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = at, operation = operation,
    )

    private val receipts = listOf("r1", "r2", "r3").flatMap { listOf(step(it, 10, a), step(it, 11, b)) }

    private val base = WorkflowDefinition(
        id = WorkflowDefinitionId("base"),
        name = "Base",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("prepare"), name = "Prepare", objective = "Prepare",
                roleId = null, executor = TaskExecutor.HumanApproval(),
            ),
        ),
    )

    private fun proposed(prefix: String = "habit"): List<TaskDefinition> {
        val sequence: FormedSequence = HabitSequences.form(receipts).single()
        val result = HabitInvocation.proposeSequence(sequence, HabitFormation.form(receipts), prefix)
        return assertIs<HabitInvocation.SequenceResult.Proposed>(result).tasks
    }

    @Test
    fun proposedStepsJoinTheWorkflowAfterTheTasksChosenAndStillAskForApproval() {
        val merged = service.addProposedTasks(base, proposed(), connectFrom = setOf(TaskDefinitionId("prepare")))
        assertEquals(listOf("prepare", "habit-1", "habit-2"), merged.tasks.map { it.id.value })
        assertEquals(setOf(TaskDefinitionId("prepare")), merged.tasks[1].dependsOn)
        assertEquals(setOf(TaskDefinitionId("habit-1")), merged.tasks[2].dependsOn)
        assertTrue(merged.tasks.drop(1).all { it.approvalPolicy == ApprovalPolicy.HumanApproval })
    }

    @Test
    fun anIdAlreadyInUseARepeatedIdOrAnUndefinedTaskToFollowIsRefused() {
        assertFailsWith<IllegalArgumentException> { service.addProposedTasks(base, proposed() + base.tasks) }
        assertFailsWith<IllegalArgumentException> { service.addProposedTasks(base, proposed() + proposed()) }
        assertFailsWith<IllegalArgumentException> {
            service.addProposedTasks(base, proposed(), connectFrom = setOf(TaskDefinitionId("nothing")))
        }
        assertFailsWith<IllegalArgumentException> { service.addProposedTasks(base, emptyList()) }
    }
}
