package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowGraphValidatorTest {
    @Test
    fun acceptsAcyclicWorkflow() {
        val definition = workflow(
            task("research"),
            task("architecture", dependsOn = setOf("research")),
            task("implementation", dependsOn = setOf("architecture")),
        )

        assertTrue(WorkflowGraphValidator.validate(definition).isEmpty())
    }

    @Test
    fun rejectsMissingDependency() {
        val definition = workflow(task("implementation", dependsOn = setOf("missing")))

        val errors = WorkflowGraphValidator.validate(definition)

        assertEquals(1, errors.size)
        assertIs<WorkflowValidationError.MissingDependency>(errors.single())
    }

    @Test
    fun acceptsDelegatedRoleAgentExecution() {
        val definition = workflow(
            TaskDefinition(
                id = TaskDefinitionId("implementation"),
                name = "implementation",
                objective = "Do implementation",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
            ),
        )

        assertTrue(WorkflowGraphValidator.validate(definition).isEmpty())
    }

    @Test
    fun rejectsCycle() {
        val definition = workflow(
            task("a", dependsOn = setOf("b")),
            task("b", dependsOn = setOf("a")),
        )

        val errors = WorkflowGraphValidator.validate(definition)

        assertTrue(errors.any { it is WorkflowValidationError.Cycle })
    }

    private fun workflow(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("workflow"),
        name = "Workflow",
        tasks = tasks.toList(),
    )

    private fun task(id: String, dependsOn: Set<String> = emptySet()) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = dependsOn.map(::TaskDefinitionId).toSet(),
        acceptanceCriteria = listOf(AcceptanceCriterion("$id is complete")),
    )
}

class WorkflowRunFactoryTest {
    @Test
    fun rootsAreReadyAndDependentsAreBlocked() {
        val first = TaskDefinitionId("first")
        val second = TaskDefinitionId("second")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(
                task(first),
                task(second, setOf(first)),
            ),
        )

        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            objective = "Ship it",
            nowEpochMillis = 100L,
            taskRunIdFactory = { TaskRunId("run-${it.value}") },
        )

        assertEquals(TaskRunStatus.Ready, run.taskRuns.getValue(first).status)
        assertEquals(TaskRunStatus.Blocked, run.taskRuns.getValue(second).status)
    }

    @Test
    fun dependentBecomesReadyAfterDependenciesComplete() {
        val first = TaskDefinitionId("first")
        val second = TaskDefinitionId("second")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(task(first), task(second, setOf(first))),
        )
        val initial = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            objective = "Ship it",
            nowEpochMillis = 100L,
            taskRunIdFactory = { TaskRunId("run-${it.value}") },
        )
        val completedFirst = initial.copy(
            taskRuns = initial.taskRuns + (
                first to initial.taskRuns.getValue(first).copy(status = TaskRunStatus.Completed)
            ),
        )

        val refreshed = WorkflowRunFactory.refreshReadiness(definition, completedFirst, 200L)

        assertEquals(TaskRunStatus.Ready, refreshed.taskRuns.getValue(second).status)
        assertEquals(200L, refreshed.updatedAtEpochMillis)
        // Nothing left to change: the run, and its timestamp, are left alone.
        assertEquals(refreshed, WorkflowRunFactory.refreshReadiness(definition, refreshed, 300L))
    }

    private fun task(
        id: TaskDefinitionId,
        dependsOn: Set<TaskDefinitionId> = emptySet(),
    ) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = dependsOn,
    )
}

class TaskRunTransitionsTest {
    @Test
    fun completedAndCancelledAreTerminal() {
        TaskRunStatus.entries.forEach { status ->
            assertFalse(TaskRunTransitions.canTransition(TaskRunStatus.Completed, status))
            assertFalse(TaskRunTransitions.canTransition(TaskRunStatus.Cancelled, status))
        }
    }

    @Test
    fun failedTaskCanRetry() {
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Failed, TaskRunStatus.Retrying))
    }

    @Test
    fun awaitingApprovalCanObserveProviderFailureButNotCompletion() {
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.AwaitingApproval, TaskRunStatus.Failed))
        assertFalse(TaskRunTransitions.canTransition(TaskRunStatus.AwaitingApproval, TaskRunStatus.Completed))
    }

    @Test
    fun systemDispatchCanReturnTerminalResult() {
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Ready, TaskRunStatus.Completed))
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Ready, TaskRunStatus.Failed))
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Retrying, TaskRunStatus.Completed))
    }

    @Test
    fun systemExecutorAvailabilityCanBlockAndRecover() {
        listOf(
            TaskRunStatus.Ready,
            TaskRunStatus.Retrying,
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
        ).forEach { status ->
            assertTrue(TaskRunTransitions.canTransition(status, TaskRunStatus.Blocked))
        }
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Blocked, TaskRunStatus.Ready))
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Blocked, TaskRunStatus.Running))
        assertTrue(TaskRunTransitions.canTransition(TaskRunStatus.Blocked, TaskRunStatus.Verifying))
    }

    @Test
    fun everyNonterminalNonfailedTaskCanBeCancelled() {
        TaskRunStatus.entries
            .filterNot {
                it == TaskRunStatus.Completed ||
                    it == TaskRunStatus.Failed ||
                    it == TaskRunStatus.Cancelled
            }
            .forEach { status ->
                assertTrue(
                    TaskRunTransitions.canTransition(status, TaskRunStatus.Cancelled),
                    "$status should support cancellation",
                )
            }
    }
}
