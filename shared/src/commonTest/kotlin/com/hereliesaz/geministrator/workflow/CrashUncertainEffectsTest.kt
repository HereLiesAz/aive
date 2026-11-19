package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A crash or restart must never silently repeat an effect whose outcome is unknown (THREAT_MODEL, repeated
 * remote-run reconciliation). A step that declares an effect, was dispatched, and has no confirmed outcome
 * (no external run id to ask about) goes to the keeper instead of back to Ready.
 */
class CrashUncertainEffectsTest {
    private val taskId = TaskDefinitionId("push")
    private val executor = TaskExecutor.TestRunner("verify")

    private fun task(effects: Set<StepEffect>) = TaskDefinition(
        id = taskId,
        name = "Push",
        objective = "Push the branch",
        roleId = null,
        executor = executor,
        contract = TaskContract(TemplateRef("change-connected-source", 1), effects = effects),
    )

    private fun definition(task: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("crash-uncertain"),
        name = "Crash uncertain",
        tasks = listOf(task),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun runOf(definition: WorkflowDefinition, externalRunId: String?) = WorkflowRun(
        id = WorkflowRunId("crash-run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            taskId to TaskRun(
                id = TaskRunId("task-run-push"),
                taskDefinitionId = taskId,
                status = TaskRunStatus.Running,
                assignedRoleId = null,
                executor = executor,
                externalRunId = externalRunId,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun project() = Project(ProjectId("project"), "Project", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private class Counting : TaskExecutorIntegration {
        var dispatchCount = 0
        var reconcileCount = 0
        override fun supports(executor: TaskExecutor) = executor is TaskExecutor.TestRunner
        override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
            dispatchCount += 1
            return TaskExecutorExecution(status = TaskRunStatus.Running, externalRunId = "ext-new")
        }
        override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
            reconcileCount += 1
            return TaskExecutorExecution(status = TaskRunStatus.Completed, externalRunId = context.taskRun.externalRunId)
        }
    }

    private class Gateway : ManagedSessionGateway {
        override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("provider")
        override suspend fun createSession(request: ManagedSessionRequest) =
            ManagedSessionHandle(request.taskRequest.taskRunId, AgentProviderId("provider"), ProviderRunId("p"))
        override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
        override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
        override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
        override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
    }

    private fun coordinator(
        persistence: InMemoryWorkflowPersistence,
        integrations: TaskExecutorIntegrationRegistry,
    ): Pair<WorkflowRuntimeCoordinator, WorkflowEngine> {
        val gateway = Gateway()
        val engine = WorkflowEngine(gateway, emptyList(), eventSink = com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink(persistence.events))
        return WorkflowRuntimeCoordinator(persistence, engine, gateway, integrations) to engine
    }

    private suspend fun cycle(
        coordinator: WorkflowRuntimeCoordinator,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        now: Long,
    ) = coordinator.cycle(project(), definition, state, now) { taskRun, _, index ->
        com.hereliesaz.geministrator.domain.ArtifactId("${taskRun.id.value}-$index")
    }

    @Test
    fun effectfulRunningTaskWithoutExternalRunIdIsEscalatedNotRedispatched(): Unit = runBlocking {
        val definition = definition(task(setOf(StepEffect.WritesRepositoryBranch)))
        val persistence = InMemoryWorkflowPersistence()
        // Restart: the integration is not available yet, so the dispatched task is blocked ...
        val (down, _) = coordinator(persistence, TaskExecutorIntegrationRegistry.Empty)
        val blocked = cycle(down, definition, WorkflowRuntimeState(runOf(definition, externalRunId = null)), 20L)

        val escalated = blocked.run.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Escalated, escalated.status)
        assertEquals(WorkflowRunFactory.OUTCOME_UNCERTAIN, escalated.blockingReason?.code)
        assertEquals(WorkflowRunStatus.AwaitingHuman, blocked.run.status)
        val gate = assertNotNull(
            persistence.approvalGates.get(ApprovalGateId("failure:crash-run:push:1")),
        )
        assertEquals(ApprovalGateKind.FailureEscalation, gate.kind)
        assertEquals(ApprovalGateStatus.Pending, gate.status)
        assertTrue(gate.reason.contains("outcome unknown after restart", ignoreCase = true), gate.reason)
        assertTrue(gate.reason.contains("may or may not", ignoreCase = true), gate.reason)
        val event = persistence.events.forRun(blocked.run.id).filterIsInstance<TaskEscalated>().single()
        assertTrue(event.reason.contains("Outcome unknown after restart"), event.reason)

        // ... and when the integration comes back it is still not dispatched.
        val integration = Counting()
        val (up, _) = coordinator(persistence, TaskExecutorIntegrationRegistry(listOf(integration)))
        val after = cycle(up, definition, blocked, 40L)
        assertEquals(0, integration.dispatchCount)
        assertEquals(TaskRunStatus.Escalated, after.run.taskRuns.getValue(taskId).status)
    }

    @Test
    fun keeperRetryOrFailResolvesTheUncertainEffect(): Unit = runBlocking {
        val definition = definition(task(setOf(StepEffect.LeavesDevice)))
        val persistence = InMemoryWorkflowPersistence()
        val (down, engine) = coordinator(persistence, TaskExecutorIntegrationRegistry.Empty)
        val blocked = cycle(down, definition, WorkflowRuntimeState(runOf(definition, externalRunId = null)), 20L)

        val retried = engine.resolveEscalation(blocked.run, taskId, approved = true, nowEpochMillis = 30L)
        val retry = retried.taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Retrying, retry.status)
        assertEquals(2, retry.attempt)
        assertEquals(null, retry.blockingReason)

        val failed = engine.resolveEscalation(blocked.run, taskId, approved = false, nowEpochMillis = 30L)
        assertEquals(TaskRunStatus.Failed, failed.taskRuns.getValue(taskId).status)
    }

