package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorkflowRuntimeState(
    val run: WorkflowRun,
    val handles: Map<TaskDefinitionId, ManagedSessionHandle> = emptyMap(),
)

class WorkflowRuntimeCoordinator(
    private val persistence: WorkflowPersistence,
    private val engine: WorkflowEngine,
    private val sessionGateway: ManagedSessionGateway,
    private val executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
    private val remoteComputeCoordinator: RemoteComputeCoordinator? = null,
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    private val surfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
) {
    private val gateCoordinator = ApprovalGateCoordinator(
        persistence.approvalGates,
        RepositoryWorkflowEventSink(persistence.events),
    )
    private val cycleMutex = Mutex()

    suspend fun persist(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
    ) {
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(state.run)
        persistArtifacts(state.run)
    }

    suspend fun resume(workflowRunId: WorkflowRunId): WorkflowRuntimeState {
        val run = requireNotNull(persistence.runs.get(workflowRunId)) {
            "Workflow run ${workflowRunId.value} was not found"
        }
        val storedProject = requireNotNull(persistence.projects.get(run.projectId)) {
            "Project ${run.projectId.value} was not found"
        }
        val project = storedProject.copy(
            repository = run.repositorySnapshot ?: storedProject.repository,
        )
        val definition = requireNotNull(persistence.definitions.get(run.workflowDefinitionId)) {
            "Workflow definition ${run.workflowDefinitionId.value} was not found"
        }
        val tasksById = definition.tasks.associateBy { it.id }
        val handles = buildMap {
            run.taskRuns.forEach { (taskDefinitionId, taskRun) ->
                val providerId = taskRun.assignedProviderId ?: return@forEach
                val providerRunId = taskRun.providerRunId ?: return@forEach
                if (!taskRun.status.canReconnect()) return@forEach
                val handle = ManagedSessionHandle(taskRun.id, providerId, providerRunId)
                val task = requireNotNull(tasksById[taskDefinitionId]) {
                    "Task ${taskDefinitionId.value} was not found"
                }
                val role = engine.roleDefinition(run, taskRun.assignedRoleId)
                val resolvedRole = requireNotNull(role) {
                    "Provider-backed task ${taskDefinitionId.value} has no role definition"
                }
                val resolvedSurfaces = surfaceRuntime.resolve(resolvedRole.surfaces)
                val request = buildProviderTaskRequest(
                    project = project,
                    definition = definition,
                    run = run,
                    task = task,
                    taskRun = taskRun,
                    role = resolvedRole,
                    orchestrationUtilities = orchestrationUtilities,
                    resolvedSurfaces = resolvedSurfaces,
                )
                sessionGateway.reconnect(
                    handle,
                    taskRun.status.toManagedStatus(),
                    request,
                    taskRun.providerPlan,
                )
                put(taskDefinitionId, handle)
            }
        }
        return WorkflowRuntimeState(run, handles)
    }

    suspend fun cycle(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        nowEpochMillis: Long,
        artifactIdFactory: (TaskRun, ProviderArtifact, Int) -> ArtifactId,
    ): WorkflowRuntimeState = cycleMutex.withLock {
        val persistedRun = persistence.runs.get(state.run.id)
        val authoritativeState = if (
            persistedRun != null &&
            persistedRun != state.run &&
            persistedRun.updatedAtEpochMillis > state.run.updatedAtEpochMillis
        ) {
            WorkflowRuntimeState(
                run = persistedRun,
                handles = mergeHandles(state.handles, persistedRun),
            )
        } else {
            state
        }
        cycleLocked(
            project = project,
            definition = definition,
            state = authoritativeState,
            nowEpochMillis = nowEpochMillis,
            artifactIdFactory = artifactIdFactory,
        )
    }

    private suspend fun cycleLocked(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        nowEpochMillis: Long,
        artifactIdFactory: (TaskRun, ProviderArtifact, Int) -> ArtifactId,
    ): WorkflowRuntimeState {
        if (state.run.status.isTerminal()) return state

        var nextRun = reconcileResolvedApprovalGates(definition, state.run, nowEpochMillis)
        if (nextRun.status.isTerminal()) {
            nextRun = closeFailureEscalationsForTerminalRun(nextRun, nowEpochMillis)
            val terminalState = WorkflowRuntimeState(nextRun, emptyMap())
            persist(project, definition, terminalState)
            return terminalState
        }

        nextRun = recoverAvailableSystemExecutors(project, definition, nextRun, nowEpochMillis)
        nextRun = reconcileRemoteCompute(project, definition, nextRun, nowEpochMillis)
        ensureMissingFailureEscalationGates(nextRun, nowEpochMillis)
        ensurePlanApprovalGates(definition, nextRun, nowEpochMillis)

        if (
            nextRun == state.run &&
            nextRun.status == WorkflowRunStatus.AwaitingHuman &&
            state.handles.isEmpty() &&
            nextRun.taskRuns.values.none {
                it.status in setOf(
                    TaskRunStatus.Ready,
                    TaskRunStatus.Retrying,
                    TaskRunStatus.Running,
                    TaskRunStatus.Verifying,
                )
            }
        ) {
            return state
        }

        nextRun = engine.reconcile(
            definition = definition,
            run = nextRun,
            handles = state.handles,
            nowEpochMillis = nowEpochMillis,
            artifactIdFactory = artifactIdFactory,
        )
        nextRun = preserveArtifactTimestamps(state.run, nextRun)
        nextRun = reconcileSystemExecutors(project, definition, nextRun, nowEpochMillis)
        persistArtifacts(nextRun)
        nextRun = refreshAfterSystemExecution(definition, nextRun, nowEpochMillis)

        val newlyFailedTaskIds = nextRun.taskRuns
            .filter { (taskId, taskRun) ->
                taskRun.status == TaskRunStatus.Failed &&
                    state.run.taskRuns[taskId]?.status != TaskRunStatus.Failed
            }
            .keys

        if (newlyFailedTaskIds.isNotEmpty() && nextRun.status == WorkflowRunStatus.Failed) {
            nextRun = nextRun.copy(status = WorkflowRunStatus.Running)
        }

        for (taskId in newlyFailedTaskIds) {
            if (nextRun.status.isTerminal()) break
            val previousStatus = state.run.taskRuns[taskId]?.status
            val retryReason = when (previousStatus) {
                TaskRunStatus.Verifying -> RetryReason.VerificationFailed
                TaskRunStatus.Planning -> RetryReason.PlanRejected
                else -> RetryReason.ProviderFailure
            }
            nextRun = engine.handleFailure(
                definition = definition,
                run = nextRun,
                taskDefinitionId = taskId,
                retryReason = retryReason,
                reason = "Executor failed",
                nowEpochMillis = nowEpochMillis,
            )
            if (nextRun.taskRuns[taskId]?.status == TaskRunStatus.Escalated) {
                ensureFailureEscalationGate(
                    run = nextRun,
                    taskId = taskId,
                    reason = "Executor failed",
                    now = nowEpochMillis,
                )
            }
        }

        if (nextRun.status.isTerminal()) {
            nextRun = closeFailureEscalationsForTerminalRun(nextRun, nowEpochMillis)
            val terminalState = WorkflowRuntimeState(nextRun, emptyMap())
            persist(project, definition, terminalState)
            return terminalState
        }

        ensureMissingFailureEscalationGates(nextRun, nowEpochMillis)

        if (
            nextRun.taskRuns.values.any {
                it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
            }
        ) {
            nextRun = nextRun.copy(status = WorkflowRunStatus.AwaitingHuman)
        }

        var nextHandles = state.handles.filterKeys { taskId ->
            nextRun.taskRuns[taskId]?.status !in setOf(
                TaskRunStatus.Completed,
                TaskRunStatus.Failed,
                TaskRunStatus.Cancelled,
                TaskRunStatus.Retrying,
                TaskRunStatus.Escalated,
            )
        }

        nextRun = dispatchRemoteCompute(project, definition, nextRun, nowEpochMillis)
        nextRun = blockUnavailableSystemExecutors(project, definition, nextRun, nowEpochMillis)
        ensurePlanApprovalGates(definition, nextRun, nowEpochMillis)

        var nextState = WorkflowRuntimeState(nextRun, nextHandles)
        persist(project, definition, nextState)

        val beforeSystemDispatch = nextRun
        nextRun = dispatchSystemExecutors(project, definition, nextRun, nowEpochMillis)
        nextRun = refreshAfterSystemExecution(definition, nextRun, nowEpochMillis)

        val dispatchFailedTaskIds = nextRun.taskRuns
            .filter { (taskId, taskRun) ->
                taskRun.status == TaskRunStatus.Failed &&
                    beforeSystemDispatch.taskRuns[taskId]?.status != TaskRunStatus.Failed
            }
            .keys
        if (dispatchFailedTaskIds.isNotEmpty() && nextRun.status == WorkflowRunStatus.Failed) {
            nextRun = nextRun.copy(status = WorkflowRunStatus.Running)
        }
        for (taskId in dispatchFailedTaskIds) {
            if (nextRun.status.isTerminal()) break
            val task = definition.tasks.firstOrNull { it.id == taskId }
            val taskRun = nextRun.taskRuns[taskId]
            val executor = taskRun?.executor ?: task?.effectiveExecutor()
            val allowRetry = executor
                ?.let { executorIntegrations.integrationFor(it, project)?.retryDispatchFailures }
                ?: true
            nextRun = engine.handleFailure(
                definition = definition,
                run = nextRun,
                taskDefinitionId = taskId,
                retryReason = RetryReason.ProviderFailure,
                reason = nextRun.taskRuns[taskId]?.progressMessage ?: "Executor dispatch failed",
                nowEpochMillis = nowEpochMillis,
                allowRetry = allowRetry,
            )
            if (nextRun.taskRuns[taskId]?.status == TaskRunStatus.Escalated) {
                ensureFailureEscalationGate(
                    run = nextRun,
                    taskId = taskId,
                    reason = nextRun.taskRuns[taskId]?.progressMessage ?: "Executor dispatch failed",
                    now = nowEpochMillis,
                )
            }
        }

        if (nextRun.status.isTerminal()) {
            nextRun = closeFailureEscalationsForTerminalRun(nextRun, nowEpochMillis)
            nextState = WorkflowRuntimeState(nextRun, emptyMap())
            persist(project, definition, nextState)
            return nextState
        }

        val dispatched = engine.dispatchReadyTasks(
            project = project,
            definition = definition,
            run = nextRun,
            existingHandles = nextHandles,
            nowEpochMillis = nowEpochMillis,
        )
        nextRun = dispatched.run
        nextHandles = dispatched.handles.filterKeys { taskId ->
            nextRun.taskRuns[taskId]?.status?.isActiveProviderStatus() == true
        }
        if (nextRun.status.isTerminal()) {
            nextRun = closeFailureEscalationsForTerminalRun(nextRun, nowEpochMillis)
            nextHandles = emptyMap()
        }
        nextState = WorkflowRuntimeState(nextRun, nextHandles)
        persist(project, definition, nextState)
        return nextState
    }

    private suspend fun persistArtifacts(run: WorkflowRun) {
        run.taskRuns.values
            .flatMap(TaskRun::artifacts)
            .forEach { persistence.artifacts.put(it) }
    }

    private suspend fun mergeHandles(
        existing: Map<TaskDefinitionId, ManagedSessionHandle>,
        run: WorkflowRun,
    ): Map<TaskDefinitionId, ManagedSessionHandle> = buildMap {
        putAll(existing.filterKeys { taskId -> run.taskRuns[taskId]?.status?.canReconnect() == true })
        run.taskRuns.forEach { (taskDefinitionId, taskRun) ->
            if (containsKey(taskDefinitionId) || !taskRun.status.canReconnect()) return@forEach
            val providerId = taskRun.assignedProviderId ?: return@forEach
            val providerRunId = taskRun.providerRunId ?: return@forEach
            val handle = ManagedSessionHandle(taskRun.id, providerId, providerRunId)
            sessionGateway.reconnect(handle, taskRun.status.toManagedStatus())
            put(taskDefinitionId, handle)
        }
    }

    private suspend fun ensureMissingFailureEscalationGates(
        run: WorkflowRun,
        now: Long,
    ) {
        for ((taskId, taskRun) in run.taskRuns) {
            if (taskRun.status == TaskRunStatus.Escalated) {
                ensureFailureEscalationGate(
                    run = run,
                    taskId = taskId,
                    reason = taskRun.progressMessage ?: "Task failure requires a human decision.",
                    now = now,
                )
            }
        }
    }

    private suspend fun ensureFailureEscalationGate(
        run: WorkflowRun,
        taskId: TaskDefinitionId,
        reason: String,
        now: Long,
    ) {
        val existing = persistence.approvalGates.unresolved(run.id).firstOrNull {
            it.taskDefinitionId == taskId && it.kind == ApprovalGateKind.FailureEscalation
        }
        if (existing != null) return

        val taskRun = requireNotNull(run.taskRuns[taskId])
        gateCoordinator.open(
            id = ApprovalGateId("failure:${run.id.value}:${taskId.value}:${taskRun.attempt}"),
            workflowRunId = run.id,
            taskDefinitionId = taskId,
            kind = ApprovalGateKind.FailureEscalation,
            reason = reason,
            requiresHuman = true,
            nowEpochMillis = now,
        )
    }

    private suspend fun closeFailureEscalationsForTerminalRun(
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        if (!run.status.isTerminal()) return run

        val unresolved = persistence.approvalGates.unresolved(run.id)
            .filter { it.kind == ApprovalGateKind.FailureEscalation }
        if (unresolved.isEmpty()) return run

        var nextRun = run
        for (gate in unresolved) {
            val taskId = gate.taskDefinitionId
            val taskRun = taskId?.let(nextRun.taskRuns::get)
            val candidateRun = if (taskId != null && taskRun?.status == TaskRunStatus.Escalated) {
                TaskRunTransitions.requireAllowed(TaskRunStatus.Escalated, TaskRunStatus.Cancelled)
                nextRun.copy(
                    taskRuns = nextRun.taskRuns + (
                        taskId to taskRun.copy(
                            status = TaskRunStatus.Cancelled,
                            blockingReason = null,
                            progressMessage = "Escalation cancelled because workflow is ${nextRun.status}",
                        )
                    ),
                    updatedAtEpochMillis = now,
                )
            } else {
                nextRun.copy(updatedAtEpochMillis = now)
            }
            val committed = persistence.commitFailureEscalationDecision(
                FailureEscalationDecisionCommit(
                    expectedGateId = gate.id,
                    decidedGate = gate.reject(
                        decidedByRoleId = null,
                        note = "Workflow became ${run.status} before escalation decision",
                        nowEpochMillis = now,
                    ),
                    nextRun = candidateRun,
                    decisionEvent = ApprovalDecisionReceived(
                        workflowRunId = run.id,
                        taskDefinitionId = taskId,
                        gateId = gate.id,
                        approved = false,
                        decidedByRoleId = null,
                        occurredAtEpochMillis = now,
                    ),
                ),
            )
            if (!committed) {
                return persistence.runs.get(run.id) ?: nextRun
            }
            nextRun = candidateRun
        }
        return nextRun
    }

    private suspend fun ensurePlanApprovalGates(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ) {
        val tasks = definition.tasks.associateBy { it.id }
        for ((taskId, taskRun) in run.taskRuns) {
            if (taskRun.status != TaskRunStatus.AwaitingApproval) continue
            val task = tasks[taskId] ?: continue
            if (task.approvalPolicy == ApprovalPolicy.None) continue

            val gateId = ApprovalGateId("plan:${run.id.value}:${taskId.value}:${taskRun.attempt}")
            if (persistence.approvalGates.get(gateId) != null) continue

            gateCoordinator.open(
                id = gateId,
                workflowRunId = run.id,
                taskDefinitionId = taskId,
                kind = ApprovalGateKind.PlanApproval,
                reason = "Plan approval required for '${task.name}'",
                requiresHuman = task.approvalPolicy is ApprovalPolicy.HumanApproval,
                nowEpochMillis = now,
            )
        }
    }

    private suspend fun reconcileResolvedApprovalGates(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        if (run.status.isTerminal()) return run

        val tasks = definition.tasks.associateBy { it.id }
        var nextRun = run
        for ((taskId, taskRun) in run.taskRuns) {
            when (taskRun.status) {
                TaskRunStatus.Escalated -> {
                    val gateId = ApprovalGateId(
                        "failure:${nextRun.id.value}:${taskId.value}:${taskRun.attempt}",
                    )
                    val gate = persistence.approvalGates.get(gateId) ?: continue
                    if (
                        gate.status == ApprovalGateStatus.Pending ||
                        gate.status == ApprovalGateStatus.Applying
                    ) {
                        continue
                    }
                    nextRun = engine.resolveEscalation(
                        run = nextRun,
                        taskDefinitionId = taskId,
                        approved = gate.status == ApprovalGateStatus.Approved,
                        nowEpochMillis = now,
                    )
                }

                TaskRunStatus.AwaitingApproval -> {
                    val task = tasks[taskId] ?: continue
                    if (task.approvalPolicy == ApprovalPolicy.None) continue
                    val gateId = ApprovalGateId(
                        "plan:${nextRun.id.value}:${taskId.value}:${taskRun.attempt}",
                    )
                    val gate = persistence.approvalGates.get(gateId) ?: continue
                    if (
                        gate.status == ApprovalGateStatus.Pending ||
                        gate.status == ApprovalGateStatus.Applying
                    ) {
                        continue
                    }
                    nextRun = if (gate.status == ApprovalGateStatus.Approved) {
                        engine.approvePlanGate(nextRun, taskId, now)
                    } else {
                        engine.handleFailure(
                            definition = definition,
                            run = nextRun,
                            taskDefinitionId = taskId,
                            retryReason = RetryReason.PlanRejected,
                            reason = "Plan rejected",
                            nowEpochMillis = now,
                        )
                    }
                }

                else -> continue
            }
            if (nextRun.status.isTerminal()) break
        }
        return nextRun
    }

    private suspend fun dispatchSystemExecutors(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        var next = run
        val tasks = definition.tasks.associateBy { it.id }
        for ((id, original) in run.taskRuns) {
            val task = tasks[id] ?: continue
            val taskRun = next.taskRuns[id] ?: original
            val executor = taskRun.executor ?: task.effectiveExecutor()
            if (
                !executor.isSystemExecutor() ||
                taskRun.externalRunId != null ||
                (taskRun.status != TaskRunStatus.Ready && taskRun.status != TaskRunStatus.Retrying)
            ) {
                continue
            }
            val integration = executorIntegrations.integrationFor(executor, project) ?: continue
            next = applyExecution(
                run = next,
                id = id,
                previous = taskRun,
                executor = executor,
                execution = integration.dispatch(
                    TaskExecutorContext(
                        project = project,
                        definition = definition,
                        run = next,
                        task = task,
                        taskRun = taskRun,
                        executor = executor,
                        nowEpochMillis = now,
                        role = engine.roleDefinition(next, task.roleId),
                    ),
                ),
                now = now,
            )
        }
        return next
    }

    private suspend fun reconcileSystemExecutors(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        var next = run
        val tasks = definition.tasks.associateBy { it.id }
        for ((id, original) in run.taskRuns) {
            val taskRun = next.taskRuns[id] ?: original
            if (taskRun.status != TaskRunStatus.Running && taskRun.status != TaskRunStatus.Verifying) {
                continue
            }
            if (remoteComputeCoordinator?.owns(taskRun) == true) continue
            val task = tasks[id] ?: continue
            val executor = taskRun.executor ?: task.effectiveExecutor()
            if (!executor.isSystemExecutor()) continue
            val integration = executorIntegrations.integrationFor(executor, project) ?: continue
            next = applyExecution(
                run = next,
                id = id,
                previous = taskRun,
                executor = executor,
                execution = integration.reconcile(
                    TaskExecutorContext(
                        project = project,
                        definition = definition,
                        run = next,
                        task = task,
                        taskRun = taskRun,
                        executor = executor,
                        nowEpochMillis = now,
                        role = engine.roleDefinition(next, task.roleId),
                    ),
                ),
                now = now,
            )
        }
        return next
    }

    private fun refreshAfterSystemExecution(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val refreshed = WorkflowRunFactory.refreshReadiness(definition, run, now)
        return if (
            refreshed.taskRuns.isNotEmpty() &&
            refreshed.taskRuns.values.all { it.status == TaskRunStatus.Cancelled }
        ) {
            refreshed.copy(status = WorkflowRunStatus.Cancelled, updatedAtEpochMillis = now)
        } else if (
            refreshed.taskRuns.isNotEmpty() &&
            refreshed.taskRuns.values.all {
                it.status == TaskRunStatus.Completed || it.status == TaskRunStatus.Cancelled
            }
        ) {
            refreshed.copy(status = WorkflowRunStatus.Completed, updatedAtEpochMillis = now)
        } else {
            refreshed
        }
    }

    private suspend fun dispatchRemoteCompute(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val coordinator = remoteComputeCoordinator
        val tasks = definition.tasks.associateBy { it.id }
        var next = run

        for ((id, original) in run.taskRuns) {
            val task = tasks[id] ?: continue
            val taskRun = next.taskRuns[id] ?: original
            if (task.computePlacement == com.hereliesaz.geministrator.domain.ComputePlacementPolicy.LocalOnly) continue
            if (taskRun.status != TaskRunStatus.Ready && taskRun.status != TaskRunStatus.Retrying) continue
            if (taskRun.externalRunId != null) continue

            if (coordinator == null) {
                if (task.computePlacement.allowsLocalFallback()) continue
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Blocked)
                next = next.copy(
                    taskRuns = next.taskRuns + (
                        id to taskRun.copy(
                            status = TaskRunStatus.Blocked,
                            blockingReason = BlockingReason(
                                REMOTE_COMPUTE_UNAVAILABLE,
                                "Remote compute is required but no compute mesh is configured.",
                            ),
                            progress = null,
                            progressMessage = null,
                        )
                    ),
                    updatedAtEpochMillis = now,
                )
                continue
            }

            when (
                val decision = coordinator.dispatch(
                    RemoteComputeContext(
                        project = project,
                        definition = definition,
                        run = next,
                        task = task,
                        taskRun = taskRun,
                        nowEpochMillis = now,
                    ),
                )
            ) {
                RemoteComputeDispatch.Local -> Unit
                is RemoteComputeDispatch.Blocked -> {
                    TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Blocked)
                    next = next.copy(
                        taskRuns = next.taskRuns + (
                            id to taskRun.copy(
                                status = TaskRunStatus.Blocked,
                                blockingReason = BlockingReason(
                                    REMOTE_COMPUTE_UNAVAILABLE,
                                    decision.reason,
                                ),
                                progress = null,
                                progressMessage = null,
                            )
                        ),
                        updatedAtEpochMillis = now,
                    )
                }
                is RemoteComputeDispatch.Started -> {
                    TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Running)
                    next = next.copy(
                        status = if (next.status == WorkflowRunStatus.AwaitingHuman) {
                            next.status
                        } else {
                            WorkflowRunStatus.Running
                        },
                        taskRuns = next.taskRuns + (
                            id to taskRun.copy(
                                status = TaskRunStatus.Running,
                                assignedProviderId = null,
                                providerRunId = null,
                                externalRunId = decision.externalRunId,
                                blockingReason = null,
                                progress = 0f,
                                progressMessage = decision.progressMessage,
                            )
                        ),
                        updatedAtEpochMillis = now,
                    )
                }
            }
        }
        return next
    }

    private suspend fun reconcileRemoteCompute(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val coordinator = remoteComputeCoordinator ?: return run
        val tasks = definition.tasks.associateBy { it.id }
        var next = run

        for ((id, original) in run.taskRuns) {
            val taskRun = next.taskRuns[id] ?: original
            if (!coordinator.owns(taskRun)) continue
            if (taskRun.status != TaskRunStatus.Running && taskRun.status != TaskRunStatus.Verifying) continue
            val task = tasks[id] ?: continue
            val update = coordinator.reconcile(
                RemoteComputeContext(
                    project = project,
                    definition = definition,
                    run = next,
                    task = task,
                    taskRun = taskRun,
                    nowEpochMillis = now,
                ),
            ) ?: continue

            if (update.status != taskRun.status) {
                TaskRunTransitions.requireAllowed(taskRun.status, update.status)
            }
            val mergedArtifacts = if (update.artifacts.isEmpty()) {
                taskRun.artifacts
            } else {
                (taskRun.artifacts + update.artifacts).distinctBy { it.id }
            }
            next = next.copy(
                taskRuns = next.taskRuns + (
                    id to taskRun.copy(
                        status = update.status,
                        artifacts = mergedArtifacts,
                        blockingReason = null,
                        progress = update.progress
                            ?: if (update.status == TaskRunStatus.Completed) 1f else taskRun.progress,
                        progressMessage = update.progressMessage ?: taskRun.progressMessage,
                    )
                ),
                updatedAtEpochMillis = now,
            )
        }
        return WorkflowRunFactory.refreshReadiness(definition, next, now)
    }

    private fun com.hereliesaz.geministrator.domain.ComputePlacementPolicy.allowsLocalFallback(): Boolean =
        when (this) {
            com.hereliesaz.geministrator.domain.ComputePlacementPolicy.LocalOnly -> true
            is com.hereliesaz.geministrator.domain.ComputePlacementPolicy.RemoteAllowed -> allowLocalFallback
            is com.hereliesaz.geministrator.domain.ComputePlacementPolicy.PreferredDevice -> allowLocalFallback
            is com.hereliesaz.geministrator.domain.ComputePlacementPolicy.Distributed ->
                allowSingleDeviceFallback && allowLocalParticipant
        }

    private fun applyExecution(
        run: WorkflowRun,
        id: TaskDefinitionId,
        previous: TaskRun,
        executor: TaskExecutor,
        execution: TaskExecutorExecution,
        now: Long,
    ): WorkflowRun {
        if (execution.status != previous.status) {
            TaskRunTransitions.requireAllowed(previous.status, execution.status)
        }
        return run.copy(
            taskRuns = run.taskRuns + (
                id to previous.copy(
                    status = execution.status,
                    executor = executor,
                    assignedProviderId = null,
                    providerRunId = null,
                    externalRunId = execution.externalRunId ?: previous.externalRunId,
                    artifacts = if (execution.artifacts.isEmpty()) previous.artifacts else execution.artifacts,
                    blockingReason = null,
                    progress = execution.progress
                        ?: if (execution.status == TaskRunStatus.Completed) 1f else previous.progress,
                    progressMessage = execution.progressMessage ?: previous.progressMessage,
                )
            ),
            updatedAtEpochMillis = now,
        )
    }

    private fun preserveArtifactTimestamps(
        previous: WorkflowRun,
        reconciled: WorkflowRun,
    ): WorkflowRun {
        val previousArtifacts = previous.taskRuns.values
            .flatMap(TaskRun::artifacts)
            .associateBy { it.id }
        if (previousArtifacts.isEmpty()) return reconciled

        return reconciled.copy(
            taskRuns = reconciled.taskRuns.mapValues { (_, taskRun) ->
                taskRun.copy(
                    artifacts = taskRun.artifacts.map { artifact ->
                        previousArtifacts[artifact.id]?.let {
                            artifact.copy(createdAtEpochMillis = it.createdAtEpochMillis)
                        } ?: artifact
                    },
                )
            },
        )
    }

    private fun recoverAvailableSystemExecutors(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val definitions = definition.tasks.associateBy { it.id }
        var changed = false
        val taskRuns = run.taskRuns.mapValues { (id, taskRun) ->
            if (
                taskRun.status != TaskRunStatus.Blocked ||
                taskRun.blockingReason?.code != WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE
            ) {
                return@mapValues taskRun
            }
            val executor = taskRun.executor ?: definitions[id]?.executor ?: return@mapValues taskRun
            if (!executor.isSystemExecutor() || !executorIntegrations.isAvailable(executor, project)) {
                return@mapValues taskRun
            }
            val recovered = if (taskRun.externalRunId != null) {
                TaskRunStatus.Running
            } else {
                TaskRunStatus.Ready
            }
            TaskRunTransitions.requireAllowed(TaskRunStatus.Blocked, recovered)
            changed = true
            taskRun.copy(status = recovered, blockingReason = null)
        }
        return if (changed) {
            run.copy(taskRuns = taskRuns, updatedAtEpochMillis = now)
        } else {
            run
        }
    }

    private fun blockUnavailableSystemExecutors(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val definitions = definition.tasks.associateBy { it.id }
        var changed = false
        val taskRuns = run.taskRuns.mapValues { (id, taskRun) ->
            val executor = taskRun.executor ?: definitions[id]?.executor
            val blockable = taskRun.status in setOf(
                TaskRunStatus.Ready,
                TaskRunStatus.Retrying,
                TaskRunStatus.Running,
                TaskRunStatus.Verifying,
            )
            if (
                blockable &&
                remoteComputeCoordinator?.owns(taskRun) != true &&
                executor != null &&
                executor.isSystemExecutor() &&
                !executorIntegrations.isAvailable(executor, project)
            ) {
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Blocked)
                changed = true
                taskRun.copy(
                    status = TaskRunStatus.Blocked,
                    blockingReason = BlockingReason(
                        WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE,
                        "${executor.displayLabel()} is not available in this runtime.",
                    ),
                    progress = null,
                    progressMessage = null,
                )
            } else {
                taskRun
            }
        }
        return if (changed) {
            run.copy(taskRuns = taskRuns, updatedAtEpochMillis = now)
        } else {
            run
        }
    }

    private fun TaskExecutor.displayLabel(): String = when (this) {
        is TaskExecutor.GitHubAction -> "GitHub Action executor"
        is TaskExecutor.TestRunner -> "Test runner executor"
        is TaskExecutor.Deployment -> "Deployment executor"
        is TaskExecutor.RepositoryOperation -> "Repository operation executor"
        is TaskExecutor.ExternalService -> "External service executor"
        is TaskExecutor.NestedWorkflow -> "Nested workflow executor"
        else -> "System executor"
    }

    private companion object {
        const val REMOTE_COMPUTE_UNAVAILABLE: String = "remote_compute_unavailable"
    }

    private fun TaskRunStatus.isActiveProviderStatus(): Boolean = this in setOf(
        TaskRunStatus.Planning,
        TaskRunStatus.AwaitingApproval,
        TaskRunStatus.Running,
        TaskRunStatus.Verifying,
    )

    private fun TaskRunStatus.canReconnect(): Boolean = isActiveProviderStatus()

    private fun TaskRunStatus.toManagedStatus(): ManagedSessionStatus = when (this) {
        TaskRunStatus.Planning -> ManagedSessionStatus.Planning
        TaskRunStatus.AwaitingApproval -> ManagedSessionStatus.AwaitingApproval
        TaskRunStatus.Running,
        TaskRunStatus.Verifying,
        -> ManagedSessionStatus.Running
        TaskRunStatus.Completed -> ManagedSessionStatus.Completed
        TaskRunStatus.Failed -> ManagedSessionStatus.Failed
        else -> ManagedSessionStatus.Unknown
    }

    private fun WorkflowRunStatus.isTerminal(): Boolean = this in setOf(
        WorkflowRunStatus.Completed,
        WorkflowRunStatus.Failed,
        WorkflowRunStatus.Cancelled,
    )
}
