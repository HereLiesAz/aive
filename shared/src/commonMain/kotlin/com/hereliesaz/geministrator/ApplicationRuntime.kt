package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowKeeperPause
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.defaultRoleCollectionEntries
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.normalized
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.domain.roleCollectionEntries
import com.hereliesaz.geministrator.domain.requireStarterRoleCoverage
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.withRoleComputeDelegation
import com.hereliesaz.geministrator.distributed.withTaskComputeDelegation
import com.hereliesaz.geministrator.distributed.withWorkflowComputeDelegation
import com.hereliesaz.geministrator.persistence.PersistenceCorruptionException
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.DurableWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.duplex.DuplexSessionAttachment
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.DelegationEligibility
import com.hereliesaz.geministrator.workflow.DelegationKey
import com.hereliesaz.geministrator.workflow.DelegationPolicy
import com.hereliesaz.geministrator.workflow.GateDelegationContext
import com.hereliesaz.geministrator.workflow.ProgressiveDelegationStore
import com.hereliesaz.geministrator.workflow.ManagedSessionFailure
import com.hereliesaz.geministrator.workflow.ManagedSessionGateway
import com.hereliesaz.geministrator.workflow.NestedWorkflowExecutorIntegration
import com.hereliesaz.geministrator.workflow.ProviderBackedManagedSessionGateway
import com.hereliesaz.geministrator.workflow.RoleSurfaceRuntimeRegistry
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.liftSpendPause
import com.hereliesaz.geministrator.workflow.StarterWorkflowFactory
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.TaskRerunPlanner
import com.hereliesaz.geministrator.workflow.TaskRerunRequest
import com.hereliesaz.geministrator.workflow.WorkflowApprovalService
import com.hereliesaz.geministrator.workflow.isHeldAtEnginePlanGate
import com.hereliesaz.geministrator.workflow.contextTaintGateId
import com.hereliesaz.geministrator.workflow.isHeldAtContextTaintGate
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowEngine
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeCoordinator
import com.hereliesaz.geministrator.workflow.WorkflowRunNestedWorkflowClient
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeState
import com.hereliesaz.geministrator.workflow.awaitsNestedHumanApproval
import com.hereliesaz.geministrator.workflow.humanReadable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface ApplicationRuntimeState {
    data object Loading : ApplicationRuntimeState
    data class NoProject(val roles: List<RoleDefinition> = emptyList()) : ApplicationRuntimeState
    data class NoRun(val project: Project, val roles: List<RoleDefinition> = emptyList()) : ApplicationRuntimeState
    data class Live(val presentation: LiveWorkflowPresentation) : ApplicationRuntimeState
    data class Disconnected(val message: String) : ApplicationRuntimeState
    data class ResumeFailed(val message: String, val isCorrupted: Boolean = false) : ApplicationRuntimeState
}

