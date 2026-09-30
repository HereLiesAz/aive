package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.isMutation
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

/**
 * Runs pool leases with this device's own integrations and credentials.
 *
 * A lease is only a request from another pool member, so it is re-checked here before anything
 * runs: the workflow must validate, the task must really be a distributed placement of the
 * delegated executor, a mutating repository operation must have a completed human-approval gate
 * in the submitted run, and work that uses this device's repository credentials must target a
 * repository of a project linked on this device ([trustedRepositories]).
 */
class SystemExecutorDistributedWorkloadRunner(
    private val integrations: TaskExecutorIntegrationRegistry,
    private val nowEpochMillis: () -> Long,
    private val pollIntervalMillis: Long = 1_000,
    private val trustedRepositories: suspend () -> Collection<RepositoryRef> = { emptyList() },
) : DistributedWorkloadRunner {
    override fun supports(envelope: DistributedTaskEnvelope): Boolean =
        envelope.delegatedExecutor.isSystemExecutor() &&
            envelope.delegatedExecutor !is TaskExecutor.Distributed &&
            integrations.isAvailable(envelope.delegatedExecutor, envelope.project)

    override suspend fun run(
        envelope: DistributedTaskEnvelope,
        onProgress: suspend (DistributedExecutionProgress) -> Unit,
    ): DistributedExecutionResult {
        leaseRefusal(envelope)?.let { reason ->
            return DistributedExecutionResult(status = TaskRunStatus.Failed, failureMessage = "Lease refused: $reason")
        }
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

    private suspend fun leaseRefusal(envelope: DistributedTaskEnvelope): String? {
        val definition = envelope.definition
        if (WorkflowGraphValidator.validate(definition).isNotEmpty()) return "the submitted workflow does not validate"
        val declared = definition.tasks.firstOrNull { it.id == envelope.task.id }
            ?: return "the task is not part of the submitted workflow"
        if ((declared.executor as? TaskExecutor.Distributed)?.delegate != envelope.delegatedExecutor) {
            return "the task is not a distributed placement of the requested executor"
        }
        val delegate = envelope.delegatedExecutor
        if (delegate is TaskExecutor.RepositoryOperation && delegate.isMutation() &&
            !hasCompletedApprovalAncestor(envelope, declared.id)
        ) {
            return "repository operation '${delegate.operation}' has no completed human approval"
        }
        if (delegate.usesRepositoryCredentials()) {
            val repository = envelope.project.repository ?: return "the project has no linked repository"
            if (trustedRepositories().none { it.sameRepositoryAs(repository) }) {
                return "${repository.owner}/${repository.name} is not linked to a project on this device"
            }
        }
        return null
    }

    private fun hasCompletedApprovalAncestor(envelope: DistributedTaskEnvelope, taskId: TaskDefinitionId): Boolean {
        val tasks = envelope.definition.tasks.associateBy { it.id }
        val visited = mutableSetOf<TaskDefinitionId>()
        fun visit(id: TaskDefinitionId): Boolean {
            if (!visited.add(id)) return false
            return tasks[id]?.dependsOn.orEmpty().any { dependencyId ->
                val dependency = tasks[dependencyId] ?: return@any false
                val approved = dependency.executor is TaskExecutor.HumanApproval &&
                    envelope.run.taskRuns[dependencyId]?.status == TaskRunStatus.Completed
                approved || visit(dependencyId)
            }
        }
        return visit(taskId)
    }

    private fun TaskExecutor.usesRepositoryCredentials(): Boolean = when (this) {
        is TaskExecutor.RepositoryOperation, is TaskExecutor.GitHubAction -> true
        is TaskExecutor.Script -> runner is ScriptRunner.GitHubActions
        else -> false
    }

    private fun RepositoryRef.sameRepositoryAs(other: RepositoryRef): Boolean =
        source == other.source &&
            owner.equals(other.owner, ignoreCase = true) &&
            name.equals(other.name, ignoreCase = true) &&
            (localPath == null || localPath == other.localPath)

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
