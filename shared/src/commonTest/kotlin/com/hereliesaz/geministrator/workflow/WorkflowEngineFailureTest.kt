package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowEngineFailureTest {
    @Test
    fun parallelSessionFailureCancelsCreatedSiblingsBeforeStateCommit() = runBlocking {
        val firstId = TaskDefinitionId("first")
        val secondId = TaskDefinitionId("second")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("parallel-start"),
            name = "Parallel start",
            tasks = listOf(
                TaskDefinition(
                    id = firstId,
                    name = "First",
                    objective = "Start first",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
                TaskDefinition(
                    id = secondId,
                    name = "Second",
                    objective = "Start second",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("parallel-run"),
            projectId = ProjectId("project"),
            objective = "Start both",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val gateway = FailingParallelGateway()
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(
            sessionGateway = gateway,
            roles = BuiltInRoles.all,
            eventSink = events,
        )

        val failure = runCatching {
            engine.dispatchReadyTasks(
                project = Project(
                    id = ProjectId("project"),
                    name = "Project",
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                ),
                definition = definition,
                run = run,
                nowEpochMillis = 10L,
            )
        }.exceptionOrNull()

        assertEquals("second session failed", failure?.message)
        assertEquals(listOf(ProviderRunId("provider-run-first")), gateway.cancelled.map { it.providerRunId })
        assertTrue(events.snapshot().none { it is TaskStarted })
        assertTrue(run.taskRuns.values.all { it.status == TaskRunStatus.Ready })
    }

    @Test
    fun failureRetriesThenReassignsWithoutLosingAuthorityDecision() = runBlocking {
        val taskId = TaskDefinitionId("implement")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("wf"),
            name = "Workflow",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Implement",
                    objective = "Implement",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    retryPolicy = RetryPolicy(
                        maxAttempts = 2,
                        retryOn = setOf(RetryReason.ProviderFailure),
                    ),
                    escalationPolicy = EscalationPolicy.Reassign(BuiltInRoles.RecoveryEngineer.id),
                ),
            ),
        )
        val priorAttemptArtifact = ArtifactRef(
            id = ArtifactId("task-run:command-output:1:0"),
            kind = ArtifactKind.CommandOutput,
            taskRunId = TaskRunId("task-run"),
            label = "attempt 1 output",
            textContent = "failed attempt evidence",
            createdAtEpochMillis = 5L,
        )
        val initial = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            objective = "Objective",
            nowEpochMillis = 0L,
            taskRunIdFactory = { TaskRunId("task-run") },
        ).copy(
            taskRuns = mapOf(
                taskId to WorkflowRunFactory.create(
                    definition,
                    WorkflowRunId("other"),
                    ProjectId("project"),
                    "Objective",
                    0L,
                    { TaskRunId("task-run") },
                ).taskRuns.getValue(taskId).copy(
                    status = TaskRunStatus.Failed,
                    artifacts = listOf(priorAttemptArtifact),
                ),
            ),
        )
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(
            sessionGateway = NoOpGateway,
            roles = BuiltInRoles.all,
            eventSink = events,
        )

        val retry = engine.handleFailure(
            definition = definition,
            run = initial,
            taskDefinitionId = taskId,
            retryReason = RetryReason.ProviderFailure,
            reason = "provider failed",
            nowEpochMillis = 10L,
        )
        assertEquals(TaskRunStatus.Retrying, retry.taskRuns.getValue(taskId).status)
        assertEquals(2, retry.taskRuns.getValue(taskId).attempt)
        assertTrue(retry.taskRuns.getValue(taskId).artifacts.isEmpty())
        assertTrue(events.snapshot().any { it is RetryScheduled })

        val exhausted = retry.copy(
            taskRuns = retry.taskRuns + (taskId to retry.taskRuns.getValue(taskId).copy(status = TaskRunStatus.Failed)),
        )
        val reassigned = engine.handleFailure(
            definition = definition,
            run = exhausted,
            taskDefinitionId = taskId,
            retryReason = RetryReason.ProviderFailure,
            reason = "provider failed again",
            nowEpochMillis = 20L,
        )

        assertEquals(BuiltInRoles.RecoveryEngineer.id, reassigned.taskRuns.getValue(taskId).assignedRoleId)
        assertEquals(TaskRunStatus.Retrying, reassigned.taskRuns.getValue(taskId).status)
        assertTrue(events.snapshot().any { it is TaskEscalated && it.reassignedRoleId == BuiltInRoles.RecoveryEngineer.id })
    }
}

private object NoOpGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("unused")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("not used")
    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) = Unit
    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = error("not used")
    override suspend fun approvePlan(handle: ManagedSessionHandle) = error("not used")
    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}


private class FailingParallelGateway : ManagedSessionGateway {
    private val providerId = AgentProviderId("parallel-provider")
    private val firstCreated = CompletableDeferred<Unit>()
    val cancelled = mutableListOf<ManagedSessionHandle>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        val taskRunId = request.taskRequest.taskRunId
        return if (request.taskRequest.objective == "Start first") {
            val handle = ManagedSessionHandle(
                taskRunId = taskRunId,
                providerId = providerId,
                providerRunId = ProviderRunId("provider-run-first"),
            )
            firstCreated.complete(Unit)
            handle
        } else {
            firstCreated.await()
            error("second session failed")
        }
    }

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelled += handle
        return ProviderActionResult.Accepted
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) =
        emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}
