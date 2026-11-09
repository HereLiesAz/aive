package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plans of the tasks that wait for approval are drafted together, so a slow provider is waited for once, not once per task. */
class PlanDraftConcurrencyTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val ids = listOf("a", "b", "c").map(::TaskDefinitionId)
    private val project = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("drafts"),
        name = "Drafts",
        tasks = ids.map { id ->
            TaskDefinition(
                id = id,
                name = id.value,
                objective = "Do ${id.value}",
                roleId = engineer.id,
                executor = TaskExecutor.RoleAgent(engineer.id),
                approvalPolicy = ApprovalPolicy.HumanApproval,
            )
        },
        testDesignPolicy = TestDesignPolicy.None,
    )
    private val run = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("run-drafts"),
        projectId = project.id,
        objective = "Ship",
        nowEpochMillis = 1L,
        taskRunIdFactory = { TaskRunId("tr-${it.value}") },
        roles = BuiltInRoles.all,
    )

    @Test
    fun threeTasksWaitingForPlanApprovalHaveTheirPlansDraftedAtTheSameTime() = runBlocking<Unit> {
        val gateway = BarrierDraftingGateway(expected = 3)

        // Drafted one after another, the first would wait for the others to start and none ever would.
        val held = withTimeout(5_000L) {
            WorkflowEngine(gateway, BuiltInRoles.all).dispatchReadyTasks(project, definition, run, nowEpochMillis = 2L).run
        }

        ids.forEach { id ->
            assertEquals(TaskRunStatus.AwaitingApproval, held.taskRuns.getValue(id).status, id.value)
            assertEquals("plan for ${id.value}", held.taskRuns.getValue(id).providerPlan)
        }
        assertEquals(3, gateway.peak, "all three were in flight together")
    }

    @Test
    fun noMoreThanAFewDraftsAreInFlightAtOnce() = runBlocking<Unit> {
        val many = (1..9).map { TaskDefinitionId("t$it") }
        val wide = definition.copy(tasks = many.map { id -> definition.tasks.first().copy(id = id, name = id.value) })
        val start = WorkflowRunFactory.create(
            definition = wide,
            workflowRunId = WorkflowRunId("run-wide"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("tr-${it.value}") },
            roles = BuiltInRoles.all,
        )
        val gateway = BarrierDraftingGateway(expected = 1)

        WorkflowEngine(gateway, BuiltInRoles.all)
            .dispatchReadyTasks(project, wide.copy(concurrencyPolicy = wide.concurrencyPolicy.copy(maxConcurrentTasks = 20)), start, nowEpochMillis = 2L)

        assertEquals(4, gateway.peak, "bounded, so a provider is not flooded")
        assertEquals(9, gateway.drafted)
    }
}

private class BarrierDraftingGateway(private val expected: Int) : ManagedSessionGateway {
    private var inFlight = 0
    var peak = 0
    var drafted = 0
    private val allStarted = CompletableDeferred<Unit>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("drafting-provider")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("no session before approval")

    override suspend fun draftPlan(providerId: AgentProviderId, request: AgentTaskRequest): String? {
        inFlight += 1
        peak = maxOf(peak, inFlight)
        if (inFlight >= expected) allStarted.complete(Unit)
        // Every draft waits until the expected number are in flight, which only happens if they run together.
        allStarted.await()
        delay(20L)
        inFlight -= 1
        drafted += 1
        return "plan for ${request.objective.removePrefix("Do ")}"
    }

    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<ProviderArtifact>()
}
