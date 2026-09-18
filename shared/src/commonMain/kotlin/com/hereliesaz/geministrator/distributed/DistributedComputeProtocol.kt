package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ComputeAccelerator
import com.hereliesaz.geministrator.domain.ComputePlatform
import com.hereliesaz.geministrator.domain.DistributedComputeRequirements
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DISTRIBUTED_COMPUTE_PROTOCOL_VERSION: Int = 1

@Serializable
data class ComputeNodeDescriptor(
    val nodeId: String,
    val displayName: String,
    val platform: ComputePlatform,
    val architecture: String,
    val logicalProcessors: Int,
    val memoryMiB: Long,
    val accelerators: Set<ComputeAccelerator> = setOf(ComputeAccelerator.Cpu),
    val capabilities: Set<String> = emptySet(),
    val supportedExecutorKinds: Set<String> = emptySet(),
    val installedModelIds: Set<String> = emptySet(),
    val maxParallelLeases: Int = 1,
    val acceptsWork: Boolean = true,
    val meteredNetwork: Boolean = false,
    val onExternalPower: Boolean? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(architecture.isNotBlank()) { "architecture must not be blank" }
        require(logicalProcessors >= 1) { "logicalProcessors must be at least 1" }
        require(memoryMiB >= 0) { "memoryMiB must not be negative" }
        require(maxParallelLeases >= 1) { "maxParallelLeases must be at least 1" }
        require(capabilities.none(String::isBlank)) { "capabilities must not contain blanks" }
        require(supportedExecutorKinds.none(String::isBlank)) { "supportedExecutorKinds must not contain blanks" }
        require(installedModelIds.none(String::isBlank)) { "installedModelIds must not contain blanks" }
    }

    fun canRun(
        requirements: DistributedComputeRequirements,
        delegatedExecutor: TaskExecutor,
    ): Boolean {
        if (!acceptsWork) return false
        if (logicalProcessors < requirements.minLogicalProcessors) return false
        if (memoryMiB < requirements.minMemoryMiB) return false
        if (!accelerators.containsAll(requirements.requiredAccelerators)) return false
        if (!capabilities.containsAll(requirements.requiredCapabilities)) return false
        if (!installedModelIds.containsAll(requirements.requiredModelIds)) return false
        val kind = delegatedExecutor.distributedKind()
        return supportedExecutorKinds.isEmpty() || kind in supportedExecutorKinds
    }

    fun preferenceScore(requirements: DistributedComputeRequirements): Int {
        var score = requirements.preferredCapabilities.count(capabilities::contains) * 10
        if (nodeId in requirements.preferredNodeIds) score += 100
        if (onExternalPower == true) score += 2
        if (!meteredNetwork) score += 1
        return score
    }
}

fun TaskExecutor.distributedKind(): String = when (this) {
    is TaskExecutor.RoleAgent -> "role-agent"
    is TaskExecutor.GitHubAction -> "github-action"
    is TaskExecutor.TestRunner -> "test-runner"
    is TaskExecutor.Deployment -> "deployment"
    is TaskExecutor.RepositoryOperation -> "repository-operation"
    is TaskExecutor.HumanApproval -> "human-approval"
    is TaskExecutor.ExternalService -> "external-service"
    is TaskExecutor.NestedWorkflow -> "nested-workflow"
    is TaskExecutor.Distributed -> "distributed"
}

@Serializable
data class DistributedTaskEnvelope(
    val leaseId: String,
    val originNodeId: String,
    val project: Project,
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val task: TaskDefinition,
    val taskRun: TaskRun,
    val role: RoleDefinition? = null,
    val delegatedExecutor: TaskExecutor,
    val requirements: DistributedComputeRequirements,
    val submittedAtEpochMillis: Long,
    val leaseDurationMillis: Long = 30_000,
) {
    init {
        require(leaseId.isNotBlank()) { "leaseId must not be blank" }
        require(originNodeId.isNotBlank()) { "originNodeId must not be blank" }
        require(leaseDurationMillis >= 5_000) { "leaseDurationMillis must be at least 5 seconds" }
        require(delegatedExecutor !is TaskExecutor.Distributed) {
            "DistributedTaskEnvelope must carry the unwrapped delegated executor"
        }
        if (delegatedExecutor is TaskExecutor.RoleAgent) {
            require(role?.id == delegatedExecutor.roleId) {
                "Distributed role-agent leases require the resolved matching role definition"
            }
        }
    }
}

