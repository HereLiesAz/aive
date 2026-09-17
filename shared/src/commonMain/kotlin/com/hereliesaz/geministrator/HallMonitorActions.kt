package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.HallMonitorGovernanceService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class HallMonitorPauseReviewState(
    val reason: String,
    val reportText: String,
    val antagonistReviewText: String,
    val orchestratorStatus: ApprovalGateStatus,
    val orchestratorReviewText: String?,
    val userStatus: ApprovalGateStatus,
    val userDecisionNote: String?,
) {
    val bothReviewsResolved: Boolean
        get() = orchestratorStatus.isResolved && userStatus.isResolved

    val recommendationsApproved: Boolean
        get() = orchestratorStatus == ApprovalGateStatus.Approved && userStatus == ApprovalGateStatus.Approved

    private val ApprovalGateStatus.isResolved: Boolean
        get() = this == ApprovalGateStatus.Approved || this == ApprovalGateStatus.Rejected
}

/** Manual entry point retained for diagnostics; normal Hall Monitor workflows auto-open the pause. */
suspend fun ApplicationRuntime.pauseForHallMonitorReview(
    reportArtifactId: ArtifactId,
    antagonistReviewArtifactId: ArtifactId,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    val resumed = coordinator.resume(live.presentation.run.id)
    HallMonitorGovernanceService(persistence).pauseAfterAntagonistPass(
        state = resumed,
        reportArtifactId = reportArtifactId,
        antagonistReviewArtifactId = antagonistReviewArtifactId,
        sessionGateway = sessionGateway,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.pauseForHallMonitorReview(
    reportArtifactId: ArtifactId,
    antagonistReviewArtifactId: ArtifactId,
) = pauseForHallMonitorReview(
    reportArtifactId = reportArtifactId,
    antagonistReviewArtifactId = antagonistReviewArtifactId,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

suspend fun ApplicationRuntime.loadHallMonitorPauseReviewState(): HallMonitorPauseReviewState? {
    val live = state.value as? ApplicationRuntimeState.Live ?: return null
    val pause = live.presentation.run.globalPause ?: return null
    val report = persistence.artifacts.get(pause.reportArtifactId)
    val antagonist = persistence.artifacts.get(pause.antagonistReviewArtifactId)
    val orchestratorGate = persistence.approvalGates.get(pause.orchestratorGateId) ?: return null
    val humanGate = persistence.approvalGates.get(pause.humanGateId) ?: return null
    return HallMonitorPauseReviewState(
        reason = pause.reason,
        reportText = report?.textContent.orEmpty(),
        antagonistReviewText = antagonist?.textContent.orEmpty(),
        orchestratorStatus = orchestratorGate.status,
        orchestratorReviewText = orchestratorGate.decisionNote,
        userStatus = humanGate.status,
        userDecisionNote = humanGate.decisionNote,
    )
}

suspend fun ApplicationRuntime.decideHallMonitorUserReview(
    approved: Boolean,
    note: String? = null,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).decideHumanReview(
        run = live.presentation.run,
        approved = approved,
        note = note,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.decideHallMonitorUserReview(
    approved: Boolean,
    note: String? = null,
) = decideHallMonitorUserReview(
    approved = approved,
    note = note,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

/**
 * Resume the snapshotted workflow after both the Orchestrator and user have reviewed the report.
 * A rejection means the recommendations remain unapplied; it does not trap the original workflow.
 */
suspend fun ApplicationRuntime.resumeHallMonitorPause(nowEpochMillis: Long) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).resumeAfterReviews(
        run = live.presentation.run,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.resumeHallMonitorPause() =
    resumeHallMonitorPause(Clock.System.now().toEpochMilliseconds())
