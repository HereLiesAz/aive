package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryMutationApprovalGateTest {
    private val build = TaskDefinitionId("build")
    private val approve = TaskDefinitionId("approve")
    private val skipped = TaskDefinitionId("skipped")
    private val push = TaskDefinitionId("push")

    private fun agent(id: TaskDefinitionId, dependsOn: Set<TaskDefinitionId> = emptySet(), condition: TaskCondition = TaskCondition.Always) =
        TaskDefinition(
            id = id,
            name = id.value,
            objective = id.value,
            roleId = null,
            executor = TaskExecutor.RoleAgent(RoleDefinitionId("engineer")),
            dependsOn = dependsOn,
            condition = condition,
        )

    private fun approval(id: TaskDefinitionId, dependsOn: Set<TaskDefinitionId>, condition: TaskCondition = TaskCondition.Always) =
        TaskDefinition(
            id = id,
            name = id.value,
            objective = id.value,
            roleId = null,
            executor = TaskExecutor.HumanApproval(),
            dependsOn = dependsOn,
            condition = condition,
        )

    private fun pushTask(dependsOn: Set<TaskDefinitionId>) = TaskDefinition(
        id = push,
        name = "Push",
        objective = "Push",
        roleId = null,
        executor = TaskExecutor.RepositoryOperation("push"),
        dependsOn = dependsOn,
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("gate"),
        name = "Gate",
        tasks = tasks.toList(),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun WorkflowRun.with(id: TaskDefinitionId, status: TaskRunStatus) =
        copy(taskRuns = taskRuns + (id to taskRuns.getValue(id).copy(status = status)))

    private fun create(definition: WorkflowDefinition) = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        objective = "test",
        nowEpochMillis = 1L,
        taskRunIdFactory = { TaskRunId(it.value) },
    )

    @Test
    fun validatorDoesNotCountAConditionalApprovalAsTheGate() {
        val errors = WorkflowGraphValidator.validate(
            definition(
                agent(build),
                approval(approve, dependsOn = setOf(build), condition = TaskCondition.OnFailure(build)),
                pushTask(dependsOn = setOf(approve, build)),
            ),
        )

        assertTrue(WorkflowValidationError.MissingRepositoryMutationApproval(push) in errors, "$errors")
    }

    @Test
    fun mutationWhoseApprovalWasSkippedNeverBecomesReady() {
        // Valid graph: the approval is unconditional, but every branch into it is skipped at runtime.
        val gated = definition(
            agent(build),
            agent(skipped, dependsOn = setOf(build), condition = TaskCondition.OnFailure(build)),
            approval(approve, dependsOn = setOf(skipped)),
            pushTask(dependsOn = setOf(approve, build)),
        )
        assertEquals(emptyList(), WorkflowGraphValidator.validate(gated))

        // Each refresh settles one level; the runtime refreshes every cycle.
        var refreshed = create(gated).with(build, TaskRunStatus.Completed)
        repeat(3) { refreshed = WorkflowRunFactory.refreshReadiness(gated, refreshed, 2L + it) }

        assertEquals(TaskRunStatus.Cancelled, refreshed.taskRuns.getValue(skipped).status)
        assertEquals(TaskRunStatus.Cancelled, refreshed.taskRuns.getValue(approve).status)
        val pushRun = refreshed.taskRuns.getValue(push)
        assertEquals(TaskRunStatus.Cancelled, pushRun.status)
        assertEquals(WorkflowRunFactory.APPROVAL_NOT_GRANTED, pushRun.blockingReason?.code)
    }

    @Test
    fun mutationRunsOnceItsApprovalIsGranted() {
        val gated = definition(
            agent(build),
            approval(approve, dependsOn = setOf(build)),
            pushTask(dependsOn = setOf(approve)),
        )
        val run = create(gated).with(build, TaskRunStatus.Completed).with(approve, TaskRunStatus.Completed)

        val refreshed = WorkflowRunFactory.refreshReadiness(gated, run, 2L)

        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(push).status)
    }
}
