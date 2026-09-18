package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ComputePlacementPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.mesh.ComputeDeviceAdvertisement
import com.hereliesaz.geministrator.mesh.ComputeLeaseGroupId
import com.hereliesaz.geministrator.mesh.ComputeLeaseId
import com.hereliesaz.geministrator.mesh.ComputeMeshScheduler
import com.hereliesaz.geministrator.mesh.ComputeMeshTransport
import com.hereliesaz.geministrator.mesh.ComputeParticipant
import com.hereliesaz.geministrator.mesh.ComputePlacementPlan
import com.hereliesaz.geministrator.mesh.RemoteWorkLease
import com.hereliesaz.geministrator.mesh.RemoteWorkProgress
import com.hereliesaz.geministrator.mesh.RemoteWorkSlice

data class RemoteComputeContext(
    val project: Project,
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val task: TaskDefinition,
    val taskRun: TaskRun,
    val nowEpochMillis: Long,
)

sealed interface RemoteComputeDispatch {
    data object Local : RemoteComputeDispatch

    data class Started(
        val externalRunId: String,
        val progressMessage: String,
    ) : RemoteComputeDispatch

    data class Blocked(val reason: String) : RemoteComputeDispatch
}

data class RemoteComputeUpdate(
    val status: TaskRunStatus,
    val progress: Float? = null,
    val progressMessage: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
) {
    init {
        require(
            status in setOf(
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
                TaskRunStatus.Completed,
                TaskRunStatus.Failed,
            ),
        )
        require(progress == null || progress in 0f..1f)
    }
}

interface RemoteComputeCoordinator {
    fun owns(taskRun: TaskRun): Boolean

    suspend fun dispatch(context: RemoteComputeContext): RemoteComputeDispatch

    suspend fun reconcile(context: RemoteComputeContext): RemoteComputeUpdate?
}

/**
 * Routes workflow tasks through the paired-device compute mesh.
 *
 * The authoritative [WorkflowRun] never leaves the originating device. A remote lease carries a
 * snapshot needed to perform one attempt/slice; only normalized progress and artifacts are merged
 * back into the authoritative run.
 */
