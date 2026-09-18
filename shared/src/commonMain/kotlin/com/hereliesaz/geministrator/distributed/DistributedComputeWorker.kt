package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.isSystemExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

fun interface DistributedWorkloadRunner {
    suspend fun run(
        envelope: DistributedTaskEnvelope,
        onProgress: suspend (DistributedExecutionProgress) -> Unit,
    ): DistributedExecutionResult

    fun supports(envelope: DistributedTaskEnvelope): Boolean = true
}

class SystemExecutorDistributedWorkloadRunner(
    private val integrations: TaskExecutorIntegrationRegistry,
    private val nowEpochMillis: () -> Long,
    private val pollIntervalMillis: Long = 1_000,
) : DistributedWorkloadRunner {
    override fun supports(envelope: DistributedTaskEnvelope): Boolean =
        envelope.delegatedExecutor.isSystemExecutor() &&
            envelope.delegatedExecutor !is TaskExecutor.Distributed &&
            integrations.isAvailable(envelope.delegatedExecutor, envelope.project)

    override suspend fun run(
        envelope: DistributedTaskEnvelope,
        onProgress: suspend (DistributedExecutionProgress) -> Unit,
    ): DistributedExecutionResult {
        val integration = integrations.integrationFor(envelope.delegatedExecutor, envelope.project)
            ?: return DistributedExecutionResult(
                status = TaskRunStatus.Failed,
                failureMessage = "No local integration can execute " + envelope.delegatedExecutor.distributedKind(),
            )

        val delegatedTask = envelope.task.copy(executor = envelope.delegatedExecutor)
        var delegatedTaskRun = envelope.taskRun.copy(
            status = TaskRunStatus.Ready,
            executor = envelope.delegatedExecutor,
            assignedProviderId = null,
            providerRunId = null,
            externalRunId = null,
            blockingReason = null,
            progress = null,
            progressMessage = null,
        )
        var delegatedRun = envelope.run.copy(
            taskRuns = envelope.run.taskRuns + (delegatedTask.id to delegatedTaskRun),
        )

        suspend fun context(): TaskExecutorContext = TaskExecutorContext(
            project = envelope.project,
            definition = envelope.definition,
            run = delegatedRun,
            task = delegatedTask,
            taskRun = delegatedTaskRun,
            executor = envelope.delegatedExecutor,
            nowEpochMillis = nowEpochMillis(),
        )

        var execution = integration.dispatch(context())
        while (true) {
            delegatedTaskRun = delegatedTaskRun.apply(execution)
            delegatedRun = delegatedRun.copy(
                taskRuns = delegatedRun.taskRuns + (delegatedTask.id to delegatedTaskRun),
                updatedAtEpochMillis = nowEpochMillis(),
            )

            when (execution.status) {
                TaskRunStatus.Completed -> return DistributedExecutionResult(
                    status = TaskRunStatus.Completed,
                    artifacts = execution.artifacts,
                    progressMessage = execution.progressMessage,
                )
                TaskRunStatus.Failed -> return DistributedExecutionResult(
                    status = TaskRunStatus.Failed,
                    artifacts = execution.artifacts,
                    progressMessage = execution.progressMessage,
                    failureMessage = execution.progressMessage ?: "Remote executor failed",
                )
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
                -> onProgress(
                    DistributedExecutionProgress(
                        status = execution.status,
                        progress = execution.progress,
                        message = execution.progressMessage,
                    ),
                )
                else -> error("Unsupported distributed executor status " + execution.status)
            }

            delay(pollIntervalMillis)
            execution = integration.reconcile(context())
        }
    }

    private fun TaskRun.apply(execution: TaskExecutorExecution): TaskRun = copy(
        status = execution.status,
        externalRunId = execution.externalRunId ?: externalRunId,
        artifacts = if (execution.artifacts.isEmpty()) artifacts else execution.artifacts,
        progress = execution.progress ?: progress,
        progressMessage = execution.progressMessage ?: progressMessage,
    )
}

class DistributedComputeWorker(
    private val client: RelayDistributedComputeClient,
    private val node: ComputeNodeDescriptor,
    private val runners: List<DistributedWorkloadRunner>,
    private val scope: CoroutineScope,
) {
    private val activeMutex = Mutex()
    private val activeLeaseIds = linkedSetOf<String>()
    private var offerJob: Job? = null

    fun start() {
        client.start(scope)
        if (offerJob?.isActive == true) return
        offerJob = scope.launch {
            client.offers.collect { envelope ->
                if (envelope.originNodeId == node.nodeId) return@collect
                if (!node.canRun(envelope.requirements, envelope.delegatedExecutor)) return@collect
                val runner = runners.firstOrNull { it.supports(envelope) } ?: return@collect
                val accepted = activeMutex.withLock {
                    if (activeLeaseIds.size >= node.maxParallelLeases || envelope.leaseId in activeLeaseIds) {
                        false
                    } else {
                        activeLeaseIds += envelope.leaseId
                        true
                    }
                }
                if (!accepted) return@collect
                scope.launch { executeClaimed(envelope, runner) }
            }
        }
    }

    suspend fun stop() {
        offerJob?.cancel()
        offerJob = null
        client.stop()
    }

    private suspend fun executeClaimed(
        envelope: DistributedTaskEnvelope,
        runner: DistributedWorkloadRunner,
    ) {
        try {
            client.claim(envelope.leaseId)
            val claimed = withTimeoutOrNull(5_000) {
                client.leaseStates
                    .map { states -> states[envelope.leaseId] }
                    .first { state ->
                        state?.phase == DistributedLeasePhase.Claimed &&
                            state.workerNodeId == node.nodeId
                    }
            }
            if (claimed == null) return

            val heartbeat = scope.launch {
                val delayMillis = (envelope.leaseDurationMillis / 3).coerceAtLeast(1_000)
                while (true) {
                    delay(delayMillis)
                    client.heartbeatLease(envelope.leaseId)
                }
            }
            try {
                val result = runner.run(envelope) { progress ->
                    client.progress(envelope.leaseId, progress)
                }
                client.complete(envelope.leaseId, result)
            } finally {
                heartbeat.cancel()
            }
        } catch (failure: Throwable) {
            runCatching {
                client.complete(
                    envelope.leaseId,
                    DistributedExecutionResult(
                        status = TaskRunStatus.Failed,
                        failureMessage = failure.message ?: failure::class.simpleName ?: "Remote execution failed",
                    ),
                )
            }
        } finally {
            activeMutex.withLock { activeLeaseIds -= envelope.leaseId }
        }
    }
}
