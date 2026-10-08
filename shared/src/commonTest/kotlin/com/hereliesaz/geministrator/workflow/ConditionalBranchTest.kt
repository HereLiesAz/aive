package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ConditionalBranchTest {

    private val mainTaskId = TaskDefinitionId("main")
    private val happyPathId = TaskDefinitionId("happy")
    private val failureHandlerId = TaskDefinitionId("on-failure")
    private val alwaysRunId = TaskDefinitionId("always")

    private val approvalExecutor = TaskExecutor.HumanApproval("Approve")

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("cond-def"),
        name = "Conditional workflow",
        tasks = listOf(
            TaskDefinition(mainTaskId, "Main", "Do the work", roleId = null, executor = approvalExecutor),
            TaskDefinition(
                id = happyPathId,
                name = "Happy path",
                objective = "Post-success work",
                roleId = null,
                executor = approvalExecutor,
                dependsOn = setOf(mainTaskId),
            ),
            TaskDefinition(
                id = failureHandlerId,
                name = "Failure handler",
                objective = "Handle failure",
                roleId = null,
                executor = approvalExecutor,
                dependsOn = setOf(mainTaskId),
                condition = TaskCondition.OnFailure(mainTaskId),
            ),
            TaskDefinition(
                id = alwaysRunId,
                name = "Always run",
                objective = "Cleanup",
                roleId = null,
                executor = approvalExecutor,
                dependsOn = setOf(mainTaskId),
                condition = TaskCondition.OnAnyOutcome(mainTaskId),
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    @Test
    fun happyPathBecomesReadyOnSuccess() {
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("proj"),
            objective = "test",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId(it.value) },
        )

        val withMainCompleted = run.copy(
            taskRuns = run.taskRuns + (mainTaskId to run.taskRuns.getValue(mainTaskId).copy(
                status = TaskRunStatus.Completed,
            )),
        )
        val refreshed = WorkflowRunFactory.refreshReadiness(definition, withMainCompleted, 2L)

        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(happyPathId).status)
        assertEquals(TaskRunStatus.Cancelled, refreshed.taskRuns.getValue(failureHandlerId).status)
        assertEquals("CONDITION_NOT_MET", refreshed.taskRuns.getValue(failureHandlerId).blockingReason?.code)
        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(alwaysRunId).status)
    }

    @Test
    fun failureHandlerBecomesReadyOnFailure() {
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("proj"),
            objective = "test",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId(it.value) },
        )

        val withMainFailed = run.copy(
            taskRuns = run.taskRuns + (mainTaskId to run.taskRuns.getValue(mainTaskId).copy(
                status = TaskRunStatus.Failed,
            )),
        )
        val refreshed = WorkflowRunFactory.refreshReadiness(definition, withMainFailed, 2L)

        // Happy path stays blocked — its Always condition is not satisfied
        assertEquals(TaskRunStatus.Blocked, refreshed.taskRuns.getValue(happyPathId).status)
        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(failureHandlerId).status)
        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(alwaysRunId).status)
    }

    @Test
    fun conditionOnlyEdgeWaitsForTargetBeforeResolving() {
        val targetId = TaskDefinitionId("target")
        val failureOnlyId = TaskDefinitionId("failure-only")
        val anyOutcomeId = TaskDefinitionId("any-outcome")
        val conditional = WorkflowDefinition(
            id = WorkflowDefinitionId("condition-only"),
            name = "Condition only",
            tasks = listOf(
                TaskDefinition(targetId, "Target", "Run target", roleId = null, executor = approvalExecutor),
                TaskDefinition(
                    id = failureOnlyId,
                    name = "Failure only",
                    objective = "Recover",
                    roleId = null,
                    executor = approvalExecutor,
                    condition = TaskCondition.OnFailure(targetId),
                ),
                TaskDefinition(
                    id = anyOutcomeId,
                    name = "Any outcome",
                    objective = "Cleanup",
                    roleId = null,
                    executor = approvalExecutor,
                    condition = TaskCondition.OnAnyOutcome(targetId),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val created = WorkflowRunFactory.create(
            definition = conditional,
            workflowRunId = WorkflowRunId("condition-only-run"),
            projectId = ProjectId("proj"),
            objective = "test",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId(it.value) },
        )

        val runningTarget = created.copy(
            taskRuns = created.taskRuns + (
                targetId to created.taskRuns.getValue(targetId).copy(status = TaskRunStatus.Running)
            ),
        )
        val whileRunning = WorkflowRunFactory.refreshReadiness(conditional, runningTarget, 2L)
        assertEquals(TaskRunStatus.Blocked, whileRunning.taskRuns.getValue(failureOnlyId).status)
        assertEquals(TaskRunStatus.Blocked, whileRunning.taskRuns.getValue(anyOutcomeId).status)

        val failedTarget = runningTarget.copy(
            taskRuns = runningTarget.taskRuns + (
                targetId to runningTarget.taskRuns.getValue(targetId).copy(status = TaskRunStatus.Failed)
            ),
        )
        val afterFailure = WorkflowRunFactory.refreshReadiness(conditional, failedTarget, 3L)
        assertEquals(TaskRunStatus.Ready, afterFailure.taskRuns.getValue(failureOnlyId).status)
        assertEquals(TaskRunStatus.Ready, afterFailure.taskRuns.getValue(anyOutcomeId).status)

        val completedTarget = runningTarget.copy(
            taskRuns = runningTarget.taskRuns + (
                targetId to runningTarget.taskRuns.getValue(targetId).copy(status = TaskRunStatus.Completed)
            ),
        )
        val afterSuccess = WorkflowRunFactory.refreshReadiness(conditional, completedTarget, 4L)
        assertEquals(TaskRunStatus.Cancelled, afterSuccess.taskRuns.getValue(failureOnlyId).status)
        assertEquals(TaskRunStatus.Ready, afterSuccess.taskRuns.getValue(anyOutcomeId).status)
    }

    @Test
    fun validatorRejectsMissingConditionTarget() {
        val badDef = WorkflowDefinition(
            id = WorkflowDefinitionId("bad"),
            name = "Bad",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("t"),
                    name = "T",
                    objective = "X",
                    roleId = null,
                    executor = approvalExecutor,
                    condition = TaskCondition.OnFailure(TaskDefinitionId("nonexistent")),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val errors = WorkflowGraphValidator.validate(badDef)
        assertTrue(errors.any { it is WorkflowValidationError.MissingConditionTarget })
    }

    @Test
    fun isTerminalClassifiesStatusesCorrectly() {
        assertTrue(TaskRunStatus.Completed.isTerminal())
        assertTrue(TaskRunStatus.Failed.isTerminal())
        assertTrue(TaskRunStatus.Escalated.isTerminal())
        assertTrue(TaskRunStatus.Cancelled.isTerminal())
        assertTrue(!TaskRunStatus.Running.isTerminal())
        assertTrue(!TaskRunStatus.Ready.isTerminal())
        assertTrue(!TaskRunStatus.Blocked.isTerminal())
    }
}

class FanOutFanInTest {
    private val upstream = TaskDefinitionId("upstream")
    private val branch1 = TaskDefinitionId("branch1")
    private val branch2 = TaskDefinitionId("branch2")
    private val downstream = TaskDefinitionId("downstream")

    private fun task(id: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = id.value,
        objective = id.value,
        roleId = null,
        executor = TaskExecutor.HumanApproval(),
    )

    @Test
    fun fanOutAddsDependencyToEachBranch() {
        val branches = fanOut(
            from = upstream,
            branches = listOf(task(branch1), task(branch2)),
        )
        assertTrue(upstream in branches[0].dependsOn)
        assertTrue(upstream in branches[1].dependsOn)
    }

    @Test
    fun fanOutPreservesExistingDependencies() {
        val branchWithExisting = task(branch1).copy(dependsOn = setOf(TaskDefinitionId("other")))
        val branches = fanOut(upstream, listOf(branchWithExisting))
        assertEquals(setOf(TaskDefinitionId("other"), upstream), branches[0].dependsOn)
    }

    @Test
    fun fanInAddsAllSourcesAsIncomingDependencies() {
        val merged = fanIn(
            from = listOf(branch1, branch2),
            into = task(downstream),
        )
        assertTrue(branch1 in merged.dependsOn)
        assertTrue(branch2 in merged.dependsOn)
    }

    @Test
    fun fanInPreservesExistingDependencies() {
        val existing = task(downstream).copy(dependsOn = setOf(TaskDefinitionId("other")))
        val merged = fanIn(listOf(branch1), existing)
        assertEquals(setOf(TaskDefinitionId("other"), branch1), merged.dependsOn)
    }
}
