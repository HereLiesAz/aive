package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
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

/**
 * The hive [run] follows: the definition it recorded when it started (with the job and criteria edits
 * it took), else the saved hive under its id for a run saved before runs recorded one. The saved hive
 * can be changed while the run is live, so a run is never resumed against it when it has its own copy.
 */
internal suspend fun WorkflowPersistence.definitionFor(run: WorkflowRun): WorkflowDefinition? =
    run.definitionSnapshot ?: definitions.get(run.workflowDefinitionId)

/**
 * The one freshness rule between the copy of a run a runtime holds ([live]) and the copy in the store
 * ([stored]), used by every reader (the cycle, a rewind, a cancel, the runtime's own decisions).
 * Whatever this runtime produces is stored as it is produced, so a stored copy that differs from the live
 * one was written by someone else (a decision on a gate) and is the newer, unless the live one is
 * strictly newer (a change not stored yet). Never a strict comparison in one place and a loose one in another.
 */
internal fun newestOf(live: WorkflowRun, stored: WorkflowRun?): WorkflowRun =
    if (stored != null && stored != live && stored.updatedAtEpochMillis >= live.updatedAtEpochMillis) stored else live

/**
 * The time to stamp a decision made on [run]: never before what it decides, so a clock that stepped
 * back cannot make the decision look older than the copy it replaces and be discarded by [newestOf].
 */
