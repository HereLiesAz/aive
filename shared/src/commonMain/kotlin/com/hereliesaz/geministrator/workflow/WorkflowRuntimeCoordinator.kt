package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.SpendCapKind
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
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.policy.InProcessPolicyDecisionPoint
import com.hereliesaz.geministrator.policy.PolicyAction
import com.hereliesaz.geministrator.policy.PolicyContext
import com.hereliesaz.geministrator.policy.PolicyDecision
import com.hereliesaz.geministrator.policy.PolicyRequest
import com.hereliesaz.geministrator.policy.PolicyResource
import com.hereliesaz.geministrator.policy.PolicySubject
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /** The keeper's spend caps, read every cycle so a change in the Keeper's room applies at once. */
    private val spendCaps: () -> SpendCaps = { SpendCaps() },
) {
    private val gateCoordinator = ApprovalGateCoordinator(
        persistence.approvalGates,
        RepositoryWorkflowEventSink(persistence.events),
    )
    private val cycleMutex = Mutex()
    /** When the hourly spend was last summed; it is summed again when this run's cost rises. */
    private var lastHourlySpendCheckAtMillis: Long? = null

    suspend fun persist(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
    ) {
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(state.run)
        persistArtifacts(state.run)
        // Refinement-ladder training data, only while the keeper has switched capture on; it never
        // affects the run (workflow/refinement/LadderDataset.kt).
        try {
            LadderDatasetBridge.sink.observe(definition, state.run)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        // A reviewer's verdict on a system executor's work becomes a verification receipt, written
        // through the same opt-in sink as the rest; it never affects the run.
        try {
            procedureVerifications.observe(project, definition, state.run)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    private val procedureVerifications = ProcedureVerificationObserver { MemoryRuntimeBridge.procedureTraceSink }

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
            // Every state this coordinator produces is persisted, so a different persisted run with
            // the same timestamp was written by someone else (a decision in the same millisecond).
            persistedRun.updatedAtEpochMillis >= state.run.updatedAtEpochMillis
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
        if (state.run.status.isTerminal()) {
            // A run made terminal outside the cycle (an escalation decision) must not leave its
            // provider sessions running and billing.
            if (state.handles.isEmpty()) return state
            cancelSessions(state.run, state.handles)
            return state.copy(handles = emptyMap())
        }
        if (state.run.spendPause != null) {
            // Stopped by a spend cap: nothing runs or starts until the keeper lifts the stop.
            if (state.handles.isEmpty()) return state
            cancelSessions(state.run, state.handles)
            return state.copy(handles = emptyMap())
        }

        var nextRun = reconcileResolvedApprovalGates(definition, state.run, nowEpochMillis)
        if (nextRun.status.isTerminal()) {
            return terminate(project, definition, nextRun, state.handles, nowEpochMillis)
        }
        // A role whose budget the keeper raised (or cleared) takes work again.
        nextRun = engine.releaseRoleBudgetHolds(nextRun, nowEpochMillis)

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
            // The executor's last status line; for provider sessions this is the provider's failure reason.
            var failureReason = nextRun.taskRuns[taskId]?.progressMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { "Executor failed. Last status: $it" }
                ?: "Executor failed"
            // A provider's own failure (credit, key, rate limit, offline, 5xx) mid-task restarts the
            // task on another provider when the keeper left the choice to the app; a pinned
            // creature, or one with nothing left to switch to, fails with a message naming who and what to do.
            if (retryReason == RetryReason.ProviderFailure) {
                val failedProvider = state.handles[taskId]?.providerId ?: state.run.taskRuns[taskId]?.assignedProviderId
                val selection = engine.selectionFor(project, definition, nextRun, taskId)
                if (selection != null) {
                    val failover = engine.failOver(
                        run = nextRun,
                        taskDefinitionId = taskId,
                        failedProviderId = failedProvider,
                        reason = nextRun.taskRuns[taskId]?.progressMessage,
                        selection = selection,
                        nowEpochMillis = nowEpochMillis,
                    )
                    failover.restarted?.let { nextRun = it; continue }
                    failover.stopMessage?.let { failureReason = "Executor failed. $it" }
                }
            }
            nextRun = engine.handleFailure(
                definition = definition,
                run = nextRun,
                taskDefinitionId = taskId,
                retryReason = retryReason,
                reason = failureReason,
                nowEpochMillis = nowEpochMillis,
            )
            if (nextRun.taskRuns[taskId]?.status == TaskRunStatus.Escalated) {
                ensureFailureEscalationGate(
                    run = nextRun,
                    taskId = taskId,
                    reason = failureReason,
                    now = nowEpochMillis,
                )
            }
        }

        if (nextRun.status.isTerminal()) {
            return terminate(project, definition, nextRun, state.handles, nowEpochMillis)
        }

        ensureMissingFailureEscalationGates(nextRun, nowEpochMillis)
        nextRun = holdTaintedPrivilegedTasks(definition, nextRun, nowEpochMillis)

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

        // Spend circuit breakers: a reached cap stops the whole run before anything else starts.
        spendCapTrip(previous = state.run, run = nextRun, nowEpochMillis = nowEpochMillis)?.let { trip ->
            return stopForSpendCap(project, definition, nextRun, nextHandles, trip, nowEpochMillis)
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
            return terminate(project, definition, nextRun, nextHandles, nowEpochMillis)
        }
        // Tasks that system executors made ready in this cycle pass the same gate before agents start.
        nextRun = holdTaintedPrivilegedTasks(definition, nextRun, nowEpochMillis)

        val dispatched = engine.dispatchReadyTasks(
            project = project,
            definition = definition,
            run = nextRun,
            existingHandles = nextHandles,
            nowEpochMillis = nowEpochMillis,
        )
        nextRun = dispatched.run
        if (nextRun.status.isTerminal()) {
            return terminate(project, definition, nextRun, dispatched.handles, nowEpochMillis)
        }
        nextHandles = dispatched.handles.filterKeys { taskId ->
            nextRun.taskRuns[taskId]?.status?.isActiveProviderStatus() == true
        }
        nextState = WorkflowRuntimeState(nextRun, nextHandles)
        persist(project, definition, nextState)
        return nextState
    }

    /**
     * Cancels [state]'s run: every unfinished task, every provider session it still holds, and the
     * remote work behind every unfinished system-executor task (GitHub Actions runs, nested child
     * runs, distributed leases, external service runs). Used when a run is abandoned, such as a
     * new launch replacing it.
     *
     * The local cancellation is persisted first. Remote cancels follow, best effort, outside the
     * cycle lock; one that fails is recorded as [ExternalRunCancelFailed] and the rest still run.
     */
    suspend fun cancel(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        nowEpochMillis: Long,
    ): WorkflowRuntimeState {
        val (cancelled, remote) = cycleMutex.withLock {
            val persistedRun = persistence.runs.get(state.run.id)
                ?.takeIf { it.updatedAtEpochMillis > state.run.updatedAtEpochMillis }
                ?: state.run
            if (persistedRun.status.isTerminal()) {
                cancelSessions(persistedRun, state.handles)
                return@withLock WorkflowRuntimeState(persistedRun, emptyMap()) to emptyList<ExternalRunToCancel>()
            }
            val pending = unfinishedExternalRuns(definition, persistedRun)
            terminate(
                project,
                definition,
                engine.cancelWorkflow(persistedRun, nowEpochMillis),
                state.handles,
                nowEpochMillis,
            ) to pending
        }
        cancelExternalRuns(project, definition, cancelled.run, remote, nowEpochMillis)
        return cancelled
    }

    private data class ExternalRunToCancel(
        val task: TaskDefinition,
        val taskRun: TaskRun,
        val executor: TaskExecutor,
    )

    /** Unfinished system-executor tasks that hold a remote handle, read before the run is cancelled. */
    private fun unfinishedExternalRuns(
        definition: WorkflowDefinition,
        run: WorkflowRun,
    ): List<ExternalRunToCancel> {
        val tasks = definition.tasks.associateBy { it.id }
        return run.taskRuns.mapNotNull { (id, taskRun) ->
            if (taskRun.externalRunId == null) return@mapNotNull null
            if (taskRun.status in TERMINAL_TASK_STATUSES) return@mapNotNull null
            // The compute mesh owns these handles, not an executor integration.
            if (remoteComputeCoordinator?.owns(taskRun) == true) return@mapNotNull null
            val task = tasks[id] ?: return@mapNotNull null
            val executor = taskRun.executor ?: task.effectiveExecutor()
            if (!executor.isSystemExecutor()) return@mapNotNull null
            ExternalRunToCancel(task, taskRun, executor)
        }
    }

    /** Best effort: each failure is recorded as an event and never stops the others. */
    private suspend fun cancelExternalRuns(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        remote: List<ExternalRunToCancel>,
        nowEpochMillis: Long,
    ) {
        if (remote.isEmpty()) return
        withContext(NonCancellable) {
            for ((task, taskRun, executor) in remote) {
                val externalRunId = taskRun.externalRunId ?: continue
                val failure: Throwable? = try {
                    executorIntegrations.integrationFor(executor, project)?.cancel(
                        TaskExecutorContext(
                            project = project,
                            definition = definition,
                            run = run,
                            task = task,
                            taskRun = taskRun,
                            executor = executor,
                            nowEpochMillis = nowEpochMillis,
                            role = engine.roleDefinition(run, task.roleId),
                        ),
                    )
                    null
                } catch (thrown: Throwable) {
                    // Including a CancellationException from the integration itself: this block is
                    // NonCancellable, so the remaining remote cancels still go out.
                    thrown
                }
                if (failure == null) continue
                try {
                    persistence.events.append(
                        ExternalRunCancelFailed(
                            workflowRunId = run.id,
                            taskDefinitionId = task.id,
                            externalRunId = externalRunId,
                            reason = failure.message?.takeIf(String::isNotBlank)
                                ?: "${executor.displayLabel()} could not be cancelled",
                            occurredAtEpochMillis = nowEpochMillis,
                        ),
                    )
                } catch (_: Throwable) {
                    // Recording the failure is itself best effort.
                }
            }
        }
    }

    /** Ends a run that has just become terminal: closes its gates, stops its sessions, persists it. */
    private suspend fun terminate(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ): WorkflowRuntimeState {
        val closed = closeFailureEscalationsForTerminalRun(run, nowEpochMillis)
        cancelSessions(closed, handles)
        val terminalState = WorkflowRuntimeState(closed, emptyMap())
        persist(project, definition, terminalState)
        return terminalState
    }

    /**
     * The spend cap [run] has reached, or null. The run's own cost is checked every cycle; the
     * hourly spend across runs is summed when this run's known cost rises, and at least once a
     * minute otherwise. A Hall Monitor pause is left to its own review.
     */
    private suspend fun spendCapTrip(previous: WorkflowRun, run: WorkflowRun, nowEpochMillis: Long): SpendCapTrip? {
        if (run.globalPause != null || run.spendPause != null) return null
        val caps = spendCaps()
        runCostTrip(run, caps)?.let { return it }
        val hourlyCap = caps.hourlySpendCapUsd ?: return null
        val lastCheck = lastHourlySpendCheckAtMillis
        val costRose = run.knownCostUsd() > previous.knownCostUsd()
        if (!costRose && lastCheck != null && nowEpochMillis - lastCheck < HOURLY_SPEND_RECHECK_MILLIS) return null
        lastHourlySpendCheckAtMillis = nowEpochMillis
        val spent = hourlySpendUsd(persistence, nowEpochMillis, alsoRunId = run.id)
        return if (spent >= hourlyCap) SpendCapTrip(SpendCapKind.HourlySpend, hourlyCap, spent) else null
    }

    /** Cancels the run's live provider sessions and parks it under a spend stop the keeper lifts. */
    private suspend fun stopForSpendCap(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        trip: SpendCapTrip,
        nowEpochMillis: Long,
    ): WorkflowRuntimeState {
        val live = handles.filterKeys { taskId -> run.taskRuns[taskId]?.status?.isActiveProviderStatus() == true }
        cancelSessions(run, live)
        val stopped = pauseRunForSpendCap(run, trip, cancelledTaskIds = live.keys, nowEpochMillis = nowEpochMillis)
        val stoppedState = WorkflowRuntimeState(stopped, emptyMap())
        persist(project, definition, stoppedState)
        return stoppedState
    }

    /** Best effort: a session that already finished, or a provider that is unreachable, is not an error here. */
    private suspend fun cancelSessions(run: WorkflowRun, handles: Map<TaskDefinitionId, ManagedSessionHandle>) {
        val live = handles.filterKeys { taskId -> run.taskRuns[taskId]?.status != TaskRunStatus.Completed }
        if (live.isEmpty()) return
        withContext(NonCancellable) {
            live.values.forEach { handle -> runCatching { sessionGateway.cancel(handle) } }
        }
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
            if (taskRun.isHeldAtContextTaintGate()) {
                ensureContextTaintGate(run, task, taskRun, taskRun.blockingReason?.message.orEmpty(), now)
                continue
            }

            val gateId = ApprovalGateId("plan:${run.id.value}:${taskId.value}:${taskRun.attempt}")
            if (persistence.approvalGates.get(gateId) != null) continue

            // The task already stopped for approval, so ask the policy decision point who must
            // decide. A task with no approval policy only stops here when its provider insists on
            // one (e.g. committing a workflow file to the default branch), which is a person's call.
            val request = PolicyRequest(
                subject = PolicySubject.System,
                action = PolicyAction.ProceedPastPlan(task.approvalPolicy, providerRequested = true),
                resource = PolicyResource.task(taskId),
                context = PolicyContext(workflowRunId = run.id, taskDefinitionId = taskId),
            )
            val decision = InProcessPolicyDecisionPoint.decide(request)
            val requiresHuman = when (decision) {
                is PolicyDecision.RequireApproval -> decision.requiresHuman
                is PolicyDecision.Allow, is PolicyDecision.Deny -> false
            }
            gateCoordinator.open(
                id = gateId,
                workflowRunId = run.id,
                taskDefinitionId = taskId,
                kind = ApprovalGateKind.PlanApproval,
                reason = "Plan approval required for '${task.name}'",
                requiresHuman = requiresHuman,
                nowEpochMillis = now,
            )
            gateCoordinator.recordPolicyDecision(request, decision, now)
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
                    if (tasks[taskId] == null) continue
                    if (taskRun.isHeldAtContextTaintGate()) {
                        val gate = persistence.approvalGates.get(
                            contextTaintGateId(nextRun.id, taskId, taskRun.attempt),
                        ) ?: continue
                        if (
                            gate.status == ApprovalGateStatus.Pending ||
                            gate.status == ApprovalGateStatus.Applying
                        ) {
                            continue
                        }
                        nextRun = if (gate.status == ApprovalGateStatus.Approved) {
                            // Approved: the task may now run, and a provider may still ask for plan approval.
                            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Ready)
                            nextRun.copy(
                                taskRuns = nextRun.taskRuns + (
                                    taskId to taskRun.copy(
                                        status = TaskRunStatus.Ready,
                                        blockingReason = null,
                                        progressMessage = null,
                                    )
                                ),
                                updatedAtEpochMillis = now,
                            )
                        } else {
                            // Retrying would only meet the same inputs at the same gate.
                            engine.handleFailure(
                                definition = definition,
                                run = nextRun,
                                taskDefinitionId = taskId,
                                retryReason = RetryReason.PlanRejected,
                                reason = "Acting on external inputs was not approved",
                                nowEpochMillis = now,
                                allowRetry = false,
                            )
                        }
                        if (nextRun.status.isTerminal()) break
                        continue
                    }
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

    /**
     * Holds each ready task that can change something and whose inputs include external or unknown
     * content at a human gate naming those inputs (ContextTaint.kt). An approved gate lets it run.
     */
    private suspend fun holdTaintedPrivilegedTasks(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val tasks = definition.tasks.associateBy { it.id }
        var next = run
        for ((id, taskRun) in run.taskRuns) {
            if (taskRun.status != TaskRunStatus.Ready && taskRun.status != TaskRunStatus.Retrying) continue
            if (taskRun.externalRunId != null) continue
            val task = tasks[id] ?: continue
            val executor = taskRun.executor ?: task.effectiveExecutor()
            val roleId = (executor.withoutPlacement() as? TaskExecutor.RoleAgent)?.roleId ?: task.roleId
            val untrusted = definition.untrustedInputsForPrivilegedTask(
                run = next,
                task = task,
                executor = executor,
                role = engine.roleDefinition(next, roleId),
            )
            if (untrusted.isEmpty()) continue
            val gate = persistence.approvalGates.get(contextTaintGateId(next.id, id, taskRun.attempt))
            if (gate?.status == ApprovalGateStatus.Approved) continue

            val reason = contextTaintReason(task, untrusted)
            ensureContextTaintGate(next, task, taskRun, reason, now)
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.AwaitingApproval)
            next = next.copy(
                taskRuns = next.taskRuns + (
                    id to taskRun.copy(
                        status = TaskRunStatus.AwaitingApproval,
                        executor = executor,
                        blockingReason = BlockingReason(CONTEXT_TAINT_BLOCKING_CODE, reason),
                        progress = null,
                        progressMessage = null,
                    )
                ),
                updatedAtEpochMillis = now,
            )
        }
        return next
    }

    private suspend fun ensureContextTaintGate(
        run: WorkflowRun,
        task: TaskDefinition,
        taskRun: TaskRun,
        reason: String,
        now: Long,
    ) {
        val gateId = contextTaintGateId(run.id, task.id, taskRun.attempt)
        if (persistence.approvalGates.get(gateId) != null) return
        gateCoordinator.open(
            id = gateId,
            workflowRunId = run.id,
            taskDefinitionId = task.id,
            kind = ApprovalGateKind.SecurityRisk,
            reason = reason.ifBlank { "'${task.name}' can change things and its inputs include external content" },
            requiresHuman = true,
            nowEpochMillis = now,
        )
    }

    /** Labels what [executor] produced for [task] with where it came from (ContextTaint.kt). */
    private fun labelOutputs(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        task: TaskDefinition,
        executor: TaskExecutor,
        execution: TaskExecutorExecution,
    ): TaskExecutorExecution {
        if (execution.artifacts.isEmpty()) return execution
        return execution.copy(
            artifacts = definition.labelTaskOutputs(
                run = run,
                task = task,
                assigned = executor.outputProvenance(engine.roleDefinition(run, task.roleId)),
                artifacts = execution.artifacts,
            ),
        )
    }

    private suspend fun dispatchSystemExecutors(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        var next = run
        // What may be saved mid-loop: the dispatches that started, never a failure the retry and
        // escalation policy has not yet seen (the cycle saves those once it has applied it).
        var durable = run
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
            // A throwing integration fails its own task (through the normal retry/escalation path)
            // instead of escaping the cycle and leaving the task Ready forever.
            val dispatched = try {
                integration.dispatch(
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
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                TaskExecutorExecution(
                    status = TaskRunStatus.Failed,
                    progressMessage = failure.message?.takeIf(String::isNotBlank)
                        ?: "${executor.displayLabel()} dispatch failed",
                )
            }
            val execution = labelOutputs(definition, next, task, executor, dispatched)
            next = applyExecution(
                run = next,
                id = id,
                previous = taskRun,
                executor = executor,
                execution = execution,
                now = now,
            )
            // Record each started external run at once, so nothing later in this cycle can lose it
            // and cause the same run to be dispatched again.
            if (execution.status != TaskRunStatus.Failed) {
                durable = applyExecution(run = durable, id = id, previous = taskRun, executor = executor, execution = execution, now = now)
                persistence.runs.put(durable)
            }
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
                execution = labelOutputs(
                    definition = definition,
                    run = next,
                    task = task,
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
                ),
                now = now,
            )
        }
        return next
    }

    private suspend fun refreshAfterSystemExecution(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ): WorkflowRun {
        val refreshed = engine.refreshReadiness(definition, run, now)
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
            // An engine-approved plan is executed by the provider that drafted it, locally.
            if (taskRun.approvedEnginePlan() != null) continue

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
                                progress = null,
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
                // Self-attested by whichever pool member ran it: external, whatever it claims.
                val received = definition.labelTaskOutputs(next, task, ContextProvenance.ExternalContent, update.artifacts)
                (taskRun.artifacts + received).distinctBy { it.id }
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
        return engine.refreshReadiness(definition, next, now)
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
        val TERMINAL_TASK_STATUSES: Set<TaskRunStatus> = setOf(
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        )
        const val HOURLY_SPEND_RECHECK_MILLIS: Long = 60_000L
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
