package com.hereliesaz.geministrator.mesh

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ComputeLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "Compute lease ID must not be blank" }
    }
}

@Serializable
@JvmInline
value class ComputeLeaseGroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "Compute lease group ID must not be blank" }
    }
}

@Serializable
data class RemoteWorkSlice(
    val index: Int = 0,
    val count: Int = 1,
) {
    init {
        require(count >= 1)
        require(index in 0 until count)
    }
}

@Serializable
data class RemoteWorkLease(
    val id: ComputeLeaseId,
    val groupId: ComputeLeaseGroupId,
    val originDeviceId: ComputeDeviceId,
    val targetDeviceId: ComputeDeviceId,
    val project: Project,
    val workflowDefinition: WorkflowDefinition,
    val workflowRun: WorkflowRun,
    val task: TaskDefinition,
    val taskRun: TaskRun,
    val slice: RemoteWorkSlice = RemoteWorkSlice(),
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(expiresAtEpochMillis > issuedAtEpochMillis) {
            "Remote work lease expiration must be after issue time"
        }
    }
}

@Serializable
data class RemoteWorkProgress(
    val leaseId: ComputeLeaseId,
    val groupId: ComputeLeaseGroupId,
    val deviceId: ComputeDeviceId,
    val status: TaskRunStatus,
    val progress: Float? = null,
    val message: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val updatedAtEpochMillis: Long,
) {
    init {
        require(
            status in setOf(
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
                TaskRunStatus.Completed,
                TaskRunStatus.Failed,
                TaskRunStatus.Cancelled,
            ),
        ) { "Remote work may only report active or terminal execution states" }
        require(progress == null || progress in 0f..1f)
    }
}

@Serializable
sealed interface ComputeMeshMessage {
    @Serializable
    data class Advertisement(
        val device: ComputeDeviceAdvertisement,
    ) : ComputeMeshMessage

    @Serializable
    data class LeaseOffered(
        val lease: RemoteWorkLease,
    ) : ComputeMeshMessage

    @Serializable
    data class LeaseAccepted(
        val leaseId: ComputeLeaseId,
        val deviceId: ComputeDeviceId,
    ) : ComputeMeshMessage

    @Serializable
    data class LeaseRejected(
        val leaseId: ComputeLeaseId,
        val deviceId: ComputeDeviceId,
        val reason: String,
    ) : ComputeMeshMessage

    @Serializable
    data class Progress(
        val value: RemoteWorkProgress,
    ) : ComputeMeshMessage

    @Serializable
    data class CancelLease(
        val leaseId: ComputeLeaseId,
        val reason: String,
    ) : ComputeMeshMessage
}

/**
 * Relay-neutral mesh transport.
 *
 * Implementations may use an internet relay, a direct peer channel, or a test loopback. Workflow
 * scheduling depends only on these semantics and never on LAN reachability.
 */
interface ComputeMeshTransport {
    suspend fun publishAdvertisement(device: ComputeDeviceAdvertisement)

    suspend fun pairedDevices(nowEpochMillis: Long): List<ComputeDeviceAdvertisement>

    suspend fun offerLease(lease: RemoteWorkLease): Boolean

    suspend fun progress(groupId: ComputeLeaseGroupId): List<RemoteWorkProgress>

    suspend fun cancel(groupId: ComputeLeaseGroupId, reason: String)

    suspend fun incomingLeases(afterToken: String? = null): ComputeMeshInbox

    suspend fun respondToLease(
        leaseId: ComputeLeaseId,
        targetDeviceId: ComputeDeviceId,
        accepted: Boolean,
        reason: String? = null,
    )

    suspend fun reportProgress(progress: RemoteWorkProgress)
}

data class ComputeMeshInbox(
    val leases: List<RemoteWorkLease>,
    val nextToken: String?,
)

fun interface RemoteWorkExecutor {
    suspend fun execute(
        lease: RemoteWorkLease,
        reportProgress: suspend (RemoteWorkProgress) -> Unit,
    ): RemoteWorkProgress
}