internal fun decisionTime(nowEpochMillis: Long, run: WorkflowRun): Long =
    maxOf(nowEpochMillis, run.updatedAtEpochMillis + 1)

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
    /** What billed generations outside any run spent; the hourly cap counts it. */
    private val ambientSpend: AmbientSpendLedger = AmbientSpendLedger.Shared,
    /**
     * The queen's own allowance for a creature that has started an attempt ([DeadlinePolicy.resolveAuto]); the
     * default gives none, so a coordinator built without it times nothing.
     */
    private val deadlineAllowance: (TaskDefinition, TaskRun) -> Allowance = { _, _ -> Allowance.None },
    /**
     * Told how long an ended attempt actually worked (active milliseconds: a step that finished, or one stopped at
     * its stop point), so the queen's Auto allowance can learn from it ([DeadlineHistory]). Does nothing by default.
     */
    private val deadlineSample: (TaskDefinition, TaskRun, Long) -> Unit = { _, _, _ -> },
    /** Whether a creature past its hard point is stopped ([HARD_DEADLINES_ENABLED]); tests set it. */
    private val enforceDeadlines: Boolean = HARD_DEADLINES_ENABLED,
) {
    private val gateCoordinator = ApprovalGateCoordinator(
        persistence.approvalGates,
        RepositoryWorkflowEventSink(persistence.events),
    )
    private val eventSink = RepositoryWorkflowEventSink(persistence.events)
    private val cycleMutex = Mutex()
    /** How many times each provider session has refused to stop (under [cycleMutex], like every cancel). */
    private val sessionCancelFailures = mutableMapOf<ProviderRunId, Int>()
    /** When the hourly spend was last summed; it is summed again when this run's cost rises. */
    private var lastHourlySpendCheckAtMillis: Long? = null

    /** What was last written to the store, so an unchanged run, project or artifact is not written again. */
    private var lastPersistedProject: Project? = null
    private var lastPersistedRun: WorkflowRun? = null
    private var storedDefinitionId: com.hereliesaz.geministrator.domain.WorkflowDefinitionId? = null
    private val persistedArtifacts = mutableMapOf<ArtifactId, com.hereliesaz.geministrator.domain.ArtifactRef>()

    suspend fun persist(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
    ) {
        // Each write is a whole-store rewrite on some backends (the web's settings), and a cycle persists
        // several times: what has not changed since it was last written is not written again.
        if (lastPersistedProject != project) {
            persistence.projects.put(project)
            lastPersistedProject = project
        }
        // The saved hive under this id is the keeper's, and a save made while the run lives is newer than
        // the copy this run holds: it is not written over (docs/Keepers-Room.md, Pause, change, resume).
        // Launching and forking store the definition a run follows; this stores it only if there is none.
        if (storedDefinitionId != definition.id) {
            if (persistence.definitions.get(definition.id) == null) persistence.definitions.put(definition)
            storedDefinitionId = definition.id
        }
        if (lastPersistedRun != state.run) {
            persistence.runs.put(state.run)
            lastPersistedRun = state.run
        }
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
        // A warning an agent wrote while handling a system executor's result becomes a flag on the
        // habit, in its own words, through the same sink; it never affects the run.
        try {
            habitFlags.observe(project, definition, state.run)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        // A decision a model wrote about a habit becomes a Deliberation receipt, unless the task had
        // untrusted content in front of it; it never affects the run.
        try {
            habitDecisions.observe(project, definition, state.run)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    private val procedureVerifications = ProcedureVerificationObserver { MemoryRuntimeBridge.procedureTraceSink }
    private val habitDecisions = HabitDecisionObserver { MemoryRuntimeBridge.procedureTraceSink }
    private val habitFlags = HabitFlagObserver(clerk = { MemoryRuntimeBridge.cautionClerk }, sink = { MemoryRuntimeBridge.procedureTraceSink })

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
        val definition = requireNotNull(persistence.definitionFor(run)) {
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
        val newest = newestOf(state.run, persistence.runs.get(state.run.id))
        val authoritativeState = if (newest !== state.run) {
            WorkflowRuntimeState(
                run = newest,
                handles = mergeHandles(state.handles, newest),
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
            // A session that would not stop stays on the state, and is asked again next cycle.
            return stopRemainingSessions(state, nowEpochMillis)
        }
        if (state.run.spendPause != null) {
            // Stopped by a spend cap: nothing runs or starts until the keeper lifts the stop.
            if (state.handles.isEmpty()) return state
            return stopRemainingSessions(state, nowEpochMillis)
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
        val beforeDeadlines = nextRun
        val progress = progressSeen(state.handles, nowEpochMillis)
        val deadlines = DeadlineReducer.reduce(
            definition, nextRun, nowEpochMillis, progressAt = { progress[it] }, autoFor = deadlineAllowance,
            enforceHard = enforceDeadlines,
        )
        nextRun = deadlines.run
        val hardStopped = deadlines.hardStops.keys
        if (hardStopped.isNotEmpty()) {
            nextRun = stopForDeadlines(project, definition, beforeDeadlines, nextRun, state.handles, hardStopped, nowEpochMillis)
        }
        recordDeadlineSamples(definition, state.run, deadlines.run, hardStopped, nowEpochMillis)
        persistArtifacts(nextRun)

        val newlyFailedTaskIds = nextRun.taskRuns
            .filter { (taskId, taskRun) ->
                taskRun.status == TaskRunStatus.Failed &&
                    state.run.taskRuns[taskId]?.status != TaskRunStatus.Failed
            }
            .keys
        // What waits on a failure (an on-failure handler, an any-outcome task) is released only after
        // the failure policy below has decided: a task about to be retried has not failed for good.
        nextRun = refreshAfterSystemExecution(definition, nextRun, nowEpochMillis, newlyFailedTaskIds)

        if (newlyFailedTaskIds.isNotEmpty() && nextRun.status == WorkflowRunStatus.Failed) {
            nextRun = nextRun.copy(status = WorkflowRunStatus.Running)
        }

        for (taskId in newlyFailedTaskIds) {
            if (nextRun.status.isTerminal()) break
            if ((nextRun.taskRuns[taskId]?.timedOutAttempts ?: 0) >= MAX_TIMEOUTS) {
                // Slow twice: the keeper decides, whatever the step's escalation policy says.
                val reason = "Took longer than allowed twice. ${nextRun.taskRuns[taskId]?.progressMessage.orEmpty()}".trim()
                nextRun = engine.escalateTimedOut(nextRun, taskId, reason, nowEpochMillis)
                ensureFailureEscalationGate(run = nextRun, taskId = taskId, reason = reason, now = nowEpochMillis)
                continue
            }
            // A step that used its whole token allowance is stopped, not tried again: another attempt would spend it again.
            val allowanceStop = nextRun.taskRuns[taskId]?.blockingReason?.takeIf { it.code == STEP_ALLOWANCE_EXCEEDED }
            if (allowanceStop != null) {
                nextRun = engine.handleFailure(
                    definition = definition,
                    run = nextRun,
                    taskDefinitionId = taskId,
                    retryReason = RetryReason.ProviderFailure,
                    reason = allowanceStop.message,
                    nowEpochMillis = nowEpochMillis,
                    allowRetry = false,
                )
                if (nextRun.taskRuns[taskId]?.status == TaskRunStatus.Escalated) {
                    ensureFailureEscalationGate(run = nextRun, taskId = taskId, reason = allowanceStop.message, now = nowEpochMillis)
                }
                continue
            }
            val previousStatus = state.run.taskRuns[taskId]?.status
            val outputRejected = nextRun.taskRuns[taskId]?.blockingReason?.takeIf { it.code == TASK_OUTPUT_REJECTED }
            val retryReason = when {
                outputRejected != null -> RetryReason.VerificationFailed
                previousStatus == TaskRunStatus.Verifying -> RetryReason.VerificationFailed
                previousStatus == TaskRunStatus.Planning -> RetryReason.PlanRejected
                else -> RetryReason.ProviderFailure
            }
            // The executor's last status line; for provider sessions this is the provider's failure reason.
            var failureReason = nextRun.taskRuns[taskId]?.progressMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { "Executor failed. Last status: $it" }
                ?: "Executor failed"
            // A turned-back output says what to fix, not that the executor failed.
            outputRejected?.let { failureReason = it.message }
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

        var nextHandles = (state.handles - hardStopped).filterKeys { taskId ->
            nextRun.taskRuns[taskId]?.status !in setOf(
                TaskRunStatus.Completed,
                TaskRunStatus.Failed,
                TaskRunStatus.Cancelled,
                TaskRunStatus.Retrying,
                TaskRunStatus.Escalated,
                // Parked (a role over its budget): its session was cancelled, and a handle kept on a parked
                // task would read that session as Failed and fail the task it only paused.
                TaskRunStatus.Blocked,
            )
        }

        // A handle the run no longer follows has read all it will: what it last reported is counted, and the
        // gateway forgets the session.
        nextRun = settleDroppedHandles(nextRun, (state.handles - hardStopped).filterKeys { it !in nextHandles }, nowEpochMillis)

        // Spend circuit breakers: a reached cap stops the whole run before anything else starts.
        spendCapTrip(previous = state.run, run = nextRun, nowEpochMillis = nowEpochMillis)?.let { trip ->
            return stopForSpendCap(project, definition, nextRun, nextHandles, trip, nowEpochMillis)
        }

        // The keeper's pause: what already runs is reconciled above and finishes; nothing new starts.
        val keeperPaused = nextRun.keeperPause != null
        if (!keeperPaused) nextRun = dispatchRemoteCompute(project, definition, nextRun, nowEpochMillis)
        nextRun = blockUnavailableSystemExecutors(project, definition, nextRun, nowEpochMillis)
        ensurePlanApprovalGates(definition, nextRun, nowEpochMillis)

        var nextState = WorkflowRuntimeState(nextRun, nextHandles)
        persist(project, definition, nextState)

        val beforeSystemDispatch = nextRun
        if (!keeperPaused) nextRun = dispatchSystemExecutors(project, definition, nextRun, nowEpochMillis)

        val dispatchFailedTaskIds = nextRun.taskRuns
            .filter { (taskId, taskRun) ->
                taskRun.status == TaskRunStatus.Failed &&
                    beforeSystemDispatch.taskRuns[taskId]?.status != TaskRunStatus.Failed
            }
            .keys
        nextRun = refreshAfterSystemExecution(definition, nextRun, nowEpochMillis, dispatchFailedTaskIds)
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
        // A failure handled above can have settled what a task waits on (an any-outcome or on-failure
        // task behind a creature that just escalated): that task is released now, so the gate sees it,
        // instead of by the dispatch below, which would start it with nothing between.
        nextRun = engine.refreshReadiness(definition, nextRun, nowEpochMillis)
        nextRun = holdTaintedPrivilegedTasks(definition, nextRun, nowEpochMillis)

        if (!keeperPaused) {
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
            nextRun = settleDroppedHandles(nextRun, dispatched.handles.filterKeys { it !in nextHandles }, nowEpochMillis)
        }
        nextState = WorkflowRuntimeState(nextRun, nextHandles)
        persistWhatStarted(project, definition, nextState, started = nextHandles.filterKeys { it !in state.handles }, nowEpochMillis)
        return nextState
    }

    /**
     * Saves the run that now holds sessions this cycle started. A cancellation that arrives while it is
     * being saved must not lose them: the sessions exist, and a reopened runtime that never learnt of
     * them would start the same tasks again. So it is saved to the end, and if it cannot be saved the
     * new sessions are stopped before the failure goes on.
     */
    private suspend fun persistWhatStarted(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        started: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ) {
        if (started.isEmpty()) {
            persist(project, definition, state)
            return
        }
        try {
            withContext(NonCancellable) { persist(project, definition, state) }
        } catch (failure: Throwable) {
            cancelSessions(state.run, started, nowEpochMillis)
            throw failure
        }
    }

    /**
     * Rewinds a live run to [taskId] in place (docs/Keepers-Room.md, Pause, change, resume): the
     * creature and everything downstream of it (and the approvals a repository change needs) start
     * again as a new attempt, while the rest of the swarm keeps running. Only what is in that set has
     * its sessions cancelled; what it spent stays on its record; its old approval gates are closed,
     * its old artifacts stay as history, and the work upstream of it is untouched.
     */
    suspend fun rewind(
        project: Project,
        definition: WorkflowDefinition,
        state: WorkflowRuntimeState,
        taskId: TaskDefinitionId,
        nowEpochMillis: Long,
    ): WorkflowRuntimeState {
        val (rewoundState, remote) = cycleMutex.withLock {
            val run = newestOf(state.run, persistence.runs.get(state.run.id))
            require(!run.status.isTerminal()) { "A finished run is run again from a task; it cannot be rewound" }
            require(definition.tasks.any { it.id == taskId }) { "Task ${taskId.value} is not part of this workflow" }
            val rewound = TaskRerunPlanner.rerunSet(definition, setOf(taskId))
                .filter { run.taskRuns[it]?.status != TaskRunStatus.Created }
                .toSet()
            // Stop what runs inside the set; the rest of the swarm is left as it is. A system step's remote
            // run (a workflow run, a deployment, a service call) is stopped as well: the new attempt starts
            // its own, and the old one must not carry on unowned.
            val stopped = state.handles.filterKeys { it in rewound }
            // A session that will not stop is recorded; it cannot be kept on the state, because the creature
            // starts over under the same id and would read the old session as its own. What it spent before
            // it stopped stays on the creature's record.
            val stoppedUsage = stopSessions(run, stopped, nowEpochMillis).usage
            val remote = unfinishedExternalRuns(definition, run).filter { it.task.id in rewound }
            persistence.approvalGates.unresolved(run.id)
                .filter { it.taskDefinitionId in rewound }
                .forEach { gate ->
                    persistence.approvalGates.put(
                        gate.copy(status = ApprovalGateStatus.Rejected, decisionNote = "Rewound", decidedAtEpochMillis = nowEpochMillis),
                    )
                }
            val fresh = WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = run.id,
                projectId = run.projectId,
                objective = run.objective,
                nowEpochMillis = nowEpochMillis,
                taskRunIdFactory = { id -> TaskRunId("${run.id.value}-${id.value}-r${(run.taskRuns[id]?.attempt ?: 0) + 1}") },
                repository = run.repositorySnapshot,
                roles = run.roleSnapshot,
            )
            val reset = rewound.mapNotNull { id ->
                val prior = run.taskRuns[id] ?: return@mapNotNull null
                fresh.taskRuns[id]?.let { f ->
                    id to f.copy(attempt = prior.attempt + 1, usage = stoppedUsage[id]?.let { prior.usage.plus(it) } ?: prior.usage)
                }
            }.toMap()
            var next = run.copy(
                taskRuns = run.taskRuns + reset,
                status = WorkflowRunStatus.Running,
                updatedAtEpochMillis = maxOf(nowEpochMillis, run.updatedAtEpochMillis + 1),
            )
            next = WorkflowRunFactory.refreshReadiness(definition, next, nowEpochMillis)
            val handles = state.handles - rewound
            val result = WorkflowRuntimeState(next, handles)
            persist(project, definition, result)
            result to remote
        }
        // Outside the cycle lock, as cancel() does: a slow remote must not hold the swarm's cycle.
        cancelExternalRuns(project, definition, rewoundState.run, remote, nowEpochMillis)
        return rewoundState
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
            val persistedRun = newestOf(state.run, persistence.runs.get(state.run.id))
            if (persistedRun.status.isTerminal()) {
                return@withLock stopRemainingSessions(WorkflowRuntimeState(persistedRun, state.handles), nowEpochMillis) to
                    emptyList<ExternalRunToCancel>()
            }
            val pending = unfinishedExternalRuns(definition, persistedRun)
            terminate(
                project,
                definition,
                engine.cancelWorkflow(persistedRun, decisionTime(nowEpochMillis, persistedRun)),
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
        /** The work is leased on the compute mesh, which cancels it, not an executor integration. */
        val onMesh: Boolean = false,
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
            val task = tasks[id] ?: return@mapNotNull null
            val executor = taskRun.executor ?: task.effectiveExecutor()
            // The compute mesh owns these handles, not an executor integration: it is asked to stop them.
            if (remoteComputeCoordinator?.owns(taskRun) == true) return@mapNotNull ExternalRunToCancel(task, taskRun, executor, onMesh = true)
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
            for ((task, taskRun, executor, onMesh) in remote) {
                val externalRunId = taskRun.externalRunId ?: continue
                val failure: Throwable? = try {
                    if (onMesh) {
                        remoteComputeCoordinator?.cancel(taskRun)?.let { refusal -> throw IllegalStateException(refusal) }
                        null
                    } else executorIntegrations.integrationFor(executor, project)?.cancel(
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
        val closedGates = closeFailureEscalationsForTerminalRun(run, nowEpochMillis)
        val stop = stopSessions(closedGates, handles, nowEpochMillis)
        val closed = closedGates.withStoppedUsage(stop.usage)
        val terminalState = WorkflowRuntimeState(closed, stop.askAgain)
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
        val costRose = run.knownCostUsd() > previous.knownCostUsd() ||
            (run.hasUnpricedUsage() && !previous.hasUnpricedUsage())
        if (!costRose && lastCheck != null && nowEpochMillis - lastCheck < HOURLY_SPEND_RECHECK_MILLIS) return null
        lastHourlySpendCheckAtMillis = nowEpochMillis
        val spent = hourlySpend(persistence, nowEpochMillis, alsoRunId = run.id, ambient = ambientSpend)
        return when {
            spent.knownUsd >= hourlyCap -> SpendCapTrip(SpendCapKind.HourlySpend, hourlyCap, spent.knownUsd)
            // Spend that could not be priced is not free: the cap stops what it cannot count, unless the keeper lifted that.
            spent.unpricedUsage && !run.unpricedCostAccepted ->
                SpendCapTrip(SpendCapKind.HourlySpend, hourlyCap, spent.knownUsd, unknownCost = true)
            else -> null
        }
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
        val stop = stopSessions(run, live, nowEpochMillis)
        val leftover = stop.askAgain
        val stopped = pauseRunForSpendCap(
            run.withStoppedUsage(stop.usage),
            trip,
            cancelledTaskIds = live.keys,
            nowEpochMillis = nowEpochMillis,
        )
        val stoppedState = WorkflowRuntimeState(stopped, leftover)
        persist(project, definition, stoppedState)
        return stoppedState
    }

    /**
     * Stops [handles]' sessions, best effort: a session that already finished is not an error. One the
     * provider rejects, or that throws, keeps billing, so it is recorded as an [ExternalRunCancelFailed]
     * and handed back to be asked again on a later cycle, up to [MAX_SESSION_CANCEL_ATTEMPTS] times, so
     * a provider that can never cancel does not keep a run cycling for ever.
     */
    private suspend fun cancelSessions(
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ): Map<TaskDefinitionId, ManagedSessionHandle> = stopSessions(run, handles, nowEpochMillis).askAgain

    /** What [stopSessions] left: sessions to ask again, and what each stopped one reported since it was last read. */
    private class StoppedSessions(
        val askAgain: Map<TaskDefinitionId, ManagedSessionHandle>,
        val usage: Map<TaskDefinitionId, ManagedSessionUsage>,
    )

    /**
     * [cancelSessions] that also reads what each stopped session had reported but the cycle had not yet
     * read (spend between the last poll and the stop is still spend), records it as usage events, and
     * lets the gateway forget the session. The caller adds [StoppedSessions.usage] to the run it keeps.
     */
    /** The last count of new reports each session made, and when it last changed (under [cycleMutex]). */
    private val progressMarks = mutableMapOf<TaskDefinitionId, Pair<Long, Long>>()

    /** When each session last made a new report, for the creatures whose session has made any at all. */
    private suspend fun progressSeen(
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        now: Long,
    ): Map<TaskDefinitionId, Long> {
        progressMarks.keys.retainAll(handles.keys)
        val seen = mutableMapOf<TaskDefinitionId, Long>()
        for ((taskId, handle) in handles) {
            val count = runCatching { sessionGateway.progressCount(handle) }.getOrNull()
            if (count == null || count <= 0L) continue
            val mark = progressMarks[taskId]
            val at = if (mark == null || mark.first != count) now else mark.second
            progressMarks[taskId] = count to at
            seen[taskId] = at
        }
        return seen
    }

    /**
     * Tells [deadlineSample] how long each attempt that ended this cycle actually worked: a step that finished
     * (the clock it had while working, read at [now]) and a step that was stopped at its stop point (the active
     * time the stop recorded). Steps still working, and steps with no clock for their attempt, add nothing.
     */
    private fun recordDeadlineSamples(
        definition: WorkflowDefinition,
        before: WorkflowRun,
        after: WorkflowRun,
        hardStopped: Set<TaskDefinitionId>,
        now: Long,
    ) {
        for ((id, taskRun) in after.taskRuns) {
            val task = definition.tasks.firstOrNull { it.id == id } ?: continue
            val millis = when {
                id in hardStopped ->
                    taskRun.clock?.takeIf { it.attempt == taskRun.attempt }?.activeMillisAtEnd
                taskRun.status == TaskRunStatus.Completed -> {
                    val prior = before.taskRuns[id]
                    if (prior != null && prior.status != TaskRunStatus.Completed) {
                        prior.clock?.takeIf { it.attempt == prior.attempt }?.let { ActiveClock.active(it, now) }
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (millis != null && millis > 0L) deadlineSample(task, taskRun, millis)
        }
    }

    /**
     * Stops what the creatures that took too long were doing: their provider sessions and executor runs. What a
     * stopped session spent is counted, so a spend cap still sees it.
     */
    private suspend fun stopForDeadlines(
        project: Project,
        definition: WorkflowDefinition,
        before: WorkflowRun,
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        stopped: Set<TaskDefinitionId>,
        nowEpochMillis: Long,
    ): WorkflowRun {
        cancelExternalRuns(
            project, definition, before,
            unfinishedExternalRuns(definition, before).filter { it.task.id in stopped },
            nowEpochMillis,
        )
        val stop = stopSessions(run, handles.filterKeys { it in stopped }, nowEpochMillis)
        return run.withStoppedUsage(stop.usage)
    }

    private suspend fun stopSessions(
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ): StoppedSessions {
        val (finished, live) = handles.entries.partition { (taskId, _) -> run.taskRuns[taskId]?.status == TaskRunStatus.Completed }
        if (handles.isEmpty()) return StoppedSessions(emptyMap(), emptyMap())
        val askAgain = mutableMapOf<TaskDefinitionId, ManagedSessionHandle>()
        val usage = mutableMapOf<TaskDefinitionId, ManagedSessionUsage>()
        withContext(NonCancellable) {
            // A completed task's session has nothing to stop: what it last reported is counted and it is let go.
            for ((taskId, handle) in finished) {
                drainUsage(run, taskId, handle, nowEpochMillis)?.let { usage[taskId] = it }
                runCatching { sessionGateway.release(handle) }
            }
            for ((taskId, handle) in live) {
                val reason = sessionGateway.cancelFailure(handle)
                if (reason == null) {
                    sessionCancelFailures.remove(handle.providerRunId)
                    drainUsage(run, taskId, handle, nowEpochMillis)?.let { usage[taskId] = it }
                    runCatching { sessionGateway.release(handle) }
                    continue
                }
                val failures = (sessionCancelFailures[handle.providerRunId] ?: 0) + 1
                sessionCancelFailures[handle.providerRunId] = failures
                try {
                    persistence.events.append(
                        ExternalRunCancelFailed(
                            workflowRunId = run.id,
                            taskDefinitionId = taskId,
                            externalRunId = handle.providerRunId.value,
                            reason = reason,
                            occurredAtEpochMillis = nowEpochMillis,
                        ),
                    )
                } catch (_: Throwable) {
                    // Recording the failure is itself best effort.
                }
                if (failures < MAX_SESSION_CANCEL_ATTEMPTS) askAgain[taskId] = handle else sessionCancelFailures.remove(handle.providerRunId)
            }
        }
        return StoppedSessions(askAgain, usage)
    }

    /** What [handle]'s session reported since the cycle last read it, as an event; null when it reported nothing. */
    private suspend fun drainUsage(
        run: WorkflowRun,
        taskId: TaskDefinitionId,
        handle: ManagedSessionHandle,
        nowEpochMillis: Long,
    ): ManagedSessionUsage? {
        val usage = try {
            sessionGateway.usageReport(handle)?.sanitized()
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            persistence.events.append(
                ProviderUsageRecorded(
                    workflowRunId = run.id,
                    taskDefinitionId = taskId,
                    providerId = handle.providerId,
                    providerRunId = handle.providerRunId,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    costUsd = usage.costUsd,
                    cacheHitFraction = usage.cacheHitFraction,
                    latencyMillis = usage.latencyMillis,
                    costUnknown = usage.costUnknown,
                    occurredAtEpochMillis = nowEpochMillis,
                ),
            )
        } catch (_: Throwable) {
            // The event is telemetry for the hourly cap; the run's own total below still counts it.
        }
        return usage
    }

    /** [run] with each task's stopped-session [usage] added to what that task has spent. */
    private fun WorkflowRun.withStoppedUsage(usage: Map<TaskDefinitionId, ManagedSessionUsage>): WorkflowRun {
        if (usage.isEmpty()) return this
        return copy(
            taskRuns = taskRuns.mapValues { (id, taskRun) ->
                val stopped = usage[id] ?: return@mapValues taskRun
                taskRun.copy(usage = taskRun.usage.plus(stopped))
            },
        )
    }

    /**
     * A run that is already finished or stopped but still holds sessions that would not stop: asks them to stop
     * again, counts what the stopped ones reported, and keeps the rest.
     */
    private suspend fun stopRemainingSessions(state: WorkflowRuntimeState, nowEpochMillis: Long): WorkflowRuntimeState {
        val stop = stopSessions(state.run, state.handles, nowEpochMillis)
        if (stop.usage.isEmpty()) return state.copy(handles = stop.askAgain)
        val counted = state.run.withStoppedUsage(stop.usage)
        persistence.runs.put(counted)
        return WorkflowRuntimeState(counted, stop.askAgain)
    }

    /**
     * Handles the run no longer follows ([dropped]: their task finished, failed or is being retried): each one's
     * last report is counted on the run and the gateway forgets the session. Nothing is stopped here.
     */
    private suspend fun settleDroppedHandles(
        run: WorkflowRun,
        dropped: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ): WorkflowRun {
        if (dropped.isEmpty()) return run
        val usage = mutableMapOf<TaskDefinitionId, ManagedSessionUsage>()
        withContext(NonCancellable) {
            for ((taskId, handle) in dropped) {
                drainUsage(run, taskId, handle, nowEpochMillis)?.let { usage[taskId] = it }
                runCatching { sessionGateway.release(handle) }
            }
        }
        return run.withStoppedUsage(usage)
    }

    /** Writes the artifacts the store does not have in this form yet; the rest of the run's are as they were. */
    private suspend fun persistArtifacts(run: WorkflowRun) {
        val current = run.taskRuns.values.flatMap(TaskRun::artifacts)
        for (artifact in current) {
            if (persistedArtifacts[artifact.id] == artifact) continue
            persistence.artifacts.put(artifact)
            persistedArtifacts[artifact.id] = artifact
        }
        // Only the run being cycled is remembered: another run's artifacts are written again if it comes back.
        val keep = current.mapTo(mutableSetOf()) { it.id }
        persistedArtifacts.keys.retainAll(keep)
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
                        val sessionless = !taskRun.isHeldAtEnginePlanGate() && taskRun.providerRunId == null
                        when {
                            !sessionless -> engine.approvePlanGate(nextRun, taskId, now)
                            // A person's own approval step has no session: approving its gate is approving it.
                            (taskRun.executor ?: tasks.getValue(taskId).effectiveExecutor()) is TaskExecutor.HumanApproval ->
                                engine.completeHumanApprovalTask(definition, nextRun, taskId, now)
                            // Nothing is running behind this gate for an approval to resume.
                            else -> nextRun
                        }
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
        undecidedFailures: Set<TaskDefinitionId> = emptySet(),
    ): WorkflowRun {
        val refreshed = engine.refreshReadiness(definition, run, now, undecidedFailures)
        // The run ends here when its last step was a system executor, so the end event is written here too:
        // the engine writes it only for a run it ends itself.
        return if (
            refreshed.taskRuns.isNotEmpty() &&
            refreshed.taskRuns.values.all { it.status == TaskRunStatus.Cancelled }
        ) {
            if (run.status != WorkflowRunStatus.Cancelled) eventSink.append(WorkflowCancelled(refreshed.id, now))
            refreshed.copy(status = WorkflowRunStatus.Cancelled, updatedAtEpochMillis = now)
        } else if (
            refreshed.taskRuns.isNotEmpty() &&
            refreshed.taskRuns.values.all {
                it.status == TaskRunStatus.Completed || it.status == TaskRunStatus.Cancelled
            }
        ) {
            if (run.status != WorkflowRunStatus.Completed) eventSink.append(WorkflowCompleted(refreshed.id, now))
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
                    // A nested workflow reports its child run's total: the parent's caps and budgets count it.
                    usage = execution.usage ?: previous.usage,
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
        const val REMOTE_COMPUTE_UNAVAILABLE: String = REMOTE_COMPUTE_UNAVAILABLE_CODE
        val TERMINAL_TASK_STATUSES: Set<TaskRunStatus> = setOf(
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
            TaskRunStatus.Cancelled,
        )
        const val HOURLY_SPEND_RECHECK_MILLIS: Long = 60_000L
        const val MAX_SESSION_CANCEL_ATTEMPTS: Int = 5

        /** A creature that took longer than allowed this many times goes to the keeper. */
        const val MAX_TIMEOUTS: Int = 2
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
