package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowGlobalPauseKind {
    HallMonitorReview,
}

/** Exact task state captured before a global pause so the same run can be resumed safely. */
@Serializable
data class PausedTaskSnapshot(
    val taskDefinitionId: TaskDefinitionId,
    val status: TaskRunStatus,
    val blockingReason: BlockingReason?,
    val progress: Float?,
    val progressMessage: String?,
    /** True when a live provider session was cancelled to make the global pause real. */
    val resumeAsRetry: Boolean = false,
)

/**
 * Durable run-level pause metadata.
 *
 * While this is present, all ordinary non-terminal tasks are parked in Blocked. Live provider work
 * is cancelled before the pause is committed and is re-dispatched as a retry after resume. The
 * optional provider/run IDs below belong only to the governance-side Orchestrator review, which may
 * continue while the ordinary workflow is globally paused.
 */
@Serializable
data class WorkflowGlobalPause(
    val kind: WorkflowGlobalPauseKind,
    val reason: String,
    val reportArtifactId: ArtifactId,
    val antagonistReviewArtifactId: ArtifactId,
    val orchestratorGateId: ApprovalGateId,
    val humanGateId: ApprovalGateId,
    val taskSnapshots: List<PausedTaskSnapshot>,
    val pausedAtEpochMillis: Long,
    val orchestratorReviewProviderId: AgentProviderId? = null,
    val orchestratorReviewProviderRunId: ProviderRunId? = null,
) {
    init {
        require(reason.isNotBlank()) { "Global pause reason must not be blank" }
        require(taskSnapshots.map { it.taskDefinitionId }.distinct().size == taskSnapshots.size) {
            "Global pause task snapshots must be unique by task definition"
        }
        require(
            (orchestratorReviewProviderId == null) == (orchestratorReviewProviderRunId == null),
        ) { "Orchestrator review provider and run IDs must be present together" }
    }
}
