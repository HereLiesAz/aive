package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.NoOpWorkflowEventSink
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.events.WorkflowFailed
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class WorkflowEngine(
    private val sessionGateway: ManagedSessionGateway,
    roles: Collection<RoleDefinition>,
    private val eventSink: WorkflowEventSink = NoOpWorkflowEventSink,
) {
    private val rolesById: Map<RoleDefinitionId, RoleDefinition> = roles.associateBy { it.id }

    init {
        require(rolesById.size == roles.size) { "Role IDs must be unique" }
    }

    data class DispatchResult(
        val run: WorkflowRun,
        val handles: Map<TaskDefinitionId, ManagedSessionHandle>,
    )

    suspend fun dispatchReadyTasks(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        existingHandles: Map<TaskDefinitionId, ManagedSessionHandle> = emptyMap(),
        nowEpochMillis: Long,
    ): DispatchResult {
        if (run.status.isTerminal()) return DispatchResult(run, existingHandles)

        val refreshed = WorkflowRunFactory.refreshReadiness(definition, run, nowEpochMillis)
        val activeCount = refreshed.taskRuns.values.count { it.status.isActive() }
        var remainingSlots = (definition.concurrencyPolicy.maxConcurrentTasks - activeCount).coerceAtLeast(0)
        if (remainingSlots == 0) return DispatchResult(refreshed, existingHandles)

        val definitionsById = definition.tasks.associateBy { it.id }
        var nextRun = refreshed
        var humanApprovalPending = false
        val handles = existingHandles.toMutableMap()
        val activeByProvider = refreshed.taskRuns.values
            .filter { it.status.isActive() && it.assignedProviderId != null }
            .groupingBy { requireNotNull(it.assignedProviderId) }
            .eachCount()
            .toMutableMap()
        val dispatchable = refreshed.taskRuns.values.filter {
            it.status == TaskRunStatus.Ready || it.status == TaskRunStatus.Retrying
        }

        // Phase 1: collect all role-agent dispatches and resolve providers (synchronous).
        data class PendingAgentDispatch(
            val taskRun: TaskRun,
            val task: TaskDefinition,
            val role: RoleDefinition,
            val executor: TaskExecutor.RoleAgent,
            val request: AgentTaskRequest,
            val sessionRequest: ManagedSessionRequest,
            val providerId: AgentProviderId,
        )

        val pendingAgentDispatches = mutableListOf<PendingAgentDispatch>()

        for (taskRun in dispatchable) {
            if (remainingSlots == 0) break
            val task = requireNotNull(definitionsById[taskRun.taskDefinitionId])
            val executor = taskRun.executor ?: task.effectiveExecutor()

            when (executor) {
                is TaskExecutor.RoleAgent -> {
                    val role = requireNotNull(rolesById[executor.roleId]) {
                        "Role ${executor.roleId.value} is not registered"
                    }
                    require(role.enabled) { "Role ${role.name} is disabled" }

                    val selection = ProviderSelectionRequest(
                        preferredProviderId = role.preferredProviderId,
                        requiredCapabilities = role.capabilitiesRequired,
                        constraints = task.providerConstraints,
                    )
                    val providerId = sessionGateway.resolveProvider(selection)
                    val providerLimit = definition.concurrencyPolicy.perProviderLimits[providerId] ?: Int.MAX_VALUE
                    val providerActive = activeByProvider[providerId] ?: 0
                    if (providerActive >= providerLimit) continue

                    val redaction = definition.payloadRedactionPolicy
                    val dependencyArtifacts = task.dependsOn
                        .mapNotNull(nextRun.taskRuns::get)
                        .flatMap(TaskRun::artifacts)
                        .filter { it.kind !in redaction.excludedArtifactKinds }
                    val request = AgentTaskRequest(
                        taskRunId = taskRun.id,
                        objective = if (redaction.redactObjective) "[redacted]" else task.objective,
                        roleInstructions = if (redaction.redactRoleInstructions) {
                            swarmInstructions
                        } else {
                            "$swarmInstructions\n\n${role.instructions}"
                        },
                        acceptanceCriteria = task.acceptanceCriteria,
                        contextArtifacts = dependencyArtifacts,
                        repository = project.repository,
                        requirePlanApproval = task.approvalPolicy != ApprovalPolicy.None,
                        promptContext = PromptContext(
                            stablePrefix = listOf(
                                PromptContextBlock("Workflow objective", nextRun.objective),
                                PromptContextBlock("Role", role.instructions),
                            ),
                            dynamicContext = listOf(
                                PromptContextBlock("Task", task.objective),
                                PromptContextBlock("Attempt", taskRun.attempt.toString()),
                            ),
                            reusePolicy = definition.promptReusePolicy,
                            cacheNamespace = "${nextRun.id.value}:${role.id.value}",
                        ),
                    )
                    pendingAgentDispatches += PendingAgentDispatch(
                        taskRun = taskRun,
                        task = task,
                        role = role,
                        executor = executor,
                        request = request,
                        sessionRequest = ManagedSessionRequest(
                            providerSelection = selection.copy(
                                preferredProviderId = providerId,
                                constraints = ProviderConstraints.RequireProvider(providerId),
                            ),
                            taskRequest = request,
                        ),
                        providerId = providerId,
                    )
                    activeByProvider[providerId] = providerActive + 1
                    remainingSlots -= 1
                }

                is TaskExecutor.HumanApproval -> {
                    TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.AwaitingApproval)
                    humanApprovalPending = true
                    nextRun = nextRun.copy(
                        taskRuns = nextRun.taskRuns + (
                            task.id to taskRun.copy(
                                status = TaskRunStatus.AwaitingApproval,
                                assignedRoleId = task.roleId,
                                executor = executor,
                                blockingReason = null,
                                progress = null,
                                progressMessage = executor.label,
                            )
                        ),
                        updatedAtEpochMillis = nowEpochMillis,
                    )
                    eventSink.append(HumanDecisionRequired(nextRun.id, task.id, executor.label, nowEpochMillis))
                    continue
                }

                is TaskExecutor.GitHubAction,
                is TaskExecutor.TestRunner,
                is TaskExecutor.Deployment,
                is TaskExecutor.RepositoryOperation,
                is TaskExecutor.ExternalService,
                is TaskExecutor.NestedWorkflow,
                -> continue
            }
        }

        // Phase 2: create sessions concurrently.
        val createdHandles = coroutineScope {
            pendingAgentDispatches.map { pending ->
                async { pending to sessionGateway.createSession(pending.sessionRequest) }
            }.map { it.await() }
        }

        // Phase 3: apply state mutations sequentially.
        for ((pending, handle) in createdHandles) {
            handles[pending.task.id] = handle
            val startedStatus = if (pending.request.requirePlanApproval) {
                TaskRunStatus.Planning
            } else {
                TaskRunStatus.Running
            }
            TaskRunTransitions.requireAllowed(pending.taskRun.status, startedStatus)
            nextRun = nextRun.copy(
                status = if (nextRun.status == WorkflowRunStatus.AwaitingHuman) {
                    nextRun.status
                } else {
                    WorkflowRunStatus.Running
                },
                taskRuns = nextRun.taskRuns + (
                    pending.task.id to pending.taskRun.copy(
                        status = startedStatus,
                        assignedRoleId = pending.task.roleId ?: pending.role.id,
                        executor = pending.executor,
                        assignedProviderId = handle.providerId,
                        providerRunId = handle.providerRunId,
                        externalRunId = null,
                        blockingReason = null,
                        progress = null,
                        progressMessage = null,
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
            eventSink.append(AgentAssigned(nextRun.id, pending.task.id, pending.role.id, nowEpochMillis))
            eventSink.append(ExecutorAssigned(nextRun.id, pending.task.id, pending.executor, pending.task.roleId, nowEpochMillis))
            eventSink.append(TaskStarted(nextRun.id, pending.task.id, pending.taskRun.attempt, nowEpochMillis))
        }

        val hasHumanWaiting = nextRun.taskRuns.values.any {
            it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
        }
        val finalStatus = when {
            humanApprovalPending || hasHumanWaiting -> WorkflowRunStatus.AwaitingHuman
            handles.size > existingHandles.size -> WorkflowRunStatus.Running
            else -> refreshed.status
        }
        return DispatchResult(nextRun.copy(status = finalStatus), handles)
    }

    suspend fun completeTask(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        nowEpochMillis: Long,
        artifacts: List<ArtifactRef> = emptyList(),
        externalRunId: String? = null,
    ): WorkflowRun = completeTaskInternal(
        definition = definition,
        run = run,
        taskDefinitionId = taskDefinitionId,
        nowEpochMillis = nowEpochMillis,
        artifacts = artifacts,
        externalRunId = externalRunId,
        allowHumanApprovalCompletion = false,
    )

    suspend fun completeHumanApprovalTask(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        nowEpochMillis: Long,
    ): WorkflowRun {
        val task = requireNotNull(definition.tasks.firstOrNull { it.id == taskDefinitionId }) {
            "Task ${taskDefinitionId.value} is not defined"
        }
        require(task.effectiveExecutor() is TaskExecutor.HumanApproval) {
            "Task ${taskDefinitionId.value} is not a human approval gate"
        }
        return completeTaskInternal(
            definition = definition,
            run = run,
            taskDefinitionId = taskDefinitionId,
            nowEpochMillis = nowEpochMillis,
            allowHumanApprovalCompletion = true,
        )
    }

    private suspend fun completeTaskInternal(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        nowEpochMillis: Long,
        artifacts: List<ArtifactRef> = emptyList(),
        externalRunId: String? = null,
        allowHumanApprovalCompletion: Boolean,
    ): WorkflowRun {
        require(!run.status.isTerminal()) { "Workflow ${run.id.value} is already ${run.status}" }
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) {
            "Task run ${taskDefinitionId.value} is missing"
        }
        val humanApprovalCompletion = allowHumanApprovalCompletion && taskRun.status == TaskRunStatus.AwaitingApproval
        if (!humanApprovalCompletion) {
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Completed)
        }
        eventSink.append(TaskCompleted(run.id, taskDefinitionId, nowEpochMillis))

        var nextRun = run.copy(
            taskRuns = run.taskRuns + (
                taskDefinitionId to taskRun.copy(
                    status = TaskRunStatus.Completed,
                    artifacts = if (artifacts.isEmpty()) {
                        taskRun.artifacts
                    } else {
                        mergeArtifacts(taskRun.artifacts, artifacts)
                    },
                    externalRunId = externalRunId ?: taskRun.externalRunId,
                    progress = 1f,
                    blockingReason = null,
                )
            ),
            updatedAtEpochMillis = nowEpochMillis,
        )
        nextRun = WorkflowRunFactory.refreshReadiness(definition, nextRun, nowEpochMillis)
        val status = deriveWorkflowStatus(nextRun)
        if (status == WorkflowRunStatus.Completed) {
            eventSink.append(WorkflowCompleted(nextRun.id, nowEpochMillis))
        }
        return nextRun.copy(status = status)
    }

    suspend fun reconcile(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
        artifactIdFactory: (TaskRun, ProviderArtifact, Int) -> ArtifactId,
    ): WorkflowRun {
        if (run.status.isTerminal()) return run
        var nextRun = run

        for ((taskId, handle) in handles) {
            val taskRun = nextRun.taskRuns[taskId] ?: continue
            if (taskRun.status == TaskRunStatus.Completed || taskRun.status == TaskRunStatus.Cancelled) continue

            val previousStatus = taskRun.status
            val status = sessionGateway.status(handle)
            val providerProgress = sessionGateway.progress(handle)
            runCatching {
                sessionGateway.usageReport(handle)?.let { usage ->
                    eventSink.append(
                        ProviderUsageRecorded(
                            workflowRunId = nextRun.id,
                            taskDefinitionId = taskId,
                            providerId = handle.providerId,
                            providerRunId = handle.providerRunId,
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            costUsd = usage.costUsd,
                            cacheHitFraction = usage.cacheHitFraction,
                            latencyMillis = usage.latencyMillis,
                            occurredAtEpochMillis = nowEpochMillis,
                        ),
                    )
                }
            }
            val durableArtifacts = sessionGateway.artifacts(handle).mapIndexed { index, artifact ->
                ArtifactRef(
                    id = artifactIdFactory(taskRun, artifact, index),
                    kind = artifact.kind,
                    taskRunId = taskRun.id,
                    label = artifact.label,
                    uri = artifact.uri,
                    textContent = artifact.textContent,
                    mediaType = artifact.mediaType,
                    metadata = artifact.metadata,
                    createdAtEpochMillis = nowEpochMillis,
                )
            }
            val mappedStatus = when (status) {
                ManagedSessionStatus.Planning -> TaskRunStatus.Planning
                ManagedSessionStatus.AwaitingApproval -> TaskRunStatus.AwaitingApproval
                ManagedSessionStatus.Running -> TaskRunStatus.Running
                ManagedSessionStatus.Completed -> TaskRunStatus.Completed
                ManagedSessionStatus.Failed -> TaskRunStatus.Failed
                ManagedSessionStatus.Unknown -> taskRun.status
            }
            val safeStatus = if (
                mappedStatus == taskRun.status || TaskRunTransitions.canTransition(taskRun.status, mappedStatus)
            ) {
                mappedStatus
            } else {
                taskRun.status
            }
            val mergedArtifacts = mergeArtifacts(taskRun.artifacts, durableArtifacts)
            val previousArtifactIds = taskRun.artifacts.mapTo(mutableSetOf()) { it.id }
            mergedArtifacts
                .filter { it.id !in previousArtifactIds }
                .forEach { eventSink.append(ArtifactCreated(nextRun.id, taskId, it, nowEpochMillis)) }
            if (safeStatus == TaskRunStatus.Completed && previousStatus != TaskRunStatus.Completed) {
                eventSink.append(TaskCompleted(nextRun.id, taskId, nowEpochMillis))
            }
            nextRun = nextRun.copy(
                taskRuns = nextRun.taskRuns + (
                    taskId to taskRun.copy(
                        status = safeStatus,
                        artifacts = mergedArtifacts,
                        progress = if (safeStatus == TaskRunStatus.Completed) {
                            1f
                        } else {
                            providerProgress?.fraction ?: taskRun.progress
                        },
                        progressMessage = providerProgress?.message ?: taskRun.progressMessage,
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }

        nextRun = WorkflowRunFactory.refreshReadiness(definition, nextRun, nowEpochMillis)
        val workflowStatus = deriveWorkflowStatus(nextRun)
        if (workflowStatus == WorkflowRunStatus.Completed && run.status != WorkflowRunStatus.Completed) {
            eventSink.append(WorkflowCompleted(nextRun.id, nowEpochMillis))
        }
        return nextRun.copy(status = workflowStatus, updatedAtEpochMillis = nowEpochMillis)
    }

    suspend fun handleFailure(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        retryReason: RetryReason,
        reason: String,
        nowEpochMillis: Long,
    ): WorkflowRun {
        require(!run.status.isTerminal()) { "Workflow ${run.id.value} is already ${run.status}" }
        val task = requireNotNull(definition.tasks.firstOrNull { it.id == taskDefinitionId }) {
            "Task ${taskDefinitionId.value} is not defined"
        }
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) {
            "Task run ${taskDefinitionId.value} is missing"
        }
        val decision = FailurePolicyEvaluator.decide(
            taskRun = taskRun,
            retryPolicy = task.retryPolicy,
            escalationPolicy = task.escalationPolicy,
            reason = retryReason,
        )

        return when (decision) {
            is FailureDecision.Retry -> {
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Retrying)
                eventSink.append(
                    RetryScheduled(run.id, taskDefinitionId, decision.nextAttempt, reason, nowEpochMillis),
                )
                run.copy(
                    status = WorkflowRunStatus.Running,
                    taskRuns = run.taskRuns + (
                        taskDefinitionId to taskRun.copy(
                            status = TaskRunStatus.Retrying,
                            attempt = decision.nextAttempt,
                            assignedProviderId = null,
                            providerRunId = null,
                            externalRunId = null,
                            artifacts = emptyList(),
                            blockingReason = null,
                            progress = null,
                            progressMessage = null,
                        )
                    ),
                    updatedAtEpochMillis = nowEpochMillis,
                )
            }

            FailureDecision.RequireHumanDecision -> {
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Escalated)
                eventSink.append(HumanDecisionRequired(run.id, taskDefinitionId, reason, nowEpochMillis))
                eventSink.append(TaskEscalated(run.id, taskDefinitionId, reason, occurredAtEpochMillis = nowEpochMillis))
                run.copy(
                    status = WorkflowRunStatus.AwaitingHuman,
                    taskRuns = run.taskRuns + (
                        taskDefinitionId to taskRun.copy(status = TaskRunStatus.Escalated)
                    ),
                    updatedAtEpochMillis = nowEpochMillis,
                )
            }

            is FailureDecision.Reassign -> {
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Retrying)
                eventSink.append(TaskEscalated(run.id, taskDefinitionId, reason, decision.roleId, nowEpochMillis))
                run.copy(
                    status = WorkflowRunStatus.Running,
                    taskRuns = run.taskRuns + (
                        taskDefinitionId to taskRun.copy(
                            status = TaskRunStatus.Retrying,
                            assignedRoleId = decision.roleId,
                            executor = TaskExecutor.RoleAgent(decision.roleId),
                            assignedProviderId = null,
                            providerRunId = null,
                            externalRunId = null,
                            blockingReason = null,
                            progress = null,
                            progressMessage = null,
                        )
                    ),
                    updatedAtEpochMillis = nowEpochMillis,
                )
            }

            FailureDecision.FailWorkflow -> {
                if (taskRun.status != TaskRunStatus.Failed) {
                    TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Failed)
                }
                eventSink.append(TaskFailed(run.id, taskDefinitionId, reason, nowEpochMillis))
                eventSink.append(WorkflowFailed(run.id, reason, nowEpochMillis))
                run.copy(
                    status = WorkflowRunStatus.Failed,
                    taskRuns = run.taskRuns + (
                        taskDefinitionId to taskRun.copy(status = TaskRunStatus.Failed)
                    ),
                    updatedAtEpochMillis = nowEpochMillis,
                )
            }
        }
    }

    fun approvePlanGate(run: WorkflowRun, taskDefinitionId: TaskDefinitionId, nowEpochMillis: Long): WorkflowRun {
        require(!run.status.isTerminal()) { "Workflow ${run.id.value} is already ${run.status}" }
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) { "Task run ${taskDefinitionId.value} is missing" }
        TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Running)
        return run.copy(
            taskRuns = run.taskRuns + (taskDefinitionId to taskRun.copy(status = TaskRunStatus.Running, blockingReason = null)),
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    suspend fun resolveEscalation(run: WorkflowRun, taskDefinitionId: TaskDefinitionId, approved: Boolean, nowEpochMillis: Long): WorkflowRun {
        require(!run.status.isTerminal()) { "Workflow ${run.id.value} is already ${run.status}" }
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) { "Task run ${taskDefinitionId.value} is missing" }
        return if (approved) {
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Retrying)
            run.copy(
                status = WorkflowRunStatus.Running,
                taskRuns = run.taskRuns + (taskDefinitionId to taskRun.copy(
                    status = TaskRunStatus.Retrying,
                    attempt = taskRun.attempt + 1,
                    assignedProviderId = null,
                    providerRunId = null,
                    externalRunId = null,
                    artifacts = emptyList(),
                    blockingReason = null,
                    progress = null,
                    progressMessage = null,
                )),
                updatedAtEpochMillis = nowEpochMillis,
            )
        } else {
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Failed)
            eventSink.append(TaskFailed(run.id, taskDefinitionId, "Escalation rejected", nowEpochMillis))
            eventSink.append(WorkflowFailed(run.id, "Escalation rejected", nowEpochMillis))
            run.copy(
                status = WorkflowRunStatus.Failed,
                taskRuns = run.taskRuns + (taskDefinitionId to taskRun.copy(status = TaskRunStatus.Failed)),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
    }

    suspend fun cancelWorkflow(run: WorkflowRun, nowEpochMillis: Long): WorkflowRun {
        if (run.status.isTerminal()) return run
        val cancelledTaskRuns = run.taskRuns.mapValues { (taskId, taskRun) ->
            if (taskRun.status == TaskRunStatus.Completed || taskRun.status == TaskRunStatus.Failed) {
                taskRun
            } else {
                eventSink.append(TaskCancelled(run.id, taskId, nowEpochMillis))
                taskRun.copy(status = TaskRunStatus.Cancelled)
            }
        }
        eventSink.append(WorkflowCancelled(run.id, nowEpochMillis))
        return run.copy(
            status = WorkflowRunStatus.Cancelled,
            taskRuns = cancelledTaskRuns,
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    private fun deriveWorkflowStatus(run: WorkflowRun): WorkflowRunStatus {
        if (run.status.isTerminal()) return run.status
        val statuses = run.taskRuns.values.map { it.status }
        return when {
            statuses.isNotEmpty() && statuses.all { it == TaskRunStatus.Completed || it == TaskRunStatus.Cancelled } -> WorkflowRunStatus.Completed
            statuses.any { it == TaskRunStatus.AwaitingApproval || it == TaskRunStatus.Escalated } -> WorkflowRunStatus.AwaitingHuman
            statuses.any { it == TaskRunStatus.Failed } -> WorkflowRunStatus.Failed
            else -> WorkflowRunStatus.Running
        }
    }

    private fun mergeArtifacts(existing: List<ArtifactRef>, incoming: List<ArtifactRef>): List<ArtifactRef> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashMap<ArtifactId, ArtifactRef>(existing.size + incoming.size)
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    private fun WorkflowRunStatus.isTerminal() = this in setOf(
        WorkflowRunStatus.Completed,
        WorkflowRunStatus.Failed,
        WorkflowRunStatus.Cancelled,
    )

    private fun TaskRunStatus.isActive() = this in setOf(
        TaskRunStatus.Planning,
        TaskRunStatus.Running,
        TaskRunStatus.Verifying,
    )
}