class MeshRemoteComputeCoordinator(
    private val localDevice: ComputeDeviceAdvertisement,
    private val transport: ComputeMeshTransport,
    private val leaseIdFactory: (TaskRun, Int, Int, Long) -> ComputeLeaseId = { taskRun, slice, attempt, now ->
        ComputeLeaseId("${taskRun.id.value}:$attempt:$slice:$now")
    },
    private val groupIdFactory: (TaskRun, Long) -> ComputeLeaseGroupId = { taskRun, now ->
        ComputeLeaseGroupId("${taskRun.id.value}:${taskRun.attempt}:$now")
    },
    private val leaseDurationMillis: Long = 10 * 60 * 1000L,
) : RemoteComputeCoordinator {
    private val scheduler = ComputeMeshScheduler(localDevice)

    init {
        require(leaseDurationMillis > 0)
    }

    override fun owns(taskRun: TaskRun): Boolean =
        taskRun.externalRunId?.startsWith(EXTERNAL_RUN_PREFIX) == true

    override suspend fun dispatch(context: RemoteComputeContext): RemoteComputeDispatch {
        if (context.task.computePlacement == ComputePlacementPolicy.LocalOnly) {
            return RemoteComputeDispatch.Local
        }

        transport.publishAdvertisement(localDevice.copy(lastSeenEpochMillis = context.nowEpochMillis))
        val peers = transport.pairedDevices(context.nowEpochMillis)
            .filterNot { it.id == localDevice.id }
        return when (
            val placement = scheduler.plan(
                policy = context.task.computePlacement,
                pairedDevices = peers,
            )
        ) {
            ComputePlacementPlan.Local -> RemoteComputeDispatch.Local
            is ComputePlacementPlan.Blocked -> RemoteComputeDispatch.Blocked(placement.reason)
            is ComputePlacementPlan.Remote -> start(
                context = context,
                devices = listOf(placement.device),
                localParticipant = false,
            )
            is ComputePlacementPlan.Distributed -> {
                val participants = placement.participants.map { participant ->
                    participant.resolveDevice(localDevice)
                }
                start(
                    context = context,
                    devices = participants,
                    localParticipant = placement.participants.any(ComputeParticipant::local),
                )
            }
        }
    }

    override suspend fun reconcile(context: RemoteComputeContext): RemoteComputeUpdate? {
        val groupId = context.taskRun.externalRunId
            ?.takeIf { it.startsWith(EXTERNAL_RUN_PREFIX) }
            ?.removePrefix(EXTERNAL_RUN_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let(::ComputeLeaseGroupId)
            ?: return null

        val updates = transport.progress(groupId)
        if (updates.isEmpty()) {
            return RemoteComputeUpdate(
                status = context.taskRun.status.takeIf {
                    it == TaskRunStatus.Running || it == TaskRunStatus.Verifying
                } ?: TaskRunStatus.Running,
                progress = context.taskRun.progress,
                progressMessage = context.taskRun.progressMessage ?: "Waiting for compute mesh update",
            )
        }

        val status = aggregateStatus(updates)
        val progressValues = updates.mapNotNull(RemoteWorkProgress::progress)
        val progress = when {
            status == TaskRunStatus.Completed -> 1f
            progressValues.isEmpty() -> context.taskRun.progress
            else -> progressValues.average().toFloat().coerceIn(0f, 1f)
        }
        val artifacts = if (status == TaskRunStatus.Completed) {
            updates.flatMap(RemoteWorkProgress::artifacts).distinctBy { it.id }
        } else {
            updates.flatMap(RemoteWorkProgress::artifacts).distinctBy { it.id }
        }
        val activeDevices = updates.count {
            it.status == TaskRunStatus.Running || it.status == TaskRunStatus.Verifying
        }

        return RemoteComputeUpdate(
            status = status,
            progress = progress,
            progressMessage = when (status) {
                TaskRunStatus.Completed -> "Compute mesh completed"
                TaskRunStatus.Failed -> updates.firstOrNull { it.status == TaskRunStatus.Failed }?.message
                    ?: "A remote compute slice failed"
                TaskRunStatus.Verifying -> "Compute mesh verifying results"
                else -> "Compute mesh running on $activeDevices device(s)"
            },
            artifacts = artifacts,
        )
    }

    private suspend fun start(
        context: RemoteComputeContext,
        devices: List<ComputeDeviceAdvertisement>,
        localParticipant: Boolean,
    ): RemoteComputeDispatch {
        if (devices.isEmpty()) return RemoteComputeDispatch.Blocked("No compute devices selected")
        val groupId = groupIdFactory(context.taskRun, context.nowEpochMillis)
        val leases = devices.mapIndexed { index, device ->
            RemoteWorkLease(
                id = leaseIdFactory(context.taskRun, index, context.taskRun.attempt, context.nowEpochMillis),
                groupId = groupId,
                originDeviceId = localDevice.id,
                targetDeviceId = device.id,
                project = context.project,
                workflowDefinition = context.definition,
                workflowRun = context.run,
                task = context.task,
                taskRun = context.taskRun,
                slice = RemoteWorkSlice(index = index, count = devices.size),
                issuedAtEpochMillis = context.nowEpochMillis,
                expiresAtEpochMillis = context.nowEpochMillis + leaseDurationMillis,
            )
        }

        val accepted = mutableListOf<RemoteWorkLease>()
        for (lease in leases) {
            if (transport.offerLease(lease)) {
                accepted += lease
            } else {
                transport.cancel(groupId, "A selected compute peer rejected the lease group")
                return RemoteComputeDispatch.Blocked(
                    "Compute peer ${lease.targetDeviceId.value} rejected the task lease",
                )
            }
        }

        val remoteCount = devices.count { it.id != localDevice.id }
        val message = if (devices.size == 1) {
            val device = devices.single()
            if (device.id == localDevice.id) {
                "Compute mesh executing locally"
            } else {
                "Compute offloaded to ${device.displayName}"
            }
        } else {
            "Distributed across ${devices.size} devices" +
                if (localParticipant) " (local + $remoteCount remote)" else ""
        }
        return RemoteComputeDispatch.Started(
            externalRunId = EXTERNAL_RUN_PREFIX + groupId.value,
            progressMessage = message,
        )
    }

    private fun ComputeParticipant.resolveDevice(
        local: ComputeDeviceAdvertisement,
    ): ComputeDeviceAdvertisement = device ?: local

    private fun aggregateStatus(updates: List<RemoteWorkProgress>): TaskRunStatus {
        if (updates.any { it.status == TaskRunStatus.Failed || it.status == TaskRunStatus.Cancelled }) {
            return TaskRunStatus.Failed
        }
        if (updates.all { it.status == TaskRunStatus.Completed }) return TaskRunStatus.Completed
        if (updates.any { it.status == TaskRunStatus.Verifying } &&
            updates.none { it.status == TaskRunStatus.Running }
        ) {
            return TaskRunStatus.Verifying
        }
        return TaskRunStatus.Running
    }

    private companion object {
        const val EXTERNAL_RUN_PREFIX = "mesh:"
    }
}
