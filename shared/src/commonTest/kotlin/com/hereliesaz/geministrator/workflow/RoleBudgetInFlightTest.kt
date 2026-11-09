package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A role's token budget counts what its running tasks are expected to spend, not only what has been reported. */
class RoleBudgetInFlightTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val ids = listOf("one", "two", "three").map(::TaskDefinitionId)

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("budgeted"),
        name = "Budgeted",
        tasks = ids.map { id ->
            TaskDefinition(
                id = id,
                name = id.value,
                // Long enough that the input estimate is the larger part of what a task is expected to bill.
                objective = "Do ${id.value}. " + "Work through it carefully. ".repeat(80),
                roleId = engineer.id,
                executor = TaskExecutor.RoleAgent(engineer.id),
            )
        },
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun fresh(): WorkflowRun = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = com.hereliesaz.geministrator.domain.WorkflowRunId("run-budgeted"),
        projectId = project.id,
        objective = "Ship",
        nowEpochMillis = 1L,
        taskRunIdFactory = { com.hereliesaz.geministrator.domain.TaskRunId("tr-${it.value}") },
        roles = BuiltInRoles.all,
    )

    private fun estimate(run: WorkflowRun): Long =
        estimateTaskInputTokens(definition, run, definition.tasks.first(), engineer).toLong()

    private fun engine(gateway: CountingGateway, budget: Long) = WorkflowEngine(
        sessionGateway = gateway,
        roles = BuiltInRoles.all.map { if (it.id == engineer.id) it.copy(tokenBudget = budget) else it },
    )

    @Test
    fun tasksOfOneRoleDoNotAllStartTogetherWhenTheirExpectedSpendTogetherExceedsItsBudget() = runBlocking<Unit> {
        val run = fresh()
        val gateway = CountingGateway()

        val dispatched = engine(gateway, budget = estimate(run) * 2 - 1)
            .dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L)

        // Before: all three were within the budget (nothing had been reported) and all three started.
        assertEquals(1, gateway.created, "the first starts; the others wait for its usage to land")
        assertEquals(1, dispatched.run.taskRuns.values.count { it.status == TaskRunStatus.Running })
        assertEquals(2, dispatched.run.taskRuns.values.count { it.status == TaskRunStatus.Ready }, "waiting, not parked or failed")
    }

    @Test
    fun aBudgetThatFitsEveryTaskStartsThemAllAndOneTaskLargerThanTheBudgetStillStarts() = runBlocking<Unit> {
        val run = fresh()
        val wide = CountingGateway()
        engine(wide, budget = estimate(run) * 10).dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L)
        assertEquals(3, wide.created)

        val narrow = CountingGateway()
        engine(narrow, budget = 1L).dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L)
        assertEquals(1, narrow.created, "progress is never refused to the first task of a role")
    }

    @Test
    fun onceTheRunningTasksUsageHasLandedTheBudgetCountsWhatWasReportedInsteadOfTheEstimate() = runBlocking<Unit> {
        val run = fresh()
        val started = run.copy(
            taskRuns = run.taskRuns.mapValues { (id, taskRun) ->
                if (id == ids.first()) {
                    taskRun.copy(
                        status = TaskRunStatus.Running,
                        assignedRoleId = engineer.id,
                        providerRunId = ProviderRunId("running"),
                        assignedProviderId = AgentProviderId("provider"),
                        usage = TaskUsage(inputTokens = 10, outputTokens = 5),
                    )
                } else {
                    taskRun
                }
            },
        )
        val gateway = CountingGateway()

        engine(gateway, budget = estimate(run) * 3).dispatchReadyTasks(project, definition, started, nowEpochMillis = 2L)

        assertEquals(2, gateway.created, "the running task reported 15 tokens: nothing more is set aside for it")
    }
}

private class CountingGateway : ManagedSessionGateway {
    var created = 0

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        created += 1
        return ManagedSessionHandle(request.taskRequest.taskRunId, AgentProviderId("provider"), ProviderRunId("created-$created"))
    }

    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Running
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
