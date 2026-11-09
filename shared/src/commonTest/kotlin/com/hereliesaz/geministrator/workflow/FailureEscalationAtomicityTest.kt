package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FailureEscalationAtomicityTest {
    @Test
    fun approvalCommitsGateRunAndAuditEventTogether() = runBlocking {
        val fixture = fixture()

        val nextRun = fixture.service.decideFailureEscalation(
            run = fixture.run,
            gateId = fixture.gate.id,
            approved = true,
            decidedByRoleId = null,
            note = "Retry it",
            nowEpochMillis = 20L,
        )

        assertEquals(ApprovalGateStatus.Approved, fixture.persistence.approvalGates.get(fixture.gate.id)?.status)
        assertEquals(nextRun, fixture.persistence.runs.get(fixture.run.id))
        assertEquals(TaskRunStatus.Retrying, nextRun.taskRuns.getValue(fixture.taskId).status)
        assertEquals(2, nextRun.taskRuns.getValue(fixture.taskId).attempt)
        val decisions = fixture.persistence.events.forRun(fixture.run.id)
            .filterIsInstance<ApprovalDecisionReceived>()
        assertEquals(1, decisions.size)
        assertTrue(decisions.single().approved)
    }

    @Test
    fun rejectionCommitsFailedRunWithRejectedGateAndSingleAuditEvent() = runBlocking {
        val fixture = fixture()

        val nextRun = fixture.service.decideFailureEscalation(
            run = fixture.run,
            gateId = fixture.gate.id,
            approved = false,
            decidedByRoleId = null,
            note = "Stop",
            nowEpochMillis = 20L,
        )

        assertEquals(ApprovalGateStatus.Rejected, fixture.persistence.approvalGates.get(fixture.gate.id)?.status)
        assertEquals(WorkflowRunStatus.Failed, nextRun.status)
        // The task failed and the keeper declined to retry it: Failed, as when the same decision is read back by the
        // engine (WorkflowEngine.resolveEscalation), so a failure handler and the run's log see the same thing.
        assertEquals(TaskRunStatus.Failed, nextRun.taskRuns.getValue(fixture.taskId).status)
        val events = fixture.persistence.events.forRun(fixture.run.id)
        assertEquals(1, events.filterIsInstance<com.hereliesaz.geministrator.events.TaskFailed>().size)
        assertEquals(1, events.filterIsInstance<com.hereliesaz.geministrator.events.WorkflowFailed>().size)
        assertEquals(nextRun, fixture.persistence.runs.get(fixture.run.id))
        val decisions = fixture.persistence.events.forRun(fixture.run.id)
            .filterIsInstance<ApprovalDecisionReceived>()
        assertEquals(1, decisions.size)
        assertEquals(false, decisions.single().approved)
    }

    @Test
    fun concurrentEscalationDecisionsHaveExactlyOneWinner() = runBlocking {
        val fixture = fixture()

        val outcomes = coroutineScope {
            listOf(true, false).map { approved ->
                async {
                    runCatching {
                        fixture.service.decideFailureEscalation(
                            run = fixture.run,
                            gateId = fixture.gate.id,
                            approved = approved,
                            decidedByRoleId = null,
                            note = if (approved) "Retry" else "Stop",
                            nowEpochMillis = 20L,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertEquals(
            1,
            fixture.persistence.events.forRun(fixture.run.id)
                .filterIsInstance<ApprovalDecisionReceived>()
                .size,
        )
        val persistedGate = fixture.persistence.approvalGates.get(fixture.gate.id)
        val persistedRun = fixture.persistence.runs.get(fixture.run.id)
        assertTrue(persistedGate?.status == ApprovalGateStatus.Approved || persistedGate?.status == ApprovalGateStatus.Rejected)
        if (persistedGate?.status == ApprovalGateStatus.Approved) {
            assertEquals(WorkflowRunStatus.Running, persistedRun?.status)
            assertEquals(TaskRunStatus.Retrying, persistedRun?.taskRuns?.get(fixture.taskId)?.status)
        } else {
            assertEquals(WorkflowRunStatus.Failed, persistedRun?.status)
            assertEquals(TaskRunStatus.Failed, persistedRun?.taskRuns?.get(fixture.taskId)?.status)
        }
    }

    @Test
    fun resolvedGateCannotBeCommittedAgain() = runBlocking {
        val fixture = fixture()
        fixture.service.decideFailureEscalation(
            run = fixture.run,
            gateId = fixture.gate.id,
            approved = true,
            decidedByRoleId = null,
            note = null,
            nowEpochMillis = 20L,
        )

        assertFails {
            fixture.service.decideFailureEscalation(
                run = fixture.run,
                gateId = fixture.gate.id,
                approved = false,
                decidedByRoleId = null,
                note = null,
                nowEpochMillis = 21L,
            )
        }
        assertEquals(
            1,
            fixture.persistence.events.forRun(fixture.run.id)
                .filterIsInstance<ApprovalDecisionReceived>()
                .size,
        )
    }

    private suspend fun fixture(): Fixture {
        val taskId = TaskDefinitionId("task")
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = WorkflowDefinitionId("definition"),
            objective = "Recover",
            status = WorkflowRunStatus.AwaitingHuman,
            taskRuns = mapOf(
                taskId to TaskRun(
                    id = TaskRunId("task-run"),
                    taskDefinitionId = taskId,
                    status = TaskRunStatus.Escalated,
                    attempt = 1,
                    assignedRoleId = null,
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 10L,
        )
        val gate = ApprovalGate(
            id = ApprovalGateId("failure:run:task:1"),
            workflowRunId = run.id,
            taskDefinitionId = taskId,
            kind = ApprovalGateKind.FailureEscalation,
            reason = "Executor failed",
            requiresHuman = true,
            createdAtEpochMillis = 10L,
        )
        val persistence = InMemoryWorkflowPersistence()
        persistence.runs.put(run)
        persistence.approvalGates.put(gate)
        val service = WorkflowApprovalService(
            gateRepository = persistence.approvalGates,
            gateCoordinator = ApprovalGateCoordinator(
                persistence.approvalGates,
                RepositoryWorkflowEventSink(persistence.events),
            ),
            sessionGateway = NoopEscalationGateway,
            failureEscalationDecisionStore = persistence,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
        )
        return Fixture(taskId, run, gate, persistence, service)
    }

    private data class Fixture(
        val taskId: TaskDefinitionId,
        val run: WorkflowRun,
        val gate: ApprovalGate,
        val persistence: InMemoryWorkflowPersistence,
        val service: WorkflowApprovalService,
    )
}

private object NoopEscalationGateway : ManagedSessionGateway {
    override suspend fun resolveProvider(selection: ProviderSelectionRequest) = AgentProviderId("unused")
    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle = error("unused")
    override suspend fun status(handle: ManagedSessionHandle) = ManagedSessionStatus.Unknown
    override suspend fun message(handle: ManagedSessionHandle, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(handle: ManagedSessionHandle) = ProviderActionResult.Accepted
    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
}