sealed class ApplicationRuntimeFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Disconnected(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
    class Resume(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
    class Corrupted(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
}

class WorkflowRuntimePublisher {
    private val mutableState = MutableStateFlow<ApplicationRuntimeState>(ApplicationRuntimeState.Loading)
    val state: StateFlow<ApplicationRuntimeState> = mutableState.asStateFlow()

    fun publish(state: ApplicationRuntimeState) {
        mutableState.value = state
    }
}

class ApplicationRuntime private constructor(
    val persistence: WorkflowPersistence,
    val providerRegistry: AgentProviderRegistry,
    val sessionGateway: ManagedSessionGateway,
    val engine: WorkflowEngine,
    val coordinator: WorkflowRuntimeCoordinator,
    val publisher: WorkflowRuntimePublisher,
    private val runtimeScope: CoroutineScope,
    private val roles: List<RoleDefinition>,
    private val nestedWorkflows: WorkflowRunNestedWorkflowClient? = null,
    /** The keeper's approval habits and delegations; null turns progressive delegation off. */
    val delegations: ProgressiveDelegationStore? = null,
) {
    private data class Current(
        val project: Project,
        val definition: WorkflowDefinition,
        val state: WorkflowRuntimeState,
    )

    private data class ViewingRun(
        val project: Project,
        val definition: WorkflowDefinition,
        val run: WorkflowRun,
        /** The run's hive was deleted: [definition] is a read-only stand-in and is never saved. */
        val deleted: Boolean = false,
    )

    private var current: Current? = null
    private var currentGeneration: Long = 0L
    private var cycleJob: Job? = null
    private val runtimeMutex = Mutex()
    /** Non-null while the UI is viewing a historical run; cycling continues on [current]. */
    private var viewingRun: ViewingRun? = null
    val state: StateFlow<ApplicationRuntimeState> = publisher.state

    suspend fun loadLatest() {
        runtimeMutex.withLock {
            publisher.publish(ApplicationRuntimeState.Loading)
            try {
                val projects = persistence.projects.all()
                if (projects.isEmpty()) {
                    replaceCurrent(null)
                    publisher.publish(ApplicationRuntimeState.NoProject(roles))
                    return@withLock
                }

                // A run whose hive was deleted is history only: it is never resumed, so startup skips it.
                val knownHives = persistence.definitions.all().mapTo(mutableSetOf()) { it.id }
                val latestProjectRun = projects
                    .flatMap { project ->
                        persistence.runs.byProject(project.id)
                            .filter { it.workflowDefinitionId in knownHives }
                            .map { run -> project to run }
                    }
                    .maxByOrNull { (_, run) -> run.updatedAtEpochMillis }

                if (latestProjectRun == null) {
                    replaceCurrent(null)
                    publisher.publish(
                        ApplicationRuntimeState.NoRun(
                            project = projects.maxByOrNull(Project::updatedAtEpochMillis) ?: projects.first(),
                            roles = roles,
                        ),
                    )
                    return@withLock
                }

                val (project, run) = latestProjectRun
                val storedDefinition = persistence.definitions.get(run.workflowDefinitionId)
                    ?: error("Workflow definition ${run.workflowDefinitionId.value} was not found")
                val definition = repairKnownNodeCreatureRigCycle(storedDefinition)
                if (definition !== storedDefinition) {
                    persistence.definitions.put(definition)
                }
                val runtimeState = try {
                    coordinator.resume(run.id)
                } catch (failure: Throwable) {
                    throw classifyResumeFailure(failure)
                }

                replaceCurrent(Current(project, definition, runtimeState))
                publishCurrent()
                startCycling()
            } catch (failure: Throwable) {
                replaceCurrent(null)
                publishFailure(failure)
            }
        }
    }

    suspend fun refresh() = loadLatest()

    /**
     * Runs [block] with the active run under the runtime lock, so a decision is built from the
     * current run (not a published snapshot or a historical view) and no cycle commits meanwhile.
     */
    internal suspend fun <T> withActiveRun(block: suspend (WorkflowRun) -> T): T =
        runtimeMutex.withLock {
            val snapshot = requireNotNull(current) { "No active workflow is loaded" }
            val live = snapshot.state.run
            val persisted = persistence.runs.get(live.id)
            block(persisted?.takeIf { it.updatedAtEpochMillis > live.updatedAtEpochMillis } ?: live)
        }

    suspend fun loadRunHistory(): List<Pair<Project, List<WorkflowRun>>> {
        val projects = persistence.projects.all()
        return projects.map { project ->
            project to persistence.runs.byProject(project.id)
                .sortedByDescending(WorkflowRun::updatedAtEpochMillis)
        }.sortedByDescending { (project, runs) ->
            runs.firstOrNull()?.updatedAtEpochMillis ?: project.updatedAtEpochMillis
        }
    }

    suspend fun switchToRun(runId: WorkflowRunId) {
        runtimeMutex.withLock {
            try {
                val run = persistence.runs.get(runId)
                    ?: error("Run ${runId.value} not found")
                val project = persistence.projects.get(run.projectId)
                    ?: error("Project ${run.projectId.value} not found")
                val stored = persistence.definitions.get(run.workflowDefinitionId)
                viewingRun = ViewingRun(project, stored ?: deletedHiveStandIn(run), run, deleted = stored == null)
                publishCurrent()
            } catch (failure: Throwable) {
                publishFailure(failure)
            }
        }
    }

    /** Clear any historical-run view and return to displaying the active run. */
    suspend fun clearRunView() {
        runtimeMutex.withLock {
            viewingRun = null
            publishCurrent()
        }
    }

    /** True while the active run has not finished: a new launch would take its place. */
    suspend fun hasUnfinishedActiveRun(): Boolean =
        runtimeMutex.withLock { current?.state?.run?.status?.isTerminal() == false }

    /**
     * Cancels [runId] when it is the active, unfinished run (every unfinished task and provider
     * session, as a replaced run is cancelled). False when it is not the active run or has ended.
     * For hosts outside the UI, such as the desktop A2A server (docs/A2A.md).
     */
    suspend fun cancelActiveRun(runId: WorkflowRunId): Boolean = runtimeMutex.withLock {
        val active = current ?: return@withLock false
        if (active.state.run.id != runId || active.state.run.status.isTerminal()) return@withLock false
        val cancelled = coordinator.cancel(active.project, active.definition, active.state, nowEpochMillis())
        replaceCurrent(active.copy(state = cancelled))
        publishCurrent()
        true
    }

    suspend fun launchStarterWorkflow(
        projectName: String,
        objective: String,
        repository: RepositoryRef? = null,
        existingProject: Project? = null,
        /** Runs the new run continues (one) or merges (several); empty starts a new root lineage. */
        continuesWorkflowRunIds: List<WorkflowRunId> = emptyList(),
    ) {
        runtimeMutex.withLock {
            val parents = requireKnownParentRuns(continuesWorkflowRunIds)
            val cleanProjectName = projectName.trim()
            val cleanObjective = objective.trim()
            require(cleanProjectName.isNotEmpty()) { "Project name is required" }
            require(cleanObjective.isNotEmpty()) { "Objective is required" }

            val normalizedRepository = repository?.normalized()
            val now = nowEpochMillis()
            val project = existingProject?.copy(
                name = cleanProjectName,
                repository = normalizedRepository ?: existingProject.repository,
                updatedAtEpochMillis = now,
            ) ?: Project(
                id = ProjectId("project-$now"),
                name = cleanProjectName,
                repository = normalizedRepository,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            val launchRoles = activeRoles(roles)
            val definition = StarterWorkflowFactory.create(
                id = WorkflowDefinitionId("workflow-$now"),
                objective = cleanObjective,
                roles = launchRoles,
            )
            val launchService = WorkflowLaunchService(
                preparer = WorkflowDefinitionPreparer(providerRegistry, launchRoles),
                persistence = persistence,
                eventSink = RepositoryWorkflowEventSink(persistence.events),
                roles = launchRoles,
            )
            val (prepared, runtimeState) = launchService.launch(
                project = project,
                definition = definition,
                workflowRunId = WorkflowRunId("run-$now"),
                objective = cleanObjective,
                nowEpochMillis = now,
                taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
                parentWorkflowRunIds = parents,
            )

            // The run being replaced would otherwise be orphaned Running with its provider
            // sessions still live: cancel it, best effort, before switching.
            current?.takeUnless { it.state.run.status.isTerminal() }?.let { previous ->
                try {
                    coordinator.cancel(previous.project, previous.definition, previous.state, now)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The new run still starts; the old one stays as persisted.
                }
            }
            replaceCurrent(Current(project, prepared, runtimeState))
            publishCurrent()
            startCycling()
        }
    }

    /**
     * Runs a finished run again from one of its tasks ([TaskRerunPlanner]): a new run in the same
     * project continues [sourceRunId]; the task (or, when [TaskRerunRequest.replacementOutput] is
     * given, only what follows it) and everything downstream start fresh, every other finished task
     * keeps its result, and the source run stays as it was. The new run becomes the active one.
     */
    suspend fun rerunFromTask(sourceRunId: WorkflowRunId, request: TaskRerunRequest) {
        runtimeMutex.withLock {
            // Rewinding a run that is still live: it is cancelled first (what had finished keeps its
            // result and carries over; what was running starts again in the new run), then forked.
            var cancelledLive = false
            current?.takeIf { it.state.run.id == sourceRunId && !it.state.run.status.isTerminal() }?.let { live ->
                coordinator.cancel(live.project, live.definition, live.state, nowEpochMillis())
                cancelledLive = true
            }
            val source = persistence.runs.get(sourceRunId)
                ?: error("Run ${sourceRunId.value} was not found")
            val storedProject = persistence.projects.get(source.projectId)
                ?: error("Project ${source.projectId.value} was not found")
            val definition = persistence.definitions.get(source.workflowDefinitionId)
                ?: error("Workflow definition ${source.workflowDefinitionId.value} was not found")
            val now = nowEpochMillis()
            val fork = TaskRerunPlanner.fork(
                definition = definition,
                source = source,
                request = request,
                workflowRunId = WorkflowRunId("run-$now"),
                nowEpochMillis = now,
                taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
                editedDefinitionId = WorkflowDefinitionId("workflow-$now"),
            )
            val project = storedProject.copy(updatedAtEpochMillis = now)
            persistence.projects.put(project)
            if (fork.definition.id != definition.id) persistence.definitions.put(fork.definition)
            persistence.runs.put(fork.run)
            fork.run.taskRuns.values.flatMap(TaskRun::artifacts).forEach { persistence.artifacts.put(it) }
            val eventSink = RepositoryWorkflowEventSink(persistence.events)
            eventSink.append(
                WorkflowCreated(
                    workflowRunId = fork.run.id,
                    projectId = fork.run.projectId,
                    workflowDefinitionId = fork.run.workflowDefinitionId,
                    objective = fork.run.objective,
                    occurredAtEpochMillis = now,
                ),
            )
            eventSink.append(fork.event)

            // As with a launch, one run is active at a time: a live one being replaced is cancelled,
            // best effort, rather than left Running with its sessions still open.
            current?.takeUnless { cancelledLive || it.state.run.status.isTerminal() }?.let { previous ->
                try {
                    coordinator.cancel(previous.project, previous.definition, previous.state, now)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The rerun still starts; the old run stays as persisted.
                }
            }
            val runProject = project.copy(repository = fork.run.repositorySnapshot ?: project.repository)
            replaceCurrent(Current(runProject, fork.definition, WorkflowRuntimeState(fork.run)))
            publishCurrent()
            startCycling()
        }
    }

    suspend fun approveTask(taskDefinitionId: TaskDefinitionId) {
        runtimeMutex.withLock { approveTaskLocked(taskDefinitionId, byDelegation = false) }
    }

    /**
     * Approves [taskDefinitionId]'s gate. [byDelegation] is true only when [approveDelegatedGatesLocked]
     * approves a plan gate the keeper delegated; everything else is the keeper's own decision.
     */
    private suspend fun approveTaskLocked(taskDefinitionId: TaskDefinitionId, byDelegation: Boolean) {
        val snapshot = requireNotNull(current) { "No active workflow is loaded" }
        val task = requireNotNull(snapshot.definition.tasks.firstOrNull { it.id == taskDefinitionId }) {
            "Task ${taskDefinitionId.value} is not defined"
        }
        val taskRun = requireNotNull(snapshot.state.run.taskRuns[taskDefinitionId]) {
            "Task ${taskDefinitionId.value} has no runtime state"
        }
        if (taskRun.awaitsNestedHumanApproval()) {
            require(!byDelegation) { "Nested human approvals are never delegated" }
            // The gate lives in the child run; the next cycle carries the decision back up.
            val childRunId = requireNotNull(taskRun.externalRunId)
            require(nestedWorkflows?.approvePendingHumanApproval(childRunId) == true) {
                "Nested workflow run $childRunId has no pending human approval"
            }
            return
        }
        require(taskRun.status == TaskRunStatus.AwaitingApproval) {
            "Task ${taskDefinitionId.value} is not awaiting approval"
        }

        val now = nowEpochMillis()
        val nextState = if (taskRun.isHeldAtContextTaintGate()) {
            // External-input gates are the keeper's own decision; never delegated.
            require(!byDelegation) { "External-input gates are never delegated" }
            // Held because its inputs include external content (ContextTaint.kt): approval is the
            // person's decision to let it act on them; the cycle then lets it run.
            val gateCoordinator = ApprovalGateCoordinator(
                repository = persistence.approvalGates,
                eventSink = RepositoryWorkflowEventSink(persistence.events),
            )
            val gateId = contextTaintGateId(snapshot.state.run.id, taskDefinitionId, taskRun.attempt)
            if (persistence.approvalGates.get(gateId) == null) {
                gateCoordinator.open(
                    id = gateId,
                    workflowRunId = snapshot.state.run.id,
                    taskDefinitionId = taskDefinitionId,
                    kind = ApprovalGateKind.SecurityRisk,
                    reason = taskRun.blockingReason?.message ?: "External inputs for '${task.name}'",
                    requiresHuman = true,
                    nowEpochMillis = now,
                )
            }
            val gate = requireNotNull(persistence.approvalGates.get(gateId))
            if (gate.status == ApprovalGateStatus.Pending || gate.status == ApprovalGateStatus.Applying) {
                gateCoordinator.decide(
                    id = gateId,
                    approved = true,
                    decidedByRoleId = null,
                    note = "Approved in application",
                    nowEpochMillis = now,
                )
            }
            coordinator.cycle(
                project = snapshot.project,
                definition = snapshot.definition,
                state = snapshot.state,
                nowEpochMillis = now,
                artifactIdFactory = ::artifactId,
            )
        } else if (taskRun.assignedProviderId != null) {
            // An engine-held plan has no provider session yet; approval only records the decision.
            val handle = if (taskRun.isHeldAtEnginePlanGate()) {
                null
            } else {
                requireNotNull(snapshot.state.handles[taskDefinitionId]) {
                    "Task ${taskDefinitionId.value} has no provider session to approve"
                }
            }
            val eventSink = RepositoryWorkflowEventSink(persistence.events)
            val gateCoordinator = ApprovalGateCoordinator(
                repository = persistence.approvalGates,
                eventSink = eventSink,
            )
            val approvalService = WorkflowApprovalService(
                gateRepository = persistence.approvalGates,
                gateCoordinator = gateCoordinator,
                sessionGateway = sessionGateway,
                failureEscalationDecisionStore = persistence,
            )
            val gateId = ApprovalGateId(
                "plan:${snapshot.state.run.id.value}:${taskDefinitionId.value}:${taskRun.attempt}",
            )
            val delegation = planGateDelegation(snapshot.definition, snapshot.state.run, taskDefinitionId)
            if (byDelegation) {
                // The single eligibility check runs again at the moment of approval.
                require(delegation != null && delegations?.isDelegated(delegation.key) == true) {
                    "Task ${taskDefinitionId.value}'s plan gate is not delegated"
                }
            }
            if (persistence.approvalGates.get(gateId) == null) {
                gateCoordinator.open(
                    id = gateId,
                    workflowRunId = snapshot.state.run.id,
                    taskDefinitionId = taskDefinitionId,
                    kind = ApprovalGateKind.PlanApproval,
                    reason = "Plan approval required for '${task.name}'",
                    requiresHuman = true,
                    nowEpochMillis = now,
                )
            }

            val decision = approvalService.approvePlan(
                gateId = gateId,
                handle = handle,
                decidedByRoleId = null,
                note = if (byDelegation) "Approved by delegation" else "Approved in application",
                nowEpochMillis = now,
                byDelegation = byDelegation,
            )
            if (!byDelegation && decision.status == ApprovalGateStatus.Approved && delegation != null) {
                delegations?.recordApproval(delegation.key, delegation.roleName)
            }
            val stateForCycle = if (decision.status == ApprovalGateStatus.Rejected) {
                snapshot.state.copy(handles = snapshot.state.handles - taskDefinitionId)
            } else {
                snapshot.state
            }
            coordinator.cycle(
                project = snapshot.project,
                definition = snapshot.definition,
                state = stateForCycle,
                nowEpochMillis = now,
                artifactIdFactory = ::artifactId,
            )
        } else {
            // Invariant 4 (docs/THREAT_MODEL.md): a HumanApproval task is always the keeper's own act.
            require(!byDelegation) { "Human approval tasks are never delegated" }
            require(task.effectiveExecutor() is TaskExecutor.HumanApproval) {
                "Task ${taskDefinitionId.value} is not a human approval gate"
            }
            WorkflowRuntimeState(
                run = engine.completeHumanApprovalTask(
                    definition = snapshot.definition,
                    run = snapshot.state.run,
                    taskDefinitionId = taskDefinitionId,
                    nowEpochMillis = now,
                ),
                handles = snapshot.state.handles,
            ).also {
                coordinator.persist(snapshot.project, snapshot.definition, it)
            }
        }

        replaceCurrent(snapshot.copy(state = nextState))
        publishCurrent()
    }

    /** The delegation [taskDefinitionId]'s plan gate falls under, or null when it can never be delegated. */
    private suspend fun planGateDelegation(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
    ): PlanGateDelegation? {
        if (delegations == null) return null
        val task = definition.tasks.firstOrNull { it.id == taskDefinitionId } ?: return null
        val taskRun = run.taskRuns[taskDefinitionId] ?: return null
        val roleId = taskRun.assignedRoleId ?: task.roleId
        val role = roleId?.let { id ->
            run.roleSnapshot.firstOrNull { it.id == id } ?: roles.firstOrNull { it.id == id }
        }
        val executor = taskRun.executor
            ?: runCatching { task.effectiveExecutor(role) }.getOrNull()
            ?: return null
        val providerId = taskRun.assignedProviderId
        val capabilities = providerId?.let { id ->
            try {
                providerRegistry.provider(id)?.capabilities()?.supported
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
        val eligibility = DelegationPolicy.eligibility(
            GateDelegationContext(
                gateKind = ApprovalGateKind.PlanApproval,
                executor = executor,
                role = role,
                requiredRoleAuthority = task.requiredRoleAuthority,
                providerId = providerId,
                providerCapabilities = capabilities,
            ),
        )
        return when (eligibility) {
            is DelegationEligibility.Eligible -> PlanGateDelegation(eligibility.key, role?.name ?: eligibility.key.roleId)
            is DelegationEligibility.Ineligible -> null
        }
    }

    private data class PlanGateDelegation(val key: DelegationKey, val roleName: String)

    /** The keeper rejected [taskDefinitionId]'s plan: its role's streak on that provider starts over. */
    internal fun recordPlanRejection(run: WorkflowRun, taskDefinitionId: TaskDefinitionId) {
        val store = delegations ?: return
        val taskRun = run.taskRuns[taskDefinitionId] ?: return
        val roleId = taskRun.assignedRoleId ?: return
        store.recordRejection(roleId.value, taskRun.assignedProviderId?.value)
    }

    /**
     * Approves every plan gate waiting on the keeper that the keeper delegated, each recorded as
     * approved by delegation. Eligibility is checked again for each, so a role that has since gained
     * a writing authority, or now runs on a provider that writes, asks again.
     */
    private suspend fun approveDelegatedGatesLocked() {
        val store = delegations ?: return
        if (store.state.value.delegations.isEmpty()) return
        val waiting = current?.state?.run?.taskRuns?.keys?.toList() ?: return
        for (taskDefinitionId in waiting) {
            val snapshot = current ?: return
            if (snapshot.state.run.status.isTerminal()) return
            val taskRun = snapshot.state.run.taskRuns[taskDefinitionId] ?: continue
            if (taskRun.status != TaskRunStatus.AwaitingApproval || taskRun.assignedProviderId == null) continue
            if (taskRun.awaitsNestedHumanApproval()) continue
            val delegation = planGateDelegation(snapshot.definition, snapshot.state.run, taskDefinitionId) ?: continue
            if (!store.isDelegated(delegation.key)) continue
            try {
                approveTaskLocked(taskDefinitionId, byDelegation = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The gate keeps waiting on the keeper, who can still decide it by hand.
            }
        }
    }

    /**
     * Lifts the active run's spend cap stop: its tasks return to where they were and stopped
     * sessions start again as a new attempt. A cap still reached stops the run again at its next check.
     */
    suspend fun liftSpendStop() {
        runtimeMutex.withLock {
            val snapshot = requireNotNull(current) { "No active workflow is loaded" }
            val live = snapshot.state.run
            val run = persistence.runs.get(live.id)
                ?.takeIf { it.updatedAtEpochMillis > live.updatedAtEpochMillis }
                ?: live
            if (run.spendPause == null || run.status.isTerminal()) return@withLock
            val lifted = liftSpendPause(run, maxOf(nowEpochMillis(), run.updatedAtEpochMillis + 1))
            persistence.runs.put(lifted)
            replaceCurrent(snapshot.copy(state = WorkflowRuntimeState(lifted, emptyMap())))
            publishCurrent()
        }
    }

    /**
     * The keeper's pause of the active run, or its resume (docs/Keepers-Room.md, Pause, change, resume).
     * Paused, nothing new starts; what is running finishes, and nothing is cancelled or parked, so
     * resuming starts nothing over. A run that has finished is left alone.
     */
    suspend fun setKeeperPause(paused: Boolean) {
        runtimeMutex.withLock {
            val snapshot = requireNotNull(current) { "No active workflow is loaded" }
            val live = snapshot.state.run
            val run = persistence.runs.get(live.id)
                ?.takeIf { it.updatedAtEpochMillis > live.updatedAtEpochMillis }
                ?: live
            if (run.status.isTerminal() || (run.keeperPause != null) == paused) return@withLock
            val now = maxOf(nowEpochMillis(), run.updatedAtEpochMillis + 1)
            val updated = run.copy(
                keeperPause = if (paused) WorkflowKeeperPause(pausedAtEpochMillis = now) else null,
                updatedAtEpochMillis = now,
            )
            persistence.runs.put(updated)
            replaceCurrent(snapshot.copy(state = WorkflowRuntimeState(updated, snapshot.state.handles)))
            publishCurrent()
        }
    }

    /**
     * An edit saved to the hive on screen reaches its live run (docs/Keepers-Room.md, Pause, change,
     * resume). Only a creature the run has not started (waiting, ready or about to retry) takes the
     * edit, its job and criteria, now, because the engine reads a task's definition when it starts it;
     * a creature that is running or done, a creature's role, executor and strands, and the hive's shape
     * stay as the run has them and change from the next run. Nothing is cancelled and nothing restarts.
     * Returns how many creatures took an edit.
     */
    suspend fun applyLiveDefinitionEdit(edited: WorkflowDefinition): Int = runtimeMutex.withLock {
        val snapshot = current ?: return@withLock 0
        val run = snapshot.state.run
        if (snapshot.definition.id != edited.id || run.status.isTerminal()) return@withLock 0
        val open = setOf(TaskRunStatus.Created, TaskRunStatus.Blocked, TaskRunStatus.Ready, TaskRunStatus.Retrying)
        var taken = 0
        val tasks = snapshot.definition.tasks.map { old ->
            val new = edited.tasks.firstOrNull { it.id == old.id }
            if (new != null && run.taskRuns[old.id]?.status in open) {
                val merged = new.copy(dependsOn = old.dependsOn, roleId = old.roleId, executor = old.executor)
                if (merged != old) taken += 1
                merged
            } else {
                old
            }
        }
        if (taken == 0) return@withLock 0
        replaceCurrent(snapshot.copy(definition = snapshot.definition.copy(tasks = tasks)))
        publishCurrent()
        taken
    }

    /**
     * Rewinds the active run to [taskDefinitionId] in place: that creature and what follows it start
     * again while the rest of the swarm keeps running ([WorkflowRuntimeCoordinator.rewind]).
     */
    suspend fun rewindToTask(taskDefinitionId: TaskDefinitionId) {
        runtimeMutex.withLock {
            val snapshot = requireNotNull(current) { "No active workflow is loaded" }
            val next = coordinator.rewind(
                project = snapshot.project,
                definition = snapshot.definition,
                state = snapshot.state,
                taskId = taskDefinitionId,
                nowEpochMillis = nowEpochMillis(),
            )
            replaceCurrent(snapshot.copy(state = next))
            publishCurrent()
            appLog("Rewind", "Rewound ${taskDefinitionId.value} and what follows it; the rest keeps running.")
        }
    }

    fun close() {
        cycleJob?.cancel()
        cycleJob = null
        replaceCurrent(null)
        runtimeScope.cancel()
    }

    private fun replaceCurrent(next: Current?) {
        currentGeneration += 1L
        current = next
        viewingRun = null
    }

    private fun startCycling() {
        if (cycleJob?.isActive == true) return
        cycleJob = runtimeScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(CYCLE_INTERVAL_MILLIS)
                var failed = false
                runtimeMutex.withLock {
                    val snapshot = current ?: return@withLock
                    val snapshotGeneration = currentGeneration
                    if (snapshot.state.run.status.isTerminal()) return@withLock

                    try {
                        val nextState = coordinator.cycle(
                            project = snapshot.project,
                            definition = snapshot.definition,
                            state = snapshot.state,
                            nowEpochMillis = nowEpochMillis(),
                            artifactIdFactory = ::artifactId,
                        )
                        if (currentGeneration != snapshotGeneration || current !== snapshot) return@withLock
                        replaceCurrent(snapshot.copy(state = nextState))
                        publishCurrent()
                        approveDelegatedGatesLocked()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        failed = true
                        val classified = classifyRuntimeFailure(failure)
                        // A transient error keeps the Live view; it is only replaced when the store
                        // is corrupted or the failure persists across several cycles.
                        val persistent = classified is ApplicationRuntimeFailure.Corrupted ||
                            consecutiveFailures + 1 >= TRANSIENT_FAILURE_LIMIT
                        if (persistent && currentGeneration == snapshotGeneration && current === snapshot) {
                            publishFailure(classified)
                        }
                    }
                }
                if (failed) {
                    consecutiveFailures += 1
                    delay(RETRY_BACKOFF_MILLIS * consecutiveFailures.coerceAtMost(MAX_BACKOFF_MULTIPLIER))
                } else {
                    consecutiveFailures = 0
                }
            }
        }
    }

    private fun publishCurrent() {
        val viewing = viewingRun
        if (viewing != null) {
            publisher.publish(
                ApplicationRuntimeState.Live(
                    LiveWorkflowPresentation(
                        project = viewing.project,
                        definition = viewing.definition,
                        run = viewing.run,
                        roles = roles,
                    ),
                ),
            )
            return
        }
        val snapshot = current ?: return
        publisher.publish(
            ApplicationRuntimeState.Live(
                LiveWorkflowPresentation(
                    project = snapshot.project,
                    definition = snapshot.definition,
                    run = snapshot.state.run,
                    roles = roles,
                ),
            ),
        )
    }

    internal fun publishFailure(failure: Throwable) {
        val message = failure.message
            ?.takeIf(String::isNotBlank)
            ?: failure::class.simpleName.orEmpty().ifBlank { "Runtime failure" }
        publisher.publish(
            when (failure) {
                is ApplicationRuntimeFailure.Disconnected -> ApplicationRuntimeState.Disconnected(message)
                is ApplicationRuntimeFailure.Corrupted -> ApplicationRuntimeState.ResumeFailed(message, isCorrupted = true)
                else -> ApplicationRuntimeState.ResumeFailed(message)
            },
        )
    }

    suspend fun recoverFromCorruption() {
        val settingsPersistence = persistence as? DurableWorkflowPersistence ?: return
        settingsPersistence.recoverFromCorruption()
        loadLatest()
    }

    suspend fun checkProviderHealth(): Map<String, Result<AgentCapabilities>> {
        val results = mutableMapOf<String, Result<AgentCapabilities>>()
        for (providerId in providerRegistry.providerIds) {
            val provider = providerRegistry.provider(providerId) ?: continue
            results[providerId.value] = runCatching { provider.capabilities() }
        }
        return results
    }

    suspend fun exportJson(): String? =
        (persistence as? DurableWorkflowPersistence)?.exportJson()

    suspend fun exportCurrentProjectFile(): IveProjectExport? {
        val project = when (val snapshot = state.value) {
            is ApplicationRuntimeState.NoRun -> snapshot.project
            is ApplicationRuntimeState.Live -> snapshot.presentation.project
            else -> runtimeMutex.withLock { viewingRun?.project ?: current?.project }
        } ?: return null
        val settingsPersistence = persistence as? DurableWorkflowPersistence ?: return null
        val encoded = settingsPersistence.exportProjectFile(project.id, nowEpochMillis())
        return IveProjectExport(
            projectId = project.id.value,
            projectName = project.name,
            fileName = (project.name + "-" + project.id.value.takeLast(8)).asIveFileName(),
            content = encoded,
        )
    }

    suspend fun importProjectFile(encoded: String): Project {
        val settingsPersistence = persistence as? DurableWorkflowPersistence
            ?: error(".ive project import requires durable persistence")
        return settingsPersistence.importProjectFile(encoded)
    }

    suspend fun openProject(projectId: ProjectId) {
        runtimeMutex.withLock {
            publisher.publish(ApplicationRuntimeState.Loading)
            try {
                val project = persistence.projects.get(projectId)
                    ?: error("Project ${projectId.value} was not found")
                val knownHives = persistence.definitions.all().mapTo(mutableSetOf()) { it.id }
                val run = persistence.runs.byProject(projectId)
                    .filter { it.workflowDefinitionId in knownHives }
                    .maxByOrNull(WorkflowRun::updatedAtEpochMillis)
                if (run == null) {
                    replaceCurrent(null)
                    publisher.publish(ApplicationRuntimeState.NoRun(project = project, roles = roles))
                    return@withLock
                }

                val definition = persistence.definitions.get(run.workflowDefinitionId)
                    ?: error("Workflow definition ${run.workflowDefinitionId.value} was not found")
                val runtimeState = try {
                    coordinator.resume(run.id)
                } catch (failure: Throwable) {
                    throw classifyResumeFailure(failure)
                }
                replaceCurrent(Current(project, definition, runtimeState))
                publishCurrent()
                startCycling()
            } catch (failure: Throwable) {
                replaceCurrent(null)
                publishFailure(failure)
            }
        }
    }

    suspend fun importJson(encoded: String) {
        (persistence as? DurableWorkflowPersistence)?.importJson(encoded)
        loadLatest()
    }

    suspend fun loadRunTimeline(): List<WorkflowEvent> {
        val runId = runtimeMutex.withLock { current?.state?.run?.id } ?: return emptyList()
        return persistence.events.forRun(runId)
    }

    suspend fun saveRole(role: RoleDefinition) {
        persistence.roles.put(role)
        loadLatest()
    }

    suspend fun saveRoleCollection(activeRoleCollection: List<RoleDefinition>) {
        requireStarterRoleCoverage(activeRoleCollection)
        val existing = persistence.roles.all()
        val nextRoles = roleCollectionEntries(activeRoleCollection, existing)
        persistence.replaceCatalog(
            definitions = persistence.definitions.all(),
            roles = nextRoles,
        )
    }

    suspend fun resetRoleCollection() {
        val existing = persistence.roles.all()
        val nextRoles = defaultRoleCollectionEntries(existing)
        persistence.replaceCatalog(
            definitions = persistence.definitions.all(),
            roles = nextRoles,
        )
    }

    suspend fun assignWorkflowCompute(
        workflowDefinitionId: WorkflowDefinitionId,
        target: ComputeDelegationTarget,
    ) {
        updateComputeDelegation(workflowDefinitionId) { definition ->
            definition.withWorkflowComputeDelegation(target)
        }
    }

    suspend fun assignRoleCompute(
        workflowDefinitionId: WorkflowDefinitionId,
        roleId: RoleDefinitionId,
        target: ComputeDelegationTarget,
    ) {
        updateComputeDelegation(workflowDefinitionId) { definition ->
            definition.withRoleComputeDelegation(roleId, target)
        }
    }

    suspend fun assignTaskCompute(
        workflowDefinitionId: WorkflowDefinitionId,
        taskDefinitionId: TaskDefinitionId,
        target: ComputeDelegationTarget,
    ) {
        updateComputeDelegation(workflowDefinitionId) { definition ->
            definition.withTaskComputeDelegation(taskDefinitionId, target)
        }
    }

    private suspend fun updateComputeDelegation(
        workflowDefinitionId: WorkflowDefinitionId,
        transform: (WorkflowDefinition) -> WorkflowDefinition,
    ) {
        runtimeMutex.withLock {
            val active = current
            val viewed = viewingRun
            val base = when {
                active?.definition?.id == workflowDefinitionId -> active.definition
                viewed != null && !viewed.deleted && viewed.definition.id == workflowDefinitionId -> viewed.definition
                else -> persistence.definitions.get(workflowDefinitionId)
            } ?: error("Workflow definition " + workflowDefinitionId.value + " was not found")

            val updated = transform(base)
            if (updated == base) return@withLock
            persistence.definitions.put(updated)

            if (active?.definition?.id == workflowDefinitionId) {
                currentGeneration += 1L
                current = active.copy(definition = updated)
            }
            if (viewed?.definition?.id == workflowDefinitionId) {
                viewingRun = viewed.copy(definition = updated)
            }
            if (
                current?.definition?.id == workflowDefinitionId ||
                viewingRun?.definition?.id == workflowDefinitionId
            ) {
                publishCurrent()
            }
        }
    }

    fun validateCurrentWorkflow(): List<String> {
        val definition = current?.definition ?: return emptyList()
        return WorkflowGraphValidator.validate(definition).map { it.humanReadable() }
    }

    suspend fun exportDiagnosticBundle(): String {
        val snapshot = runtimeMutex.withLock { current }
        val run = snapshot?.state?.run
        val definition = snapshot?.definition
        val events = if (run != null) persistence.events.forRun(run.id) else emptyList()

        val retryCount = events.count { it is com.hereliesaz.geministrator.events.RetryScheduled }
        val failureCount = events.count { it is com.hereliesaz.geministrator.events.TaskFailed }
        val escalationCount = events.count { it is com.hereliesaz.geministrator.events.TaskEscalated }
        val artifactCount = events.count { it is com.hereliesaz.geministrator.events.ArtifactCreated }
        val durationMs = if (run != null) run.updatedAtEpochMillis - run.createdAtEpochMillis else 0L

        val startsByTask = events
            .filterIsInstance<com.hereliesaz.geministrator.events.TaskStarted>()
            .groupBy { it.taskDefinitionId }
            .mapValues { (_, starts) -> starts.minOf { it.occurredAtEpochMillis } }
        val completionsByTask = events
            .filterIsInstance<com.hereliesaz.geministrator.events.TaskCompleted>()
            .associateBy { it.taskDefinitionId }

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"haive-diagnostic-v1\",")
            appendLine("  \"runId\": ${jsonStr(run?.id?.value)},")
            appendLine("  \"objective\": ${jsonStr(run?.objective)},")
            appendLine("  \"status\": ${jsonStr(run?.status?.name)},")
            appendLine("  \"durationMs\": $durationMs,")
            appendLine("  \"taskCount\": ${definition?.tasks?.size ?: 0},")
            appendLine("  \"taskRunCount\": ${run?.taskRuns?.size ?: 0},")
            appendLine("  \"eventCount\": ${events.size},")
            appendLine("  \"retryCount\": $retryCount,")
            appendLine("  \"failureCount\": $failureCount,")
            appendLine("  \"escalationCount\": $escalationCount,")
            appendLine("  \"artifactCount\": $artifactCount,")
            appendLine("  \"taskRuns\": [")
            val taskRuns = run?.taskRuns?.values?.toList() ?: emptyList()
            taskRuns.forEachIndexed { index, taskRun ->
                val comma = if (index < taskRuns.size - 1) "," else ""
                val taskStart = startsByTask[taskRun.taskDefinitionId]
                val taskEnd = completionsByTask[taskRun.taskDefinitionId]?.occurredAtEpochMillis
                val taskDurationMs = if (taskStart != null && taskEnd != null) taskEnd - taskStart else "null"
                appendLine("    {\"id\": ${jsonStr(taskRun.taskDefinitionId.value)}, \"status\": ${jsonStr(taskRun.status.name)}, \"attempt\": ${taskRun.attempt}, \"durationMs\": $taskDurationMs, \"providerId\": ${jsonStr(taskRun.assignedProviderId?.value)}}$comma")
            }
            appendLine("  ]")
            append("}")
        }
    }

    private fun jsonStr(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    private fun WorkflowRunStatus.isTerminal() =
        this == WorkflowRunStatus.Completed ||
            this == WorkflowRunStatus.Failed ||
            this == WorkflowRunStatus.Cancelled

    private fun artifactId(
        taskRun: TaskRun,
        artifact: ProviderArtifact,
        index: Int,
    ) = ArtifactId("${taskRun.id.value}:${artifact.kind}:${taskRun.attempt}:$index")

    companion object {
        private const val CYCLE_INTERVAL_MILLIS = 1_000L
        private const val RETRY_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MULTIPLIER = 15
        private const val TRANSIENT_FAILURE_LIMIT = 3

        internal fun classifyResumeFailure(failure: Throwable): ApplicationRuntimeFailure =
            classifyRuntimeFailure(failure)

        internal fun classifyRuntimeFailure(failure: Throwable): ApplicationRuntimeFailure = when (failure) {
            is ApplicationRuntimeFailure -> failure
            is PersistenceCorruptionException -> ApplicationRuntimeFailure.Corrupted(
                failure.message ?: "Workflow persistence is corrupted",
                failure,
            )
            is ManagedSessionFailure.ProviderUnavailable -> ApplicationRuntimeFailure.Disconnected(
                failure.message ?: "Provider runtime is unavailable",
                failure,
            )
            else -> ApplicationRuntimeFailure.Resume(
                failure.message ?: "Runtime operation failed",
                failure,
            )
        }

        suspend fun create(
            providers: Collection<AgentProvider>,
            scope: CoroutineScope,
            persistence: WorkflowPersistence = com.hereliesaz.geministrator.persistence.WorkflowPersistenceFactory.durable,
            executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
            roleSurfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
            providerRegistry: AgentProviderRegistry? = null,
            orchestrationUtilities: LocalOrchestrationUtilityFamily =
                DeterministicLocalOrchestrationUtilities,
            localModelLibrary: LocalModelLibrary = MemoryEpoch8LocalModelLibrary.library,
            /** Opt-in, task-scoped external agent conversation channel. Off unless explicitly provided. */
            duplexAttachment: DuplexSessionAttachment? = null,
            /** Repository and artifact chunks offered to the Context Packer; upstream artifacts only by default. */
            contextRetriever: com.hereliesaz.geministrator.retrieval.ContextRetriever? =
                com.hereliesaz.geministrator.retrieval.SimilarityContextRetriever(
                    listOf(com.hereliesaz.geministrator.retrieval.ArtifactChunkSource),
                ),
            /** The keeper's spend caps (Keeper's room, This device); none unless provided. */
            spendCaps: () -> SpendCaps = { SpendCaps() },
            /** Opt-in OTLP trace export of finished runs (docs/architecture/OBSERVABILITY.md); null exports nothing. */
            traceExport: com.hereliesaz.geministrator.telemetry.WorkflowTraceExport? = null,
            /** The keeper's approval habits (workflow/ProgressiveDelegation.kt); null leaves every gate asking. */
            delegations: ProgressiveDelegationStore? = null,
        ): ApplicationRuntime {
            val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
            val runtimeScope = CoroutineScope(scope.coroutineContext + runtimeJob)
            // Refinement-ladder training data: attached here, recording only once capture is switched on.
            com.hereliesaz.geministrator.workflow.refinement.LadderDatasetBridge.attach(persistence.inferenceSettings)
            val registry = providerRegistry ?: AgentProviderRegistry(
                providers = providers,
                inferenceSettings = persistence.inferenceSettings,
                orchestrationUtilities = orchestrationUtilities,
                localModelLibrary = localModelLibrary,
            )
            val gateway = ProviderBackedManagedSessionGateway(
                providerRegistry = registry,
                scope = runtimeScope,
                orchestrationUtilities = orchestrationUtilities,
                contextRetriever = contextRetriever,
                duplexAttachment = duplexAttachment,
            )
            val publisher = WorkflowRuntimePublisher()
            val effectiveExecutorIntegrations = executorIntegrations
                .withOrchestrationUtilities(orchestrationUtilities)
                .withInferenceDataRegistry(registry.inferenceFabric.dataRegistry)
                .withIntegration(
                    com.hereliesaz.geministrator.workflow.GenealogyGovernanceExecutorIntegration(
                        registry.genealogyGovernance,
                    ),
                )

            fun build(roles: List<RoleDefinition>): ApplicationRuntime {
                val engine = WorkflowEngine(
                    sessionGateway = gateway,
                    roles = roles,
                    eventSink = RepositoryWorkflowEventSink(persistence.events),
                    orchestrationUtilities = orchestrationUtilities,
                    surfaceRuntime = roleSurfaceRuntime,
                )
                // Nested workflow nodes run child runs through this same engine and persistence.
                // Each child gets its own coordinator because coordinator cycles are not reentrant.
                lateinit var runtimeIntegrations: TaskExecutorIntegrationRegistry
                fun newCoordinator() = WorkflowRuntimeCoordinator(
                    persistence = persistence,
                    engine = engine,
                    sessionGateway = gateway,
                    executorIntegrations = runtimeIntegrations,
                    orchestrationUtilities = orchestrationUtilities,
                    surfaceRuntime = roleSurfaceRuntime,
                    spendCaps = spendCaps,
                )
                val nestedWorkflows = WorkflowRunNestedWorkflowClient(
                    persistence = persistence,
                    engine = engine,
                    coordinatorFactory = ::newCoordinator,
                    nowEpochMillis = ::nowEpochMillis,
                )
                runtimeIntegrations = effectiveExecutorIntegrations
                    .withIntegration(NestedWorkflowExecutorIntegration(nestedWorkflows))
                return ApplicationRuntime(
                    persistence,
                    registry,
                    gateway,
                    engine,
                    newCoordinator(),
                    publisher,
                    runtimeScope,
                    roles,
                    nestedWorkflows,
                    delegations,
                )
            }

            val runtime = try {
                val roles = resolveRoleCollection(persistence.roles.all())
                build(roles)
            } catch (failure: Throwable) {
                build(BuiltInRoles.all).also {
                    it.publishFailure(
                        ApplicationRuntimeFailure.Resume(
                            failure.message ?: "Runtime bootstrap failed",
                            failure,
                        ),
                    )
                }
            }

            traceExport?.attach(
                scope = runtimeScope,
                runs = runtime.state.map { (it as? ApplicationRuntimeState.Live)?.presentation?.run },
                events = persistence.events,
            )
            if (runtime.state.value == ApplicationRuntimeState.Loading) {
                runtime.loadLatest()
            }
            return runtime
        }
    }
}


/**
 * Correct only the persisted Node Creature Rig Pipeline back-edge. Provenance review must
 * precede rig generation, and QC must wait for the generated sheets. All other stored graphs
 * and run data are deliberately left untouched.
 */
internal fun repairKnownNodeCreatureRigCycle(definition: WorkflowDefinition): WorkflowDefinition {
    if (definition.id != WorkflowDefinitionId("node-creature-rig-pipeline")) return definition
    val batchReview = TaskDefinitionId("batch-review")
    val qcAudit = TaskDefinitionId("qc-audit")
    val firstRig = TaskDefinitionId("rig-01")
    val cycle = com.hereliesaz.geministrator.workflow.WorkflowValidationError.Cycle(
        setOf(batchReview, qcAudit, firstRig),
    )
    if (WorkflowGraphValidator.validate(definition) != listOf(cycle)) return definition

    val reviewTask = definition.tasks.singleOrNull { it.id == batchReview } ?: return definition
    if (qcAudit !in reviewTask.dependsOn) return definition

    val corrected = definition.copy(
        tasks = definition.tasks.map { task ->
            if (task.id == batchReview) task.copy(dependsOn = task.dependsOn - qcAudit) else task
        },
    )
    return corrected.takeIf { WorkflowGraphValidator.validate(it).isEmpty() } ?: definition
}

@OptIn(ExperimentalTime::class)
private fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * The parents of a run the user launches as a continuation (one earlier run) or a merge (several):
 * each must be a stored run. Duplicates collapse; order is kept (the first is the primary parent).
 */
internal suspend fun ApplicationRuntime.requireKnownParentRuns(parents: List<WorkflowRunId>): List<WorkflowRunId> =
    parents.distinct().onEach { parent ->
        requireNotNull(persistence.runs.get(parent)) { "Workflow run ${parent.value} to continue was not found" }
    }
