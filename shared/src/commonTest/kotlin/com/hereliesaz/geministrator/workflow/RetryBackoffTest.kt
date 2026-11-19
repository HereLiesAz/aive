package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
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
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A retry of a provider failure waits, longer each time, and a creature is not started before then. */
class RetryBackoffTest {
    private val taskId = TaskDefinitionId("work")
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "Workflow",
        tasks = listOf(
            TaskDefinition(
                id = taskId,
                name = "Work",
                objective = "Work",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                retryPolicy = RetryPolicy(
                    maxAttempts = 4,
                    retryOn = setOf(RetryReason.ProviderFailure, RetryReason.VerificationFailed),
                ),
            ),
        ),
    )
    private val project = Project(id = ProjectId("project"), name = "Project", createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L)

    private fun failedRun() = WorkflowRunFactory.create(
        definition = definition,
        workflowRunId = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        objective = "Objective",
        nowEpochMillis = 0L,
        taskRunIdFactory = { TaskRunId("task-run") },
    ).let { created ->
        created.copy(taskRuns = created.taskRuns + (taskId to created.taskRuns.getValue(taskId).copy(status = TaskRunStatus.Failed)))
    }

    @Test
    fun theDelayGrowsEachAttemptAndStopsAtTheCap() {
        val delays = (1..8).map { retryBackoffMillis(attempt = it, seed = "t") }
        assertTrue(delays.zipWithNext().take(3).all { (a, b) -> b > a }, delays.toString())
        assertTrue(delays.all { it <= RETRY_BACKOFF_MAX_MILLIS * 6 / 5 }, delays.toString())
        assertTrue(delays.last() >= RETRY_BACKOFF_MAX_MILLIS * 4 / 5, delays.toString())
    }

    @Test
    fun theJitterIsWithinAFifthAndDoesNotChangeBetweenCalls() {
        val one = retryBackoffMillis(attempt = 1, seed = "alpha")
        assertEquals(one, retryBackoffMillis(attempt = 1, seed = "alpha"))
        assertTrue(one in (RETRY_BACKOFF_BASE_MILLIS * 4 / 5)..(RETRY_BACKOFF_BASE_MILLIS * 6 / 5), one.toString())
        // Different creatures do not all retry in the same instant.
        val spread = (1..20).map { retryBackoffMillis(attempt = 1, seed = "task-$it") }.toSet()
        assertTrue(spread.size > 1, spread.toString())
    }

    @Test
    fun aProviderFailureRetryWaitsButAVerificationRetryDoesNot() = runBlocking {
        val engine = WorkflowEngine(sessionGateway = BackoffGateway(), roles = BuiltInRoles.all, eventSink = InMemoryWorkflowEventSink())

        val provider = engine.handleFailure(
            definition = definition, run = failedRun(), taskDefinitionId = taskId,
            retryReason = RetryReason.ProviderFailure, reason = "HTTP 500", nowEpochMillis = 1_000L,
        ).taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Retrying, provider.status)
        val notBefore = assertNotNull(provider.retryNotBefore)
        assertTrue(notBefore > 1_000L, notBefore.toString())

        val verification = engine.handleFailure(
            definition = definition, run = failedRun(), taskDefinitionId = taskId,
            retryReason = RetryReason.VerificationFailed, reason = "output rejected", nowEpochMillis = 1_000L,
        ).taskRuns.getValue(taskId)
        assertEquals(TaskRunStatus.Retrying, verification.status)
        assertNull(verification.retryNotBefore)
    }

    @Test
    fun aCreatureWaitingToRetryIsNotStartedUntilItsTime() = runBlocking {
        val gateway = BackoffGateway()
        val engine = WorkflowEngine(sessionGateway = gateway, roles = BuiltInRoles.all, eventSink = InMemoryWorkflowEventSink())
        val retrying = engine.handleFailure(
            definition = definition, run = failedRun(), taskDefinitionId = taskId,
            retryReason = RetryReason.ProviderFailure, reason = "HTTP 500", nowEpochMillis = 1_000L,
        )
        val notBefore = retrying.taskRuns.getValue(taskId).retryNotBefore!!

        val early = engine.dispatchReadyTasks(project = project, definition = definition, run = retrying, nowEpochMillis = notBefore - 1)
        assertEquals(0, gateway.started)
        assertEquals(TaskRunStatus.Retrying, early.run.taskRuns.getValue(taskId).status)

        engine.dispatchReadyTasks(project = project, definition = definition, run = retrying, nowEpochMillis = notBefore)
        assertEquals(1, gateway.started)
    }
}

private class BackoffGateway : ManagedSessionGateway {
    var started = 0
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("recording")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        started++
        return ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("recording"),
            providerRunId = ProviderRunId("provider-run-$started"),
        )
    }
    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) = Unit
    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle) =
        emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()
}
