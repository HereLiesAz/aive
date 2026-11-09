package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
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
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.policy.PolicyDecision
import com.hereliesaz.geministrator.policy.PolicyRequest
import com.hereliesaz.geministrator.policy.recordPolicyDecision
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.providers.ProviderPlanDraft
import com.hereliesaz.geministrator.providers.SpentTokens
import com.hereliesaz.geministrator.providers.spentTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WorkflowEngine(
    private val sessionGateway: ManagedSessionGateway,
    roles: Collection<RoleDefinition>,
    private val eventSink: WorkflowEventSink = NoOpWorkflowEventSink,
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    private val surfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
) {
    private val rolesById: Map<RoleDefinitionId, RoleDefinition> = roles.associateBy { it.id }

    init {
        require(rolesById.size == roles.size) { "Role IDs must be unique" }
    }

    fun roleDefinition(roleId: RoleDefinitionId?): RoleDefinition? = roleId?.let(rolesById::get)

    fun roleDefinition(run: WorkflowRun, roleId: RoleDefinitionId?): RoleDefinition? {
        val id = roleId ?: return null
        return run.roleSnapshot.firstOrNull { it.id == id } ?: rolesById[id]
    }

    /**
     * [roleId]'s token budget for one run. The keeper's current role wins over the run's snapshot,
     * so raising (or clearing) a budget in the role editor applies to a run already under way.
     */
    fun roleTokenBudget(run: WorkflowRun, roleId: RoleDefinitionId): Long? {
        val current = rolesById[roleId]
        if (current != null) return current.tokenBudget
        return run.roleSnapshot.firstOrNull { it.id == roleId }?.tokenBudget
    }

    /** Whether [roleId] has spent its whole token budget in [run]. False when it has no budget. */
    fun roleBudgetSpent(run: WorkflowRun, roleId: RoleDefinitionId?): Boolean {
        val id = roleId ?: return false
        val budget = roleTokenBudget(run, id) ?: return false
        return run.roleTokensSpent(id) >= budget
    }

    /**
     * What [roleId]'s running tasks that have reported no usage yet are expected to spend: the estimate of
     * their input. Their usage lands only when their session reports it, and until then the role's budget
     * would not see them.
     */
    private fun roleTokensInFlight(definition: WorkflowDefinition, run: WorkflowRun, roleId: RoleDefinitionId): Long {
        val role = roleDefinition(run, roleId) ?: return 0L
        val tasks = definition.tasks.associateBy { it.id }
        return run.taskRuns.values
            .filter { it.status.isActive() && it.assignedRoleId == roleId && (it.usage?.totalTokens ?: 0L) == 0L }
            .sumOf { taskRun ->
                val task = tasks[taskRun.taskDefinitionId] ?: return@sumOf 0L
                estimateTaskInputTokens(definition, run, task, role).toLong()
            }
    }

    /**
     * Releases tasks parked for a spent role budget once that role has budget again (the keeper
     * raised or cleared it). A released task is Ready; one that was stopped mid-run was already
     * moved to a fresh attempt when it was parked.
     */
    fun releaseRoleBudgetHolds(run: WorkflowRun, nowEpochMillis: Long): WorkflowRun {
        if (run.taskRuns.values.none { it.isHeldForRoleBudget() }) return run
        var changed = false
        val released = run.taskRuns.mapValues { (_, taskRun) ->
            if (!taskRun.isHeldForRoleBudget() || roleBudgetSpent(run, taskRun.assignedRoleId)) {
                taskRun
            } else {
                changed = true
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Ready)
                taskRun.copy(status = TaskRunStatus.Ready, blockingReason = null, progressMessage = null)
            }
        }
        if (!changed) return run
        val status = if (run.status == WorkflowRunStatus.AwaitingHuman || run.status.isTerminal()) {
            run.status
        } else {
            WorkflowRunStatus.Running
        }
        return run.copy(status = status, taskRuns = released, updatedAtEpochMillis = nowEpochMillis)
    }

    /**
     * [WorkflowRunFactory.refreshReadiness] that records each non-allow policy decision it makes
     * (a repository mutation cancelled without a granted approval) on this engine's event sink.
     */
    suspend fun refreshReadiness(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        nowEpochMillis: Long,
        undecidedFailures: Set<TaskDefinitionId> = emptySet(),
    ): WorkflowRun {
        val decisions = mutableListOf<Pair<PolicyRequest, PolicyDecision>>()
        val refreshed = WorkflowRunFactory.refreshReadiness(
            definition,
            run,
            nowEpochMillis,
            undecidedFailures,
        ) { request, decision ->
            decisions += request to decision
        }
        decisions.forEach { (request, decision) -> eventSink.recordPolicyDecision(request, decision, nowEpochMillis) }
        return refreshed
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

        val refreshed = refreshReadiness(definition, run, nowEpochMillis)
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
            val selection: ProviderSelectionRequest,
        )

        val pendingAgentDispatches = mutableListOf<PendingAgentDispatch>()
        /** Tokens set aside for the budgeted roles' tasks chosen to start in this pass, by role ([roleTokensInFlight]). */
        val reservedThisBatch = mutableMapOf<RoleDefinitionId, Long>()

        for (taskRun in dispatchable) {
            if (remainingSlots == 0) break
            val task = definitionsById[taskRun.taskDefinitionId]
            if (task == null) {
                // A run saved before its hive was edited can hold a task run the hive no longer has.
                // Nothing can say what it should do or what waits on it, so the run fails with that
                // reason; throwing here would stop every other ready task from starting, every cycle.
                nextRun = failRunForMissingTask(nextRun, taskRun, nowEpochMillis)
                return DispatchResult(nextRun, handles)
            }
            val executor = taskRun.executor ?: task.effectiveExecutor()

            when (executor) {
                is TaskExecutor.RoleAgent -> {
                    val role = roleDefinition(nextRun, executor.roleId)
                    if (role == null || !role.enabled) {
                        // A missing or disabled role (a creature reassigned to one, a role switched off
                        // mid-run) fails this task through its escalation policy, without a retry that
                        // would find the same thing, and leaves the other ready tasks to start.
                        nextRun = failTaskBeforeStart(
                            definition = definition,
                            run = nextRun,
                            taskRun = taskRun,
                            task = task,
                            reason = if (role == null) {
                                "Role ${executor.roleId.value} is not registered for this run"
                            } else {
                                "Role ${role.name} is disabled"
                            },
                            nowEpochMillis = nowEpochMillis,
                            allowRetry = false,
                        )
                        if (nextRun.status.isTerminal()) return DispatchResult(nextRun, handles)
                        continue
                    }

                    // Token budget circuit breaker: a role that has spent its budget in this run
                    // starts no more work until the keeper raises the budget.
                    val budgetRoleId = taskRun.assignedRoleId ?: task.roleId ?: role.id
                    if (roleBudgetSpent(nextRun, budgetRoleId)) {
                        TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Blocked)
                        nextRun = nextRun.copy(
                            taskRuns = nextRun.taskRuns + (
                                task.id to taskRun.copy(
                                    status = TaskRunStatus.Blocked,
                                    assignedRoleId = budgetRoleId,
                                    blockingReason = roleBudgetReason(nextRun, budgetRoleId),
                                )
                            ),
                            updatedAtEpochMillis = nowEpochMillis,
                        )
                        continue
                    }

                    // The budget is checked against usage that has landed, and tasks of this role that are
                    // running have reported none yet: left to start together they would each be within the
                    // budget and together over it. What the running ones are expected to spend (their input,
                    // which is the least they will bill) is set aside, so a task that would take the role over
                    // waits Ready until their usage lands. The first task of a role always starts.
                    val ownEstimate = estimateTaskInputTokens(definition, nextRun, task, role)
                    val roleBudget = roleTokenBudget(nextRun, budgetRoleId)
                    if (roleBudget != null) {
                        val reserved = roleTokensInFlight(definition, nextRun, budgetRoleId) +
                            (reservedThisBatch[budgetRoleId] ?: 0L)
                        if (reserved > 0L && nextRun.roleTokensSpent(budgetRoleId) + reserved + ownEstimate > roleBudget) continue
                        reservedThisBatch[budgetRoleId] = (reservedThisBatch[budgetRoleId] ?: 0L) + ownEstimate
                    }

                    val selection = ProviderSelectionRequest(
                        preferredProviderId = role.preferredProviderId,
                        requiredCapabilities = role.capabilitiesRequired,
                        constraints = task.providerConstraints,
                        repository = project.repository,
                        estimatedInputTokens = ownEstimate,
                        excludedProviderIds = taskRun.failedProviders.toSet(),
                        failoverAllowed = isProviderAuto(role, task),
                    )
                    val providerId = sessionGateway.resolveProvider(selection)
                    val providerLimit = definition.concurrencyPolicy.perProviderLimits[providerId] ?: Int.MAX_VALUE
                    val providerActive = activeByProvider[providerId] ?: 0
                    if (providerActive >= providerLimit) continue

                    val resolvedSurfaces = surfaceRuntime.resolve(role.surfaces)
                    val builtRequest = buildProviderTaskRequest(
                        project = project,
                        definition = definition,
                        run = nextRun,
                        task = task,
                        taskRun = taskRun,
                        role = role,
                        orchestrationUtilities = orchestrationUtilities,
                        resolvedSurfaces = resolvedSurfaces,
                    )
                    val approvedPlan = taskRun.approvedEnginePlan()
                    val request = if (approvedPlan != null) {
                        // The engine already held this task at its gate; the run executes the approved plan.
                        builtRequest.copy(
                            requirePlanApproval = false,
                            promptContext = builtRequest.promptContext.copy(
                                dynamicContext = builtRequest.promptContext.dynamicContext +
                                    PromptContextBlock(APPROVED_PLAN_CONTEXT_LABEL, approvedPlan),
                            ),
                        )
                    } else {
                        builtRequest
                    }
                    if (request.requirePlanApproval) {
                        val draft = try {
                            sessionGateway.draftPlanWithUsage(providerId, request)
                        } catch (failure: ManagedSessionFailure) {
                            // A provider refusal while drafting (HTTP 429, bad key) fails the task with
                            // the provider's reason and goes through the task's retry policy. A draft that
                            // was cut off was still billed: what it spent counts toward the task's budget.
                            nextRun = failTaskBeforeStart(
                                definition = definition,
                                run = nextRun,
                                taskRun = chargePlanDraft(nextRun, taskRun, task, providerId, failure.spentTokens()?.toUsage(), nowEpochMillis),
                                task = task,
                                reason = failure.message?.takeIf(String::isNotBlank) ?: "Unable to draft a plan",
                                nowEpochMillis = nowEpochMillis,
                                providerId = providerId,
                                selection = selection,
                            )
                            if (nextRun.status.isTerminal()) {
                                // The task's policy failed the workflow: start nothing else, and keep
                                // the terminal status rather than overwriting it below.
                                return DispatchResult(nextRun, handles)
                            }
                            continue
                        }
                        val drafted = draft?.text
                        if (drafted != null) {
                            // Engine-owned gate: hold the task with the drafted plan; no run exists yet.
                            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.AwaitingApproval)
                            humanApprovalPending = true
                            val charged = chargePlanDraft(nextRun, taskRun, task, providerId, draft.toUsage(), nowEpochMillis)
                            nextRun = nextRun.copy(
                                taskRuns = nextRun.taskRuns + (
                                    task.id to charged.copy(
                                        status = TaskRunStatus.AwaitingApproval,
                                        assignedRoleId = task.roleId ?: role.id,
                                        executor = executor,
                                        assignedProviderId = providerId,
                                        providerRunId = null,
                                        externalRunId = null,
                                        blockingReason = null,
                                        progress = null,
                                        progressMessage = drafted,
                                        providerPlan = drafted,
                                    )
                                ),
                                updatedAtEpochMillis = nowEpochMillis,
                            )
                            eventSink.append(HumanDecisionRequired(nextRun.id, task.id, "Plan approval", nowEpochMillis))
                            continue
                        }
                    }
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
                        selection = selection,
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
                is TaskExecutor.Script,
                is TaskExecutor.TestRunner,
                is TaskExecutor.Deployment,
                is TaskExecutor.RepositoryOperation,
                is TaskExecutor.ExternalService,
                is TaskExecutor.NestedWorkflow,
                is TaskExecutor.Distributed,
                -> continue
            }
        }

        // Phase 2: create sessions concurrently. This batch is transactional with respect to
        // provider lifetime: Phase 3 is the only place handles become workflow state, so any
        // session created before a sibling fails must be cancelled before the failure escapes.
        // A provider's refusal to start one task (ManagedSessionFailure: credit, key, rate limit, an
        // unavailable provider) is that task's failure, not the batch's: it is kept aside and the task
        // goes through its failure policy in Phase 3, while the sessions that did start stay.
        val createdHandles = mutableListOf<Pair<PendingAgentDispatch, ManagedSessionHandle>>()
        val refusedStarts = mutableListOf<Pair<PendingAgentDispatch, ManagedSessionFailure>>()
        val createdHandlesMutex = Mutex()
        try {
            coroutineScope {
                pendingAgentDispatches.map { pending ->
                    async {
                        val handle = try {
                            sessionGateway.createSession(pending.sessionRequest)
                        } catch (refusal: ManagedSessionFailure) {
                            withContext(NonCancellable) {
                                createdHandlesMutex.withLock { refusedStarts += pending to refusal }
                            }
                            return@async
                        }
                        withContext(NonCancellable) {
                            createdHandlesMutex.withLock {
                                createdHandles += pending to handle
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        } catch (failure: Throwable) {
            val rollbackHandles = withContext(NonCancellable) {
                createdHandlesMutex.withLock { createdHandles.map { it.second } }
            }
            cancelSessions(rollbackHandles, failure)
            throw failure
        }

        // Phase 3: apply state mutations sequentially. The sessions exist by now, so a failure here
        // (an illegal transition, the event sink) cancels them all before it escapes: nothing else
        // knows about them yet.
        try {
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
                            // A new session has asked nothing yet.
                            keeperQuestion = null,
                            // An engine-approved plan stays visible on the task it is being executed for.
                            providerPlan = pending.taskRun.approvedEnginePlan(),
                        )
                    ),
                    updatedAtEpochMillis = nowEpochMillis,
                )
                eventSink.append(AgentAssigned(nextRun.id, pending.task.id, pending.role.id, nowEpochMillis))
                eventSink.append(ExecutorAssigned(nextRun.id, pending.task.id, pending.executor, pending.task.roleId, nowEpochMillis))
                eventSink.append(TaskStarted(nextRun.id, pending.task.id, pending.taskRun.attempt, nowEpochMillis))
            }
            // A refused start fails its own task now that the started ones are committed.
            for ((pending, refusal) in refusedStarts) {
                nextRun = failTaskBeforeStart(
                    definition = definition,
                    run = nextRun,
                    taskRun = pending.taskRun,
                    task = pending.task,
                    reason = refusal.message?.takeIf(String::isNotBlank) ?: "Unable to start provider session",
                    nowEpochMillis = nowEpochMillis,
                    providerId = pending.providerId,
                    selection = pending.selection,
                )
                // The task's policy failed the workflow: keep the terminal status, and the sessions
                // that did start are handed back for the caller to cancel with the run.
                if (nextRun.status.isTerminal()) return DispatchResult(nextRun, handles)
            }
        } catch (failure: Throwable) {
            cancelSessions(createdHandles.map { it.second }, failure)
            throw failure
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

    /**
     * [taskRun] with what drafting its plan spent ([usage]) added, and that spend recorded as a usage event.
     * Drafting a plan is a billed generation like any other: the task's role budget and the keeper's spend
     * caps count it. Nothing changes when the draft reported no usage.
     */
    private suspend fun chargePlanDraft(
        run: WorkflowRun,
        taskRun: TaskRun,
        task: TaskDefinition,
        providerId: AgentProviderId,
        usage: ManagedSessionUsage?,
        nowEpochMillis: Long,
    ): TaskRun {
        val clean = usage?.sanitized() ?: return taskRun
        val total = taskRun.usage.plus(clean) ?: return taskRun
        runCatching {
            eventSink.append(
                ProviderUsageRecorded(
                    workflowRunId = run.id,
                    taskDefinitionId = task.id,
                    providerId = providerId,
                    providerRunId = ProviderRunId("${providerId.value}/plan-draft/${taskRun.id.value}"),
                    inputTokens = clean.inputTokens,
                    outputTokens = clean.outputTokens,
                    costUsd = clean.costUsd,
                    cacheHitFraction = clean.cacheHitFraction,
                    costUnknown = clean.costUnknown,
                    occurredAtEpochMillis = nowEpochMillis,
                ),
            )
        }
        return taskRun.copy(usage = total)
    }

    private fun ProviderPlanDraft?.toUsage(): ManagedSessionUsage? = this?.let {
        if (it.inputTokens == null && it.outputTokens == null && it.costUsd == null && !it.costUnknown) null else ManagedSessionUsage(
            inputTokens = it.inputTokens,
            outputTokens = it.outputTokens,
            costUsd = it.costUsd,
            cacheHitFraction = it.cacheHitFraction,
            costUnknown = it.costUnknown,
        )
    }

    private fun SpentTokens.toUsage(): ManagedSessionUsage = ManagedSessionUsage(
        inputTokens = spentInputTokens,
        outputTokens = spentOutputTokens,
        costUsd = spentCostUsd,
        // A failed attempt was billed: unless the provider priced it, its cost is unknown, not free.
        costUnknown = spentCostUsd == null,
    )

    /**
     * Fails [task] before any session exists for it (a provider refused to draft its plan or to
     * start it, its role is missing or disabled) and applies its failure policy. A refusal that is the
     * provider's own may move the task to another provider first ([failOver], when [providerId] and
     * [selection] are given); [allowRetry] is false for a cause a retry would find again.
     */
    private suspend fun failTaskBeforeStart(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskRun: TaskRun,
        task: TaskDefinition,
        reason: String,
        nowEpochMillis: Long,
        providerId: AgentProviderId? = null,
        selection: ProviderSelectionRequest? = null,
        allowRetry: Boolean = true,
    ): WorkflowRun {
        TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Failed)
        eventSink.append(TaskFailed(run.id, task.id, reason, nowEpochMillis))
        val failedRun = run.copy(
            taskRuns = run.taskRuns + (
                task.id to taskRun.copy(status = TaskRunStatus.Failed, progressMessage = reason)
            ),
        )
        var stopReason = reason
        if (providerId != null && selection != null) {
            // The provider's own failure moves the work to another provider (next cycle).
            val failover = failOver(failedRun, task.id, providerId, reason, selection, nowEpochMillis)
            failover.restarted?.let { return it }
            failover.stopMessage?.let { stopReason = it }
        }
        return handleFailure(
            definition = definition,
            run = failedRun,
            taskDefinitionId = task.id,
            retryReason = RetryReason.ProviderFailure,
            reason = "Executor failed. Last status: $stopReason",
            nowEpochMillis = nowEpochMillis,
            allowRetry = allowRetry,
        )
    }

    /** Fails [run] because [taskRun] belongs to a task the workflow definition no longer has. */
    private suspend fun failRunForMissingTask(
        run: WorkflowRun,
        taskRun: TaskRun,
        nowEpochMillis: Long,
    ): WorkflowRun {
        val reason = "Task ${taskRun.taskDefinitionId.value} is no longer part of this workflow"
        TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Failed)
        eventSink.append(TaskFailed(run.id, taskRun.taskDefinitionId, reason, nowEpochMillis))
        eventSink.append(WorkflowFailed(run.id, reason, nowEpochMillis))
        return run.copy(
            status = WorkflowRunStatus.Failed,
            taskRuns = run.taskRuns + (
                taskRun.taskDefinitionId to taskRun.copy(status = TaskRunStatus.Failed, progressMessage = reason)
            ),
            updatedAtEpochMillis = nowEpochMillis,
        )
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
        nextRun = refreshReadiness(definition, nextRun, nowEpochMillis)
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
            // Usage is best effort, but cancellation of the cycle must still propagate.
            var usageTotal = taskRun.usage
            try {
                sessionGateway.usageReport(handle)?.sanitized()?.let { usage ->
                    usageTotal = usageTotal.plus(usage)
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
                            costUnknown = usage.costUnknown,
                            occurredAtEpochMillis = nowEpochMillis,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
            val receivedArtifacts = sessionGateway.artifacts(handle).mapIndexed { index, artifact ->
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
            // Labelled with where they came from (ContextTaint.kt); the provider cannot claim more trust.
            val producingTask = definition.tasks.firstOrNull { it.id == taskId }
            val durableArtifacts = if (producingTask == null) {
                receivedArtifacts
            } else {
                definition.labelTaskOutputs(
                    run = nextRun,
                    task = producingTask,
                    assigned = outputProvenanceOnProvider(
                        (taskRun.executor ?: producingTask.effectiveExecutor())
                            .outputProvenance(roleDefinition(nextRun, taskRun.assignedRoleId ?: producingTask.roleId)),
                        handle.providerId,
                    ),
                    artifacts = receivedArtifacts,
                )
            }
            // A gate (a task that requires a Verification artifact) whose artifact turns the work back
            // fails, so what depends on it stays blocked (ArtifactVerdict.kt). No stated verdict, no change.
            val gateVerdict = if (
                status == ManagedSessionStatus.Completed &&
                producingTask != null &&
                ArtifactKind.Verification in producingTask.requiredArtifacts
            ) {
                ArtifactVerdicts.blocking(durableArtifacts)
            } else {
                null
            }
            val mappedStatus = if (gateVerdict != null) {
                TaskRunStatus.Failed
            } else {
                when (status) {
                    ManagedSessionStatus.Planning -> TaskRunStatus.Planning
                    ManagedSessionStatus.AwaitingApproval -> TaskRunStatus.AwaitingApproval
                    ManagedSessionStatus.Running -> TaskRunStatus.Running
                    ManagedSessionStatus.Completed -> TaskRunStatus.Completed
                    ManagedSessionStatus.Failed -> TaskRunStatus.Failed
                    ManagedSessionStatus.Unknown -> taskRun.status
                }
            }
            val safeStatus = if (
                mappedStatus == taskRun.status || TaskRunTransitions.canTransition(taskRun.status, mappedStatus)
            ) {
                mappedStatus
            } else {
                taskRun.status
            }
            // The agent's question to the keeper (docs/Agent-UI.md). A gateway that does not know
            // keeps what the task holds; an answered question stays answered even when replayed.
            val questionState = try {
                sessionGateway.keeperQuestion(handle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            val answeredQuestion = questionState?.answeredKey ?: taskRun.answeredKeeperQuestion
            val keeperQuestion = (if (questionState == null) taskRun.keeperQuestion else questionState.pending)
                ?.takeUnless { AgentUi.questionKey(it) == answeredQuestion }
                ?.takeUnless { safeStatus.isFinishedForQuestions() }
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
                        blockingReason = if (gateVerdict != null) {
                            BlockingReason(
                                code = GATE_VERDICT_BLOCKED,
                                message = "The gate turned this work back (${gateVerdict.name.lowercase()}).",
                            )
                        } else {
                            taskRun.blockingReason
                        },
                        usage = usageTotal,
                        keeperQuestion = keeperQuestion,
                        answeredKeeperQuestion = answeredQuestion,
                        providerPlan = if (
                            safeStatus == TaskRunStatus.AwaitingApproval &&
                            !providerProgress?.message.isNullOrBlank()
                        ) {
                            providerProgress?.message
                        } else {
                            taskRun.providerPlan
                        },
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }

        nextRun = stopRolesOverBudget(nextRun, handles, nowEpochMillis)
        // A task that failed in this pass has not met its failure policy yet (the caller applies it
        // next): what is conditioned on its failure waits for that decision.
        val failedThisPass = nextRun.taskRuns
            .filter { (id, taskRun) -> taskRun.status == TaskRunStatus.Failed && run.taskRuns[id]?.status != TaskRunStatus.Failed }
            .keys
        nextRun = refreshReadiness(definition, nextRun, nowEpochMillis, failedThisPass)
        val workflowStatus = deriveWorkflowStatus(nextRun)
        if (workflowStatus == WorkflowRunStatus.Completed && run.status != WorkflowRunStatus.Completed) {
            eventSink.append(WorkflowCompleted(nextRun.id, nowEpochMillis))
        }
        // A run whose every task ended Cancelled (a repository change that needed an approval nobody granted,
        // branches whose condition was not met) is cancelled, and says so in its log like a stopped one.
        if (workflowStatus == WorkflowRunStatus.Cancelled && run.status != WorkflowRunStatus.Cancelled) {
            eventSink.append(WorkflowCancelled(nextRun.id, nowEpochMillis))
        }
        return nextRun.copy(status = workflowStatus, updatedAtEpochMillis = nowEpochMillis)
    }

    /** True when the keeper left the provider to the app: no role preference and no provider required by id. */
    fun isProviderAuto(role: RoleDefinition, task: TaskDefinition): Boolean =
        com.hereliesaz.geministrator.isProviderAuto(role, task)

    /** The outcome of [failOver]: a [restarted] run, a [stopMessage] to fail with, or neither (not a failover case). */
    data class FailoverResult(val restarted: WorkflowRun? = null, val stopMessage: String? = null)

    /**
     * A task whose provider failed for a reason that is the provider's own (credit, key, rate limit,
     * offline, timeout, 5xx) is restarted from the start on another connected provider, without using
     * up its retry budget; each provider is tried once. A pinned creature, or one with no provider
     * left, is not restarted: [FailoverResult.stopMessage] says who failed and what to do.
     * It changes only which provider answers; approval gates are untouched.
     */
    suspend fun failOver(
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        failedProviderId: AgentProviderId?,
        reason: String?,
        selection: ProviderSelectionRequest,
        nowEpochMillis: Long,
    ): FailoverResult {
        failedProviderId ?: return FailoverResult()
        val taskRun = run.taskRuns[taskDefinitionId] ?: return FailoverResult()
        return when (val advice = sessionGateway.adviseFailover(failedProviderId, reason, selection)) {
            FailoverAdvice.NotApplicable -> FailoverResult()
            is FailoverAdvice.Stop -> FailoverResult(stopMessage = advice.message)
            is FailoverAdvice.Restart -> {
                TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Retrying)
                val attempt = taskRun.attempt + 1
                eventSink.append(RetryScheduled(run.id, taskDefinitionId, attempt, advice.note, nowEpochMillis))
                FailoverResult(
                    restarted = run.copy(
                        status = WorkflowRunStatus.Running,
                        taskRuns = run.taskRuns + (
                            taskDefinitionId to taskRun.copy(
                                status = TaskRunStatus.Retrying,
                                attempt = attempt,
                                failedProviders = (taskRun.failedProviders + failedProviderId).distinct(),
                                assignedProviderId = null,
                                providerRunId = null,
                                externalRunId = null,
                                artifacts = emptyList(),
                                blockingReason = null,
                                progress = null,
                                progressMessage = advice.note,
                                providerPlan = null,
                                keeperQuestion = null,
                            )
                        ),
                        updatedAtEpochMillis = nowEpochMillis,
                    ),
                )
            }
        }
    }

    /** The selection [taskDefinitionId] was or would be dispatched with, for failover decisions. */
    fun selectionFor(
        project: Project,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
    ): ProviderSelectionRequest? {
        val task = definition.tasks.firstOrNull { it.id == taskDefinitionId } ?: return null
        val taskRun = run.taskRuns[taskDefinitionId] ?: return null
        val executor = taskRun.executor ?: task.effectiveExecutor()
        val roleId = (executor as? TaskExecutor.RoleAgent)?.roleId ?: return null
        val role = roleDefinition(run, roleId) ?: return null
        return ProviderSelectionRequest(
            preferredProviderId = role.preferredProviderId,
            requiredCapabilities = role.capabilitiesRequired,
            constraints = task.providerConstraints,
            repository = project.repository,
            excludedProviderIds = taskRun.failedProviders.toSet(),
            failoverAllowed = isProviderAuto(role, task),
        )
    }

    suspend fun handleFailure(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        retryReason: RetryReason,
        reason: String,
        nowEpochMillis: Long,
        allowRetry: Boolean = true,
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
            retryPolicy = if (allowRetry) task.retryPolicy else task.retryPolicy.copy(retryOn = emptySet()),
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
                            providerPlan = null,
                            keeperQuestion = null,
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
                            // A new attempt: counted against the policy, with fresh attempt-keyed
                            // gate ids and none of the failed attempt's output or plan.
                            attempt = taskRun.attempt + 1,
                            assignedRoleId = decision.roleId,
                            executor = TaskExecutor.RoleAgent(decision.roleId),
                            assignedProviderId = null,
                            providerRunId = null,
                            externalRunId = null,
                            artifacts = emptyList(),
                            blockingReason = null,
                            progress = null,
                            progressMessage = null,
                            providerPlan = null,
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
        if (taskRun.isHeldAtEnginePlanGate()) {
            // No run exists yet: the approved plan makes the task dispatchable.
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Ready)
            return run.copy(
                taskRuns = run.taskRuns + (
                    taskDefinitionId to taskRun.copy(status = TaskRunStatus.Ready, blockingReason = null, progressMessage = null)
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
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
                    providerPlan = null,
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
        val cancelled = cancelledRunView(run, nowEpochMillis)
        run.taskRuns.forEach { (taskId, taskRun) ->
            if (cancelled.taskRuns.getValue(taskId) !== taskRun) eventSink.append(TaskCancelled(run.id, taskId, nowEpochMillis))
        }
        eventSink.append(WorkflowCancelled(run.id, nowEpochMillis))
        return cancelled
    }

    /**
     * Token budget circuit breaker for work under way: the provider session of a task whose role has
     * spent its budget in this run is cancelled, and the task parked ([ROLE_BUDGET_SPENT]) as a fresh
     * attempt, so raising the budget dispatches it again ([releaseRoleBudgetHolds]).
     */
    private suspend fun stopRolesOverBudget(
        run: WorkflowRun,
        handles: Map<TaskDefinitionId, ManagedSessionHandle>,
        nowEpochMillis: Long,
    ): WorkflowRun {
        var nextRun = run
        for ((taskId, handle) in handles) {
            val taskRun = nextRun.taskRuns[taskId] ?: continue
            if (taskRun.status !in BUDGET_STOPPABLE) continue
            val roleId = taskRun.assignedRoleId ?: continue
            if (!roleBudgetSpent(nextRun, roleId)) continue
            // The task is parked either way so nothing more is sent, but a session the provider would not
            // stop keeps billing: that is recorded, not lost.
            var stoppedUsage: ManagedSessionUsage? = null
            withContext(NonCancellable) {
                val refusal = sessionGateway.cancelFailure(handle)
                if (refusal != null) {
                    runCatching {
                        eventSink.append(
                            ExternalRunCancelFailed(
                                workflowRunId = run.id,
                                taskDefinitionId = taskId,
                                externalRunId = handle.providerRunId.value,
                                reason = refusal,
                                occurredAtEpochMillis = nowEpochMillis,
                            ),
                        )
                    }
                } else {
                    // What it spent between the last poll and the stop is still spent: it counts toward the budget.
                    stoppedUsage = runCatching { sessionGateway.usageReport(handle)?.sanitized() }.getOrNull()
                    stoppedUsage?.let { usage ->
                        runCatching {
                            eventSink.append(
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
                        }
                    }
                }
            }
            TaskRunTransitions.requireAllowed(taskRun.status, TaskRunStatus.Blocked)
            nextRun = nextRun.copy(
                taskRuns = nextRun.taskRuns + (
                    taskId to taskRun.copy(
                        usage = stoppedUsage?.let { taskRun.usage.plus(it) } ?: taskRun.usage,
                        status = TaskRunStatus.Blocked,
                        attempt = taskRun.attempt + 1,
                        heldAttempts = taskRun.heldAttempts + 1,
                        assignedProviderId = null,
                        providerRunId = null,
                        externalRunId = null,
                        blockingReason = roleBudgetReason(nextRun, roleId),
                        progress = null,
                        progressMessage = null,
                        providerPlan = null,
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }
        return nextRun
    }

    private fun roleBudgetReason(run: WorkflowRun, roleId: RoleDefinitionId): BlockingReason {
        val name = roleDefinition(run, roleId)?.name ?: roleId.value
        val budget = roleTokenBudget(run, roleId)
        return BlockingReason(
            code = ROLE_BUDGET_SPENT,
            message = "Budget spent: $name used ${run.roleTokensSpent(roleId)} of its $budget tokens in this run. " +
                "Raise its budget to let it work again.",
        )
    }

    private fun deriveWorkflowStatus(run: WorkflowRun): WorkflowRunStatus {
        if (run.status.isTerminal()) return run.status
        val statuses = run.taskRuns.values.map { it.status }
        return when {
            statuses.isNotEmpty() && statuses.all { it == TaskRunStatus.Cancelled } -> WorkflowRunStatus.Cancelled
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

    private companion object {
        val BUDGET_STOPPABLE = setOf(TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying)
    }
    /** A task in one of these states has no agent left to answer: its question is dropped. */
    private fun TaskRunStatus.isFinishedForQuestions() = this in setOf(
        TaskRunStatus.Completed,
        TaskRunStatus.Failed,
        TaskRunStatus.Cancelled,
    )

    private fun TaskRunStatus.isActive() = this in setOf(
        TaskRunStatus.Planning,
        TaskRunStatus.Running,
        TaskRunStatus.Verifying,
    )

    /**
     * Cancels [handles] after [failure]. A cancel that throws or comes back rejected cannot be
     * retried here, so it is attached to [failure] as a suppressed error instead of being lost.
     */
    private suspend fun cancelSessions(handles: List<ManagedSessionHandle>, failure: Throwable) {
        withContext(NonCancellable) {
            handles.forEach { handle ->
                val outcome = runCatching { sessionGateway.cancel(handle) }
                val problem = outcome.exceptionOrNull()
                    ?: (outcome.getOrNull() as? ProviderActionResult.Rejected)?.let {
                        IllegalStateException("Could not cancel session ${handle.providerRunId}: ${it.reason}")
                    }
                if (problem != null && problem !== failure) failure.addSuppressed(problem)
            }
        }
    }
}

/**
 * What [run] is once cancelled: every task that has not completed or failed is Cancelled. Pure;
 * [WorkflowEngine.cancelWorkflow] adds the journal entries. A caller that must know whether a
 * cancelled run could still be used (a rerun from it) asks this before it stops anything.
 */
internal fun cancelledRunView(run: WorkflowRun, nowEpochMillis: Long): WorkflowRun {
    if (run.status.isTerminal()) return run
    return run.copy(
        status = WorkflowRunStatus.Cancelled,
        taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
            if (taskRun.status == TaskRunStatus.Completed || taskRun.status == TaskRunStatus.Failed) {
                taskRun
            } else {
                taskRun.copy(status = TaskRunStatus.Cancelled)
            }
        },
        updatedAtEpochMillis = nowEpochMillis,
    )
}
