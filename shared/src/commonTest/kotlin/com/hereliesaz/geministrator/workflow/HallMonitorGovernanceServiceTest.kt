package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.Project
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
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HallMonitorGovernanceServiceTest {
    @Test
    fun passingAntagonistReviewPausesWholeRunAndResumeRestoresExactTaskState() = runTest {
        val fixture = fixture(verdict = "pass")
        val service = HallMonitorGovernanceService(fixture.persistence)

        val paused = service.pauseAfterAntagonistPass(
            state = WorkflowRuntimeState(fixture.run),
            reportArtifactId = fixture.report.id,
            antagonistReviewArtifactId = fixture.review.id,
            sessionGateway = NoOpSessionGateway,
            nowEpochMillis = 50L,
        )

        assertEquals(WorkflowRunStatus.AwaitingHuman, paused.run.status)
        assertTrue(paused.handles.isEmpty())
        val pause = assertNotNull(paused.run.globalPause)
        assertEquals(fixture.report.id, pause.reportArtifactId)
        assertEquals(TaskRunStatus.Completed, paused.run.taskRuns.getValue(fixture.hallTask).status)
        assertEquals(TaskRunStatus.Completed, paused.run.taskRuns.getValue(fixture.reviewTask).status)
        assertEquals(TaskRunStatus.Blocked, paused.run.taskRuns.getValue(fixture.workerTask).status)
        assertEquals(
            HALL_MONITOR_GLOBAL_PAUSE_CODE,
            paused.run.taskRuns.getValue(fixture.workerTask).blockingReason?.code,
        )

        val unresolved = fixture.persistence.approvalGates.unresolved(fixture.run.id)
        assertEquals(2, unresolved.size)
        assertTrue(unresolved.any { it.kind == ApprovalGateKind.HallMonitorOrchestratorReview })
        assertTrue(unresolved.any { it.kind == ApprovalGateKind.HallMonitorHumanReview })

        service.decideOrchestratorReview(paused.run, true, "Proceed", 60L)
        service.decideHumanReview(paused.run, true, "Proceed", 61L)
        val resumed = service.resumeAfterReviews(paused.run, 70L)

        assertNull(resumed.globalPause)
        assertEquals(WorkflowRunStatus.Running, resumed.status)
        val restoredWorker = resumed.taskRuns.getValue(fixture.workerTask)
        assertEquals(TaskRunStatus.Running, restoredWorker.status)
        assertEquals(0.42f, restoredWorker.progress)
        assertEquals("Still working", restoredWorker.progressMessage)
        assertNull(restoredWorker.blockingReason)
        assertEquals(AgentProviderId("provider"), restoredWorker.assignedProviderId)
        assertEquals(ProviderRunId("provider-run"), restoredWorker.providerRunId)
    }

    @Test
    fun orchestratorReviewReconstructsProviderSessionAfterRestart() = runTest {
        val fixture = fixture(verdict = "pass")
        val service = HallMonitorGovernanceService(fixture.persistence)
        val paused = service.pauseAfterAntagonistPass(
            state = WorkflowRuntimeState(fixture.run),
            reportArtifactId = fixture.report.id,
            antagonistReviewArtifactId = fixture.review.id,
            sessionGateway = NoOpSessionGateway,
            nowEpochMillis = 50L,
        )
        val project = Project(
            id = fixture.run.projectId,
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        val withReviewSession = service.reconcileOrchestratorReview(
            project = project,
            run = paused.run,
            sessionGateway = NoOpSessionGateway,
            nowEpochMillis = 60L,
        )
        val persistedPause = assertNotNull(withReviewSession.globalPause)
        assertNotNull(persistedPause.orchestratorReviewProviderId)
        assertNotNull(persistedPause.orchestratorReviewProviderRunId)

        val restartedGateway = RecordingReconnectSessionGateway()
        service.reconcileOrchestratorReview(
            project = project,
            run = withReviewSession,
            sessionGateway = restartedGateway,
            nowEpochMillis = 70L,
        )

        val request = assertNotNull(restartedGateway.reconnectedRequest)
        assertEquals(false, request.requirePlanApproval)
        assertEquals(
            setOf(fixture.report.id, fixture.review.id),
            request.contextArtifacts.map { it.id }.toSet(),
        )
        assertEquals(ManagedSessionStatus.Running, restartedGateway.reconnectedStatus)
    }

    @Test
    fun nonPassingAntagonistReviewCannotPauseWorkflow() = runTest {
        val fixture = fixture(verdict = "revise")
        val service = HallMonitorGovernanceService(fixture.persistence)

        val failure = assertFailsWith<IllegalArgumentException> {
            service.pauseAfterAntagonistPass(
                state = WorkflowRuntimeState(fixture.run),
                reportArtifactId = fixture.report.id,
                antagonistReviewArtifactId = fixture.review.id,
                sessionGateway = NoOpSessionGateway,
                nowEpochMillis = 50L,
            )
        }

        assertTrue(failure.message.orEmpty().contains("PASS the Antagonist"))
        assertNull(fixture.persistence.runs.get(fixture.run.id)?.globalPause)
        assertTrue(fixture.persistence.approvalGates.unresolved(fixture.run.id).isEmpty())
    }

    @Test
    fun resumeRequiresBothReviews() = runTest {
        val fixture = fixture(verdict = "pass")
        val service = HallMonitorGovernanceService(fixture.persistence)
        val paused = service.pauseAfterAntagonistPass(
            state = WorkflowRuntimeState(fixture.run),
            reportArtifactId = fixture.report.id,
            antagonistReviewArtifactId = fixture.review.id,
            sessionGateway = NoOpSessionGateway,
            nowEpochMillis = 50L,
        )

        service.decideOrchestratorReview(paused.run, true, null, 60L)
        val failure = assertFailsWith<IllegalArgumentException> {
            service.resumeAfterReviews(paused.run, 70L)
        }
        assertTrue(failure.message.orEmpty().contains("User review is still pending"))
    }

    private suspend fun fixture(verdict: String): Fixture {
        val persistence = InMemoryWorkflowPersistence()
        val hallTask = TaskDefinitionId("hall-monitor")
        val reviewTask = TaskDefinitionId("hall-monitor-antagonist")
        val workerTask = TaskDefinitionId("worker")
        val report = ArtifactRef(
            id = ArtifactId("hall-report"),
            kind = ArtifactKind.HallMonitorReport,
            taskRunId = TaskRunId("hall-run"),
            label = "Hall Monitor report",
            textContent = "{}",
            metadata = mapOf(HALL_MONITOR_REPORT_ID_METADATA to "report-1"),
            createdAtEpochMillis = 10L,
        )
        val review = ArtifactRef(
            id = ArtifactId("hall-review"),
            kind = ArtifactKind.HallMonitorReview,
            taskRunId = TaskRunId("review-run"),
            label = "Antagonist review",
            textContent = "{}",
            metadata = mapOf(
                HALL_MONITOR_REPORT_ID_METADATA to "report-1",
                HALL_MONITOR_REVIEW_VERDICT_METADATA to verdict,
            ),
            createdAtEpochMillis = 20L,
        )
        persistence.artifacts.put(report)
        persistence.artifacts.put(review)

        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = WorkflowDefinitionId("definition"),
            objective = "Monitor the swarm",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                hallTask to TaskRun(
                    id = report.taskRunId,
                    taskDefinitionId = hallTask,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = HallMonitorRole.id,
                    artifacts = listOf(report),
                    progress = 1f,
                ),
                reviewTask to TaskRun(
                    id = review.taskRunId,
                    taskDefinitionId = reviewTask,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = BuiltInRoles.Antagonist.id,
                    artifacts = listOf(review),
                    progress = 1f,
                ),
                workerTask to TaskRun(
                    id = TaskRunId("worker-run"),
                    taskDefinitionId = workerTask,
                    status = TaskRunStatus.Running,
                    assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                    assignedProviderId = AgentProviderId("provider"),
                    providerRunId = ProviderRunId("provider-run"),
                    progress = 0.42f,
                    progressMessage = "Still working",
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        persistence.runs.put(run)
        return Fixture(persistence, run, hallTask, reviewTask, workerTask, report, review)
    }

    private data class Fixture(
        val persistence: InMemoryWorkflowPersistence,
        val run: WorkflowRun,
        val hallTask: TaskDefinitionId,
        val reviewTask: TaskDefinitionId,
        val workerTask: TaskDefinitionId,
        val report: ArtifactRef,
        val review: ArtifactRef,
    )

    private class RecordingReconnectSessionGateway : ManagedSessionGateway {
        var reconnectedRequest: AgentTaskRequest? = null
        var reconnectedStatus: ManagedSessionStatus? = null
        private var status: ManagedSessionStatus = ManagedSessionStatus.Unknown

        override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId =
            AgentProviderId("provider")

        override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle =
            error("restart must reconnect the persisted Hall Monitor review session")

        override suspend fun reconnect(
            handle: ManagedSessionHandle,
            initialStatus: ManagedSessionStatus,
            request: AgentTaskRequest,
            providerPlan: String?,
        ) {
            reconnectedRequest = request
            reconnectedStatus = initialStatus
            status = initialStatus
        }

        override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = status

        override suspend fun message(
            handle: ManagedSessionHandle,
            message: String,
        ): ProviderActionResult = ProviderActionResult.Accepted

        override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
            ProviderActionResult.Accepted

        override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
    }

    private object NoOpSessionGateway : ManagedSessionGateway {
        override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId =
            AgentProviderId("provider")

        override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle =
            ManagedSessionHandle(
                taskRunId = request.taskRequest.taskRunId,
                providerId = AgentProviderId("provider"),
                providerRunId = ProviderRunId("provider-run"),
            )

        override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus =
            ManagedSessionStatus.Unknown

        override suspend fun message(
            handle: ManagedSessionHandle,
            message: String,
        ): ProviderActionResult = ProviderActionResult.Accepted

        override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
            ProviderActionResult.Accepted

        override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult =
            ProviderActionResult.Accepted

        override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> = emptyList()
    }
}