@Serializable
data class DistributedExecutionProgress(
    val status: TaskRunStatus,
    val progress: Float? = null,
    val message: String? = null,
) {
    init {
        require(status in setOf(TaskRunStatus.Running, TaskRunStatus.Verifying)) {
            "Distributed progress may only report Running or Verifying"
        }
        require(progress == null || progress in 0f..1f) { "progress must be normalized 0f..1f" }
    }
}

@Serializable
data class DistributedExecutionResult(
    val status: TaskRunStatus,
    val artifacts: List<ArtifactRef> = emptyList(),
    val progressMessage: String? = null,
    val failureMessage: String? = null,
) {
    init {
        require(status == TaskRunStatus.Completed || status == TaskRunStatus.Failed) {
            "Distributed result must be Completed or Failed"
        }
        require(status != TaskRunStatus.Failed || !failureMessage.isNullOrBlank()) {
            "Failed distributed results require a failureMessage"
        }
    }
}

@Serializable
sealed interface ComputeRelayClientMessage {
    @Serializable
    @SerialName("register")
    data class Register(
        val protocolVersion: Int = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
        val node: ComputeNodeDescriptor,
    ) : ComputeRelayClientMessage

    @Serializable
    @SerialName("update_node")
    data class UpdateNode(val node: ComputeNodeDescriptor) : ComputeRelayClientMessage

    @Serializable
    @SerialName("publish_lease")
    data class PublishLease(val envelope: DistributedTaskEnvelope) : ComputeRelayClientMessage

    @Serializable
    @SerialName("claim_lease")
    data class ClaimLease(val leaseId: String) : ComputeRelayClientMessage

    @Serializable
    @SerialName("lease_heartbeat")
    data class LeaseHeartbeat(val leaseId: String) : ComputeRelayClientMessage

    @Serializable
    @SerialName("lease_progress")
    data class LeaseProgress(
        val leaseId: String,
        val progress: DistributedExecutionProgress,
    ) : ComputeRelayClientMessage

    @Serializable
    @SerialName("complete_lease")
    data class CompleteLease(
        val leaseId: String,
        val result: DistributedExecutionResult,
    ) : ComputeRelayClientMessage

    @Serializable
    @SerialName("cancel_lease")
    data class CancelLease(
        val leaseId: String,
        val reason: String? = null,
    ) : ComputeRelayClientMessage

    @Serializable
    @SerialName("node_heartbeat")
    data object NodeHeartbeat : ComputeRelayClientMessage
}

@Serializable
sealed interface ComputeRelayServerMessage {
    @Serializable
    @SerialName("registered")
    data class Registered(
        val protocolVersion: Int = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
        val nodeId: String,
        val onlineNodes: List<ComputeNodeDescriptor>,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("node_joined")
    data class NodeJoined(val node: ComputeNodeDescriptor) : ComputeRelayServerMessage

    @Serializable
    @SerialName("node_updated")
    data class NodeUpdated(val node: ComputeNodeDescriptor) : ComputeRelayServerMessage

    @Serializable
    @SerialName("node_left")
    data class NodeLeft(val nodeId: String) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_accepted")
    data class LeaseAccepted(val leaseId: String) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_offered")
    data class LeaseOffered(val envelope: DistributedTaskEnvelope) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_claimed")
    data class LeaseClaimed(
        val leaseId: String,
        val workerNodeId: String,
        val expiresAtEpochMillis: Long,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_requeued")
    data class LeaseRequeued(
        val leaseId: String,
        val reason: String,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_progress")
    data class LeaseProgress(
        val leaseId: String,
        val workerNodeId: String,
        val progress: DistributedExecutionProgress,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_completed")
    data class LeaseCompleted(
        val leaseId: String,
        val workerNodeId: String,
        val result: DistributedExecutionResult,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("lease_cancelled")
    data class LeaseCancelled(
        val leaseId: String,
        val reason: String? = null,
    ) : ComputeRelayServerMessage

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
        val leaseId: String? = null,
    ) : ComputeRelayServerMessage
}

enum class DistributedLeasePhase {
    Publishing,
    Pending,
    Claimed,
    Running,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

data class DistributedLeaseState(
    val leaseId: String,
    val phase: DistributedLeasePhase,
    val workerNodeId: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val progress: Float? = null,
    val progressMessage: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val failureMessage: String? = null,
)
