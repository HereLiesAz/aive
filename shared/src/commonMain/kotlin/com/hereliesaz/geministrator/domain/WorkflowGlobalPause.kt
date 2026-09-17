package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowGlobalPauseKind {
    HallMonitorReview,
}

/** Exact task state captured before a global pause so the same run can be resumed. */
@Serializable
data class PausedTaskSnapshot(
    val taskDefinitionId: TaskDefinitionId,
    val status: TaskRunStatus,
    val blockingReason: BlockingReason?,
    val progress: Float?,
    val progressMessage: String?,
)

/**
 * Durable run-level pause metadata.
 *
 * While this is present, all non-terminal tasks are parked in Blocked and runtime handles are
 * detached. Provider identifiers remain on the task runs so [WorkflowRuntimeCoordinator.resume]
 * can reconnect after the original statuses are restored.
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
) {
    init {
        require(reason.isNotBlank()) { "Global pause reason must not be blank" }
        require(taskSnapshots.map { it.taskDefinitionId }.distinct().size == taskSnapshots.size) {
            "Global pause task snapshots must be unique by task definition"
        }
    }
}