    @Test
    fun nonEffectfulRunningTaskStillGoesBackToReady(): Unit = runBlocking {
        val definition = definition(task(emptySet()))
        val persistence = InMemoryWorkflowPersistence()
        val (down, _) = coordinator(persistence, TaskExecutorIntegrationRegistry.Empty)
        val blocked = cycle(down, definition, WorkflowRuntimeState(runOf(definition, externalRunId = null)), 20L)
        assertEquals(TaskRunStatus.Blocked, blocked.run.taskRuns.getValue(taskId).status)

        val integration = Counting()
        val (up, _) = coordinator(persistence, TaskExecutorIntegrationRegistry(listOf(integration)))
        val after = cycle(up, definition, blocked, 40L)
        assertEquals(1, integration.dispatchCount)
        assertEquals(TaskRunStatus.Running, after.run.taskRuns.getValue(taskId).status)
    }

    @Test
    fun effectfulTaskWithExternalRunIdStillReconciles(): Unit = runBlocking {
        val definition = definition(task(setOf(StepEffect.MutatesConnectedSource)))
        val persistence = InMemoryWorkflowPersistence()
        val (down, _) = coordinator(persistence, TaskExecutorIntegrationRegistry.Empty)
        val blocked = cycle(down, definition, WorkflowRuntimeState(runOf(definition, externalRunId = "ext-1")), 20L)
        assertEquals(TaskRunStatus.Blocked, blocked.run.taskRuns.getValue(taskId).status)

        val integration = Counting()
        val (up, _) = coordinator(persistence, TaskExecutorIntegrationRegistry(listOf(integration)))
        val after = cycle(up, definition, blocked, 40L)
        assertEquals(0, integration.dispatchCount)
        assertEquals(1, integration.reconcileCount)
        assertEquals(TaskRunStatus.Completed, after.run.taskRuns.getValue(taskId).status)
    }
}
