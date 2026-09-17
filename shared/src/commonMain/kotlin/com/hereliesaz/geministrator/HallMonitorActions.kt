package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.workflow.HallMonitorGovernanceService
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Opens the Hall Monitor's run-level pause after a completed Hall Monitor report has passed a cold
 * Antagonist review. The pause is persisted before [refresh] reloads the runtime without live handles.
 */
suspend fun ApplicationRuntime.pauseForHallMonitorReview(
    reportArtifactId: ArtifactId,
    antagonistReviewArtifactId: ArtifactId,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    val service = HallMonitorGovernanceService(persistence)
    service.pauseAfterAntagonistPass(
        state = WorkflowRuntimeState(live.presentation.run),
        reportArtifactId = reportArtifactId,
        antagonistReviewArtifactId = antagonistReviewArtifactId,
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

suspend fun ApplicationRuntime.decideHallMonitorOrchestratorReview(
    approved: Boolean,
    note: String? = null,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).decideOrchestratorReview(
        run = live.presentation.run,
        approved = approved,
        note = note,
        nowEpochMillis = nowEpochMillis,
        decidedByRoleId = BuiltInRoles.Orchestrator.id,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.decideHallMonitorOrchestratorReview(
    approved: Boolean,
    note: String? = null,
) = decideHallMonitorOrchestratorReview(
    approved = approved,
    note = note,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

suspend fun ApplicationRuntime.decideHallMonitorUserReview(
    approved: Boolean,
    note: String? = null,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
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
 * Restores the exact task states captured at pause time. Both Hall Monitor review gates must already
 * be approved. [refresh] then reconnects provider sessions from the restored task/provider IDs.
 */
suspend fun ApplicationRuntime.resumeHallMonitorPause(nowEpochMillis: Long) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).resumeIfFullyApproved(
        run = live.presentation.run,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.resumeHallMonitorPause() =
    resumeHallMonitorPause(Clock.System.now().toEpochMilliseconds())
