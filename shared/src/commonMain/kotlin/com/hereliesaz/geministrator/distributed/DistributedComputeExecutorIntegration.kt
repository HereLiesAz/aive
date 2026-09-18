package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration

class DistributedComputeExecutorIntegration(
    private val gateway: DistributedComputeGateway,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.Distributed && gateway.isAvailable()

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val distributed = context.executor as? TaskExecutor.Distributed
            ?: error("DistributedComputeExecutorIntegration requires TaskExecutor.Distributed")
        val leaseId = leaseId(context)
        gateway.submit(
            DistributedTaskEnvelope(
                leaseId = leaseId,
                originNodeId = gateway.localNodeId,
                project = context.project,
                definition = context.definition,
                run = context.run,
                task = context.task,
                taskRun = context.taskRun,
                delegatedExecutor = distributed.delegate,
                requirements = distributed.requirements,
                submittedAtEpochMillis = context.nowEpochMillis,
            ),
        )
        return TaskExecutorExecution(
            status = TaskRunStatus.Running,
            externalRunId = leaseId,
            progress = 0f,
            progressMessage = "Waiting for an eligible compute node",
        )
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val leaseId = context.taskRun.externalRunId ?: leaseId(context)
        val state = gateway.leaseState(leaseId)
            ?: return TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = leaseId,
                progress = context.taskRun.progress,
                progressMessage = "Waiting for relay state",
            )

        return when (state.phase) {
            DistributedLeasePhase.Publishing,
            DistributedLeasePhase.Pending,
            -> TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = leaseId,
                progress = state.progress ?: 0f,
                progressMessage = state.progressMessage ?: "Waiting for an eligible compute node",
            )

            DistributedLeasePhase.Claimed -> TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = leaseId,
                progress = state.progress,
                progressMessage = "Running on " + (state.workerNodeId ?: "remote node"),
            )

            DistributedLeasePhase.Running -> TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = leaseId,
                progress = state.progress,
                progressMessage = state.progressMessage,
            )

            DistributedLeasePhase.Verifying -> TaskExecutorExecution(
                status = TaskRunStatus.Verifying,
                externalRunId = leaseId,
                progress = state.progress,
                progressMessage = state.progressMessage,
            )

            DistributedLeasePhase.Completed -> TaskExecutorExecution(
                status = TaskRunStatus.Completed,
                externalRunId = leaseId,
                artifacts = state.artifacts,
                progress = 1f,
                progressMessage = state.progressMessage ?: "Remote execution completed",
            )

            DistributedLeasePhase.Failed -> TaskExecutorExecution(
                status = TaskRunStatus.Failed,
                externalRunId = leaseId,
                artifacts = state.artifacts,
                progress = state.progress,
                progressMessage = state.failureMessage ?: state.progressMessage ?: "Remote execution failed",
            )

            DistributedLeasePhase.Cancelled -> TaskExecutorExecution(
                status = TaskRunStatus.Failed,
                externalRunId = leaseId,
                progress = state.progress,
                progressMessage = state.progressMessage ?: "Remote execution was cancelled",
            )
        }
    }

    private fun leaseId(context: TaskExecutorContext): String =
        listOf(
            "distributed",
            context.run.id.value,
            context.taskRun.id.value,
            context.taskRun.attempt.toString(),
        ).joinToString(":")
}
