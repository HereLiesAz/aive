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
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.InMemoryWorkflowEventSink
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.WorkflowFailed
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
    fun aSessionThatCannotStartFailsOnlyItsTaskAndLeavesItsSiblingRunning() = runBlocking {
        val goodId = TaskDefinitionId("good")
        val badId = TaskDefinitionId("bad")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("start-failure"),
            name = "Start failure",
            tasks = listOf(
                TaskDefinition(
                    id = goodId,
                    name = "Good",
                    objective = "Start good",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
                TaskDefinition(
                    id = badId,
                    name = "Bad",
                    objective = "Start bad",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("start-failure-run"),
            projectId = ProjectId("project"),
            objective = "Start both",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val gateway = StartFailureGateway(failingObjective = "Start bad")
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all, eventSink = events)
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        )

        // The provider refuses the start (HTTP 402): this must not escape the dispatch.
        val first = engine.dispatchReadyTasks(project = project, definition = definition, run = run, nowEpochMillis = 10L)

        assertEquals(TaskRunStatus.Running, first.run.taskRuns.getValue(goodId).status)
        assertEquals(setOf(goodId), first.handles.keys)
        // The failed start goes through the task's retry policy like any other provider failure.
        assertEquals(TaskRunStatus.Retrying, first.run.taskRuns.getValue(badId).status)
        assertEquals(2, first.run.taskRuns.getValue(badId).attempt)
        assertTrue(events.snapshot().any { it is TaskFailed && it.taskDefinitionId == badId })
        assertTrue(events.snapshot().any { it is RetryScheduled && it.taskDefinitionId == badId })
        assertTrue(gateway.cancelled.isEmpty(), "the sibling that started must keep running")

        // Its retry budget is finite: the second refusal fails the workflow instead of looping.
        val second = engine.dispatchReadyTasks(
            project = project,
            definition = definition,
            run = first.run,
            existingHandles = first.handles,
            nowEpochMillis = 20L,
        )
        assertEquals(WorkflowRunStatus.Failed, second.run.status)
        assertEquals(TaskRunStatus.Failed, second.run.taskRuns.getValue(badId).status)
        assertTrue(events.snapshot().any { it is WorkflowFailed })
    }

    @Test
    fun aTaskWhoseRoleIsMissingOrDisabledFailsAloneAndDoesNotStarveReadySiblings() = runBlocking {
        val ghostId = TaskDefinitionId("ghost")
        val disabledId = TaskDefinitionId("disabled")
        val goodId = TaskDefinitionId("good")
        val disabledRole = BuiltInRoles.RecoveryEngineer.copy(enabled = false)
        fun agent(id: TaskDefinitionId, role: RoleDefinitionId) = TaskDefinition(
            id = id,
            name = id.value,
            objective = "Start ${id.value}",
            roleId = role,
            escalationPolicy = EscalationPolicy.RequireHumanDecision,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("bad-roles"),
            name = "Bad roles",
            // The broken tasks come first, where the old dispatch threw before reaching the good one.
            tasks = listOf(
                agent(ghostId, RoleDefinitionId("no-such-role")),
                agent(disabledId, disabledRole.id),
                agent(goodId, BuiltInRoles.ImplementationEngineer.id),
            ),
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("bad-roles-run"),
            projectId = ProjectId("project"),
            objective = "Start all",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        val gateway = StartFailureGateway(failingObjective = "never")
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(
            sessionGateway = gateway,
            roles = BuiltInRoles.all.filterNot { it.id == disabledRole.id } + disabledRole,
            eventSink = events,
        )

        val result = engine.dispatchReadyTasks(
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

        assertEquals(TaskRunStatus.Running, result.run.taskRuns.getValue(goodId).status)
        assertEquals(setOf(goodId), result.handles.keys)
        assertEquals(TaskRunStatus.Escalated, result.run.taskRuns.getValue(ghostId).status)
        assertEquals(TaskRunStatus.Escalated, result.run.taskRuns.getValue(disabledId).status)
        val reasons = events.snapshot().filterIsInstance<TaskFailed>().associate { it.taskDefinitionId to it.reason }
        assertTrue("no-such-role" in reasons.getValue(ghostId), reasons.toString())
        assertTrue("disabled" in reasons.getValue(disabledId), reasons.toString())
    }

    @Test
    fun aTaskRunWhoseTaskIsNoLongerInTheDefinitionFailsTheRunInsteadOfThrowing() = runBlocking {
        val keptId = TaskDefinitionId("kept")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("orphan"),
            name = "Orphan",
            tasks = listOf(
                TaskDefinition(
                    id = keptId,
                    name = "Kept",
                    objective = "Start kept",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                ),
            ),
        )
        val created = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("orphan-run"),
            projectId = ProjectId("project"),
            objective = "Start",
            nowEpochMillis = 0L,
            taskRunIdFactory = { id -> TaskRunId("run-${id.value}") },
        )
        // A run saved before its hive was edited keeps a task run for a creature the hive no longer has.
        val orphanId = TaskDefinitionId("removed")
        val run = created.copy(
            taskRuns = mapOf(orphanId to created.taskRuns.getValue(keptId).copy(
                id = TaskRunId("run-removed"),
                taskDefinitionId = orphanId,
            )) + created.taskRuns,
        )
        val events = InMemoryWorkflowEventSink()
        val engine = WorkflowEngine(
            sessionGateway = StartFailureGateway(failingObjective = "never"),
            roles = BuiltInRoles.all,
            eventSink = events,
        )

        val result = engine.dispatchReadyTasks(
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

        assertEquals(WorkflowRunStatus.Failed, result.run.status)
        assertEquals(TaskRunStatus.Failed, result.run.taskRuns.getValue(orphanId).status)
        val reason = events.snapshot().filterIsInstance<TaskFailed>().single { it.taskDefinitionId == orphanId }.reason
        assertTrue("removed" in reason, reason)
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
        assertEquals(3, reassigned.taskRuns.getValue(taskId).attempt)

        // The reassigned attempt failing too ends the run instead of reassigning forever.
        val reassignedFailed = reassigned.copy(
            taskRuns = reassigned.taskRuns + (taskId to reassigned.taskRuns.getValue(taskId).copy(status = TaskRunStatus.Failed)),
        )
        val failed = engine.handleFailure(
            definition = definition,
            run = reassignedFailed,
            taskDefinitionId = taskId,
            retryReason = RetryReason.ProviderFailure,
            reason = "recovery failed too",
            nowEpochMillis = 30L,
        )
        assertEquals(WorkflowRunStatus.Failed, failed.status)
        assertEquals(TaskRunStatus.Failed, failed.taskRuns.getValue(taskId).status)
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

/** Starts every session except the one whose objective is [failingObjective], which the provider refuses. */
private class StartFailureGateway(private val failingObjective: String) : ManagedSessionGateway {
    private val providerId = AgentProviderId("start-failure-provider")
    val cancelled = mutableListOf<ManagedSessionHandle>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId = providerId

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        if (request.taskRequest.objective == failingObjective) {
            throw ManagedSessionFailure.ProviderOperationFailed("HTTP 402 Payment Required")
        }
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = providerId,
            providerRunId = ProviderRunId("provider-run-${request.taskRequest.taskRunId.value}"),
        )
    }

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        cancelled += handle
        return ProviderActionResult.Accepted
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) =
        emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}
