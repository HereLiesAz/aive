package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.PausedTaskSnapshot
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowGlobalPause
import com.hereliesaz.geministrator.domain.WorkflowGlobalPauseKind
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

const val HALL_MONITOR_REPORT_ID_METADATA: String = "hall-monitor-report-id"
const val HALL_MONITOR_REVIEW_VERDICT_METADATA: String = "hall-monitor-verdict"
const val HALL_MONITOR_REVIEW_PASS: String = "pass"
const val HALL_MONITOR_GLOBAL_PAUSE_CODE: String = "hall-monitor-global-pause"

/**
 * Governs the Hall Monitor's escalation path:
 *
 * Hall Monitor report -> passing Antagonist review -> global resumable pause ->
 * Orchestrator review + human review -> explicit resume.
 *
 * This service never treats report production itself as permission to pause or mutate the swarm.
 */
class HallMonitorGovernanceService(
    private val persistence: WorkflowPersistence,
    private val gateCoordinator: ApprovalGateCoordinator = ApprovalGateCoordinator(
        persistence.approvalGates,
        RepositoryWorkflowEventSink(persistence.events),
    ),
) {
    data class ReviewGates(
        val orchestrator: ApprovalGate,
        val human: ApprovalGate,
    )

    suspend fun pauseAfterAntagonistPass(
        state: WorkflowRuntimeState,
        reportArtifactId: ArtifactId,
        antagonistReviewArtifactId: ArtifactId,
        nowEpochMillis: Long,
    ): WorkflowRuntimeState {
        val run = state.run
        require(run.globalPause == null) { "Workflow ${run.id.value} is already globally paused" }
        require(run.status !in setOf(WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled)) {
            "Terminal workflow ${run.id.value} cannot be paused"
        }

        val report = requireNotNull(persistence.artifacts.get(reportArtifactId)) {
            "Hall Monitor report artifact ${reportArtifactId.value} does not exist"
        }
        require(report.kind == ArtifactKind.HallMonitorReport) {
            "Artifact ${reportArtifactId.value} is not a Hall Monitor report"
        }
        val reportTaskRun = requireNotNull(run.taskRuns.values.firstOrNull { it.id == report.taskRunId }) {
            "Hall Monitor report does not belong to a task in workflow ${run.id.value}"
        }
        require(reportTaskRun.assignedRoleId == HallMonitorRole.id) {
            "Hall Monitor report must be produced by the Hall Monitor role"
        }
        require(reportTaskRun.status == TaskRunStatus.Completed) {
            "Hall Monitor report task must be completed before review"
        }

        val review = requireNotNull(persistence.artifacts.get(antagonistReviewArtifactId)) {
            "Antagonist review artifact ${antagonistReviewArtifactId.value} does not exist"
        }
        require(review.kind == ArtifactKind.HallMonitorReview || review.kind == ArtifactKind.Review) {
            "Artifact ${antagonistReviewArtifactId.value} is not a Hall Monitor review"
        }
        val reviewTaskRun = requireNotNull(run.taskRuns.values.firstOrNull { it.id == review.taskRunId }) {
            "Antagonist review does not belong to a task in workflow ${run.id.value}"
        }
        require(reviewTaskRun.assignedRoleId == BuiltInRoles.Antagonist.id) {
            "Hall Monitor report must be reviewed by the Antagonist"
        }
        require(reviewTaskRun.status == TaskRunStatus.Completed) {
            "Antagonist review task must be completed before a global pause"
        }

        val reportId = report.metadata[HALL_MONITOR_REPORT_ID_METADATA]
            ?: reportArtifactId.value
        val reviewedReportId = review.metadata[HALL_MONITOR_REPORT_ID_METADATA]
        require(reviewedReportId == null || reviewedReportId == reportId) {
            "Antagonist review targets report '$reviewedReportId', not '$reportId'"
        }
        require(review.metadata[HALL_MONITOR_REVIEW_VERDICT_METADATA].equals(HALL_MONITOR_REVIEW_PASS, ignoreCase = true)) {
            "Hall Monitor report must PASS the Antagonist before a global pause can open"
        }

        val orchestratorGateId = ApprovalGateId("hall-monitor:orchestrator:${run.id.value}:$reportId")
        val humanGateId = ApprovalGateId("hall-monitor:human:${run.id.value}:$reportId")
        val reason = "Hall Monitor report '$reportId' passed Antagonist review and requires Orchestrator and user review."

        val snapshots = run.taskRuns.values
            .filterNot { it.status.isTerminal() }
            .map { taskRun ->
                PausedTaskSnapshot(
                    taskDefinitionId = taskRun.taskDefinitionId,
                    status = taskRun.status,
                    blockingReason = taskRun.blockingReason,
                    progress = taskRun.progress,
                    progressMessage = taskRun.progressMessage,
                )
            }

        val parkedRuns = run.taskRuns.mapValues { (_, taskRun) ->
            if (taskRun.status.isTerminal()) {
                taskRun
            } else {
                taskRun.copy(
                    status = TaskRunStatus.Blocked,
                    blockingReason = BlockingReason(HALL_MONITOR_GLOBAL_PAUSE_CODE, reason),
                    progressMessage = "Globally paused for Hall Monitor review",
                )
            }
        }
        val pausedRun = run.copy(
            status = WorkflowRunStatus.AwaitingHuman,
            taskRuns = parkedRuns,
            globalPause = WorkflowGlobalPause(
                kind = WorkflowGlobalPauseKind.HallMonitorReview,
                reason = reason,
                reportArtifactId = reportArtifactId,
                antagonistReviewArtifactId = antagonistReviewArtifactId,
                orchestratorGateId = orchestratorGateId,
                humanGateId = humanGateId,
                taskSnapshots = snapshots,
                pausedAtEpochMillis = nowEpochMillis,
            ),
            updatedAtEpochMillis = nowEpochMillis,
        )

        persistence.runs.put(pausedRun)
        ensureReviewGates(pausedRun, nowEpochMillis)

        // Detach live handles. Provider IDs/run IDs remain durable on each TaskRun and are used by
        // WorkflowRuntimeCoordinator.resume after the task states are restored.
        return WorkflowRuntimeState(run = pausedRun, handles = emptyMap())
    }

    suspend fun ensureReviewGates(
        run: WorkflowRun,
        nowEpochMillis: Long,
    ): ReviewGates {
        val pause = requireNotNull(run.globalPause) { "Workflow ${run.id.value} is not globally paused" }
        require(pause.kind == WorkflowGlobalPauseKind.HallMonitorReview) {
            "Workflow ${run.id.value} is paused for ${pause.kind}, not Hall Monitor review"
        }
        val orchestrator = gateCoordinator.open(
            id = pause.orchestratorGateId,
            workflowRunId = run.id,
            taskDefinitionId = null,
            kind = ApprovalGateKind.HallMonitorOrchestratorReview,
            reason = pause.reason,
            requiredRoleId = BuiltInRoles.Orchestrator.id,
            requiresHuman = false,
            nowEpochMillis = nowEpochMillis,
        )
        val human = gateCoordinator.open(
            id = pause.humanGateId,
            workflowRunId = run.id,
            taskDefinitionId = null,
            kind = ApprovalGateKind.HallMonitorHumanReview,
            reason = pause.reason,
            requiredRoleId = null,
            requiresHuman = true,
            nowEpochMillis = nowEpochMillis,
        )
        return ReviewGates(orchestrator, human)
    }

    suspend fun decideOrchestratorReview(
        run: WorkflowRun,
        approved: Boolean,
        note: String?,
        nowEpochMillis: Long,
        decidedByRoleId: RoleDefinitionId = BuiltInRoles.Orchestrator.id,
    ): ApprovalGate {
        val pause = requireNotNull(run.globalPause) { "Workflow ${run.id.value} is not globally paused" }
        require(decidedByRoleId == BuiltInRoles.Orchestrator.id) {
            "Hall Monitor Orchestrator review must be decided by the Orchestrator role"
        }
        ensureReviewGates(run, nowEpochMillis)
        return gateCoordinator.decide(
            id = pause.orchestratorGateId,
            approved = approved,
            decidedByRoleId = decidedByRoleId,
            note = note,
            nowEpochMillis = nowEpochMillis,
        )
    }

    suspend fun decideHumanReview(
        run: WorkflowRun,
        approved: Boolean,
        note: String?,
        nowEpochMillis: Long,
    ): ApprovalGate {
        val pause = requireNotNull(run.globalPause) { "Workflow ${run.id.value} is not globally paused" }
        ensureReviewGates(run, nowEpochMillis)
        return gateCoordinator.decide(
            id = pause.humanGateId,
            approved = approved,
            decidedByRoleId = null,
            note = note,
            nowEpochMillis = nowEpochMillis,
        )
    }

    suspend fun resumeIfFullyApproved(
        run: WorkflowRun,
        nowEpochMillis: Long,
    ): WorkflowRun {
        val pause = requireNotNull(run.globalPause) { "Workflow ${run.id.value} is not globally paused" }
        val gates = ensureReviewGates(run, nowEpochMillis)
        require(gates.orchestrator.status == ApprovalGateStatus.Approved) {
            "Orchestrator has not approved the Hall Monitor report"
        }
        require(gates.human.status == ApprovalGateStatus.Approved) {
            "User has not approved the Hall Monitor report"
        }

        val snapshots = pause.taskSnapshots.associateBy { it.taskDefinitionId }
        val restoredRuns = run.taskRuns.mapValues { (taskId, taskRun) ->
            val snapshot = snapshots[taskId] ?: return@mapValues taskRun
            taskRun.copy(
                status = snapshot.status,
                blockingReason = snapshot.blockingReason,
                progress = snapshot.progress,
                progressMessage = snapshot.progressMessage,
            )
        }
        val nextStatus = when {
            restoredRuns.values.all { it.status == TaskRunStatus.Completed } -> WorkflowRunStatus.Completed
            restoredRuns.values.any {
                it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
            } -> WorkflowRunStatus.AwaitingHuman
            else -> WorkflowRunStatus.Running
        }
        val resumed = run.copy(
            status = nextStatus,
            taskRuns = restoredRuns,
            globalPause = null,
            updatedAtEpochMillis = nowEpochMillis,
        )
        persistence.runs.put(resumed)
        return resumed
    }
}
