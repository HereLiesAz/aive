package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ContextProvenance
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
import com.hereliesaz.geministrator.domain.isUntrusted
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
import com.hereliesaz.geministrator.workflow.HallMonitorGovernanceService
import com.hereliesaz.geministrator.workflow.LiveDefinitionEdit
import com.hereliesaz.geministrator.workflow.LiveEditMerge
import com.hereliesaz.geministrator.workflow.ProgressiveDelegationStore
import com.hereliesaz.geministrator.workflow.ManagedSessionFailure
import com.hereliesaz.geministrator.workflow.ManagedSessionGateway
import com.hereliesaz.geministrator.workflow.NestedWorkflowExecutorIntegration
import com.hereliesaz.geministrator.workflow.ProviderBackedManagedSessionGateway
import com.hereliesaz.geministrator.workflow.RoleSurfaceRuntimeRegistry
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.queen.QueenAction
import com.hereliesaz.geministrator.queen.queenAcceptRefused
import com.hereliesaz.geministrator.queen.queenCandidates
import com.hereliesaz.geministrator.queen.queenIsMoot
import com.hereliesaz.geministrator.queen.queenNewProposals
import com.hereliesaz.geministrator.queen.queenSignals
import com.hereliesaz.geministrator.queen.queenSuggestionNotes
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
import com.hereliesaz.geministrator.workflow.cancelledRunView
import com.hereliesaz.geministrator.workflow.decisionTime
import com.hereliesaz.geministrator.workflow.definitionFor
import com.hereliesaz.geministrator.workflow.newestOf
import com.hereliesaz.geministrator.workflow.WorkflowRunNestedWorkflowClient
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeState
import com.hereliesaz.geministrator.workflow.uniqueLaunchStamp
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
    /** How much the queen tells the keeper in Messages; read at each publish, so a change applies at the next cycle. */
    private val queenLevel: () -> com.hereliesaz.geministrator.queen.QueenLevel = { com.hereliesaz.geministrator.queen.QueenLevel.Quiet },
    /** Where her suggestions are kept (the one improvement ledger); null means she makes none. */
    private val queenLedger: com.hereliesaz.geministrator.workflow.improvement.ImprovementStore? = null,
    /** The keeper's allowances, so she can say a run is close to its own. */
    private val queenCaps: () -> SpendCaps = { SpendCaps() },
) {
    private data class QueenReview(
        val runId: String,
        val reportId: String,
        val loadedAtEpochMillis: Long,
        val state: HallMonitorPauseReviewState?,
    )

    /** The freeze review the queen last read; refreshed by the cycle while frozen, so publishing stays pure. */
    private var queenReview: QueenReview? = null

    /** Her rows in the ledger, as the cycle last read them, so publishing stays pure and never waits on the store. */
    private var queenProposals: List<com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal> = emptyList()
    private var queenProposalsReadAt: Long = 0L

    /** What the queen consulted before planning each run launched here (memory only; the Story keeps the line). */
    private val researchRecords = LinkedHashMap<String, com.hereliesaz.geministrator.research.ResearchRecord>()

    internal fun rememberResearch(runId: String, record: com.hereliesaz.geministrator.research.ResearchRecord) {
        researchRecords[runId] = record
        while (researchRecords.size > 32) researchRecords.remove(researchRecords.keys.first())
    }

    /** What the queen found missing when she staffed each run launched here (memory only; she says it once in Messages). */
    private val queenMissing = LinkedHashMap<String, List<com.hereliesaz.geministrator.queen.Missing>>()

    internal fun rememberMissing(runId: String, missing: List<com.hereliesaz.geministrator.queen.Missing>) {
        if (missing.isEmpty()) return
        queenMissing[runId] = missing
        while (queenMissing.size > 32) queenMissing.remove(queenMissing.keys.first())
    }

    /**
     * Whether the reviewer may be asked now, as the keeper's caps and the Sources see it ([com.hereliesaz.geministrator.queen.planningMayCall]):
     * the reviewer's price from the price table, the real hourly spend (read only when an hourly cap is set) and the room on the
     * Sources it may ask. [callsSoFar] counts the planning calls already made.
     */
    internal suspend fun reviewGateFor(
        reviewer: com.hereliesaz.geministrator.orchestration.PlanReviewer,
        callsSoFar: Int,
    ): com.hereliesaz.geministrator.queen.CallDecision {
        val caps = queenCaps()
        val uses = reviewer.uses
        val read = com.hereliesaz.geministrator.queen.priceRead(
            uses,
            com.hereliesaz.geministrator.providers.ModelPriceTable.Default,
            com.hereliesaz.geministrator.queen.PlanningLimits.MAX_INPUT_TOKENS.toLong(),
            com.hereliesaz.geministrator.queen.PlanningLimits.MAX_OUTPUT_TOKENS.toLong(),
        )
        val hourly = if (caps.hourlySpendCapUsd != null) {
            com.hereliesaz.geministrator.workflow.hourlySpend(persistence, nowEpochMillis(), null, com.hereliesaz.geministrator.workflow.AmbientSpendLedger.Shared)
        } else {
            com.hereliesaz.geministrator.workflow.HourlySpend(0.0, false)
        }
        val noRoom = providerRegistry.keylessWithoutRoom()
        val room = uses.isEmpty() || uses.any { it.sourceId !in noRoom }
        return com.hereliesaz.geministrator.queen.planningMayCall(
            com.hereliesaz.geministrator.queen.PlanningCaller.Reviewer, read.status, read.estimateUsd, caps, hourly, callsSoFar, room,
        )
    }

    /**
     * Holds the reviewer's ideas beyond the requirements in the improvement ledger as suggestions for the NEXT run (source QueenKept):
     * records only, never applied to the running plan. A failure is swallowed; the run goes on.
     */
    internal suspend fun keepPlanSuggestions(rows: List<com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal>) {
        val ledger = queenLedger ?: return
        if (rows.isEmpty()) return
        try {
            ledger.merge { state ->
                state.copy(proposals = state.proposals + rows.filter { row -> state.proposals.none { it.id == row.id } })
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The ideas are a courtesy to the next run; this one goes on.
        }
    }

    /** The ledger's rows as they stand, so ideas already kept are not written again; empty with no ledger. */
    internal suspend fun keptProposals(): List<com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal> =
        try {
            queenLedger?.state()?.proposals.orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }

    /** What the planner named as the signs that each run's hive is reached (memory only; the default signs are checked when none are kept). */
    private val hiveSignBook = LinkedHashMap<String, List<com.hereliesaz.geministrator.queen.HiveSign>>()

    internal fun rememberHiveSigns(runId: String, signs: List<com.hereliesaz.geministrator.queen.HiveSign>) {
        if (signs.isEmpty()) return
        hiveSignBook[runId] = signs
        while (hiveSignBook.size > 32) hiveSignBook.remove(hiveSignBook.keys.first())
    }

    /** What each run's hive still needs created or obtained (memory only; see queen/Blockers.kt). */
    private val blockerBook = LinkedHashMap<String, List<com.hereliesaz.geministrator.queen.Blocker>>()

    internal fun rememberBlockers(runId: String, blockers: List<com.hereliesaz.geministrator.queen.Blocker>) {
        if (blockers.isEmpty()) return
        blockerBook[runId] = blockers
        while (blockerBook.size > 32) blockerBook.remove(blockerBook.keys.first())
    }

    /** What she says about disputes she could not settle herself, per run (memory only; derived into Messages at each publish). */
    private val queenDisputes = LinkedHashMap<String, List<com.hereliesaz.geministrator.queen.QueenNote>>()

    internal fun rememberDisputeNotes(runId: String, notes: List<com.hereliesaz.geministrator.queen.QueenNote>) {
        if (notes.isEmpty()) return
        val known = queenDisputes[runId].orEmpty()
        queenDisputes[runId] = (known + notes).distinctBy { it.id }.takeLast(8)
        while (queenDisputes.size > 32) queenDisputes.remove(queenDisputes.keys.first())
    }

    /**
     * Her model and the creatures' voices for disputes; null until the app wires one, and she is then away: a dispute goes to
     * the keeper in Messages (see [queenDisputeLocked]).
     */
    internal var disputeModels: DisputeModels? = null

    /**
     * Builds her models for a dispute from the roles as they are now (see [com.hereliesaz.geministrator.queen.DisputeModelSource]); the app
     * sets it. Null, or a source that returns null, leaves her away. Wins over [disputeModels].
     */
    var disputeModelSource: com.hereliesaz.geministrator.queen.DisputeModelSource? = null

    /** Where an adopted specialist's lessons are banked (the memory layer's per-role bank); null where there is no memory layer. */
    internal var roleLessonBank: RoleLessonBank? = null

    // --- The training round driver's hooks (ApplicationRuntimeTrainingDriver.kt): her models, pacing, caps and the ledger. ---

    /** Training runs whose Nanny ruling passed and was offered to the keeper: the only ones that may ever be adopted. */
    internal val trainingOffered = linkedSetOf<String>()

    internal fun keeperHourlyCapUsd(): Double? = queenCaps().hourlySpendCapUsd

    internal suspend fun activeRunId(): String? = runtimeMutex.withLock { current?.state?.run?.id?.value }

    internal suspend fun trainingModels(): DisputeModels? = resolveDisputeModels()

    internal suspend fun republishState() {
        runtimeMutex.withLock { publishCurrent() }
    }

    /**
     * Runs [block] off the cycle as the queen's own work for [runId]: her calls keep the registry's per-Source pacing (a wait is
     * hers, bounded, never the keeper's) and a move to another Source goes into the Story of the run. A failure is swallowed.
     */
    internal fun launchQueenWork(runId: String, block: suspend () -> Unit) {
        val callContext = com.hereliesaz.geministrator.queen.QueenCallContext(
            pacing = providerRegistry.sourcePacing,
            onMoved = { from, to, why ->
                rememberDisputeNotes(runId, listOf(com.hereliesaz.geministrator.queen.sourceMovedNote(runId, from, to, why)))
                runtimeMutex.withLock { publishCurrent() }
            },
        )
        runtimeScope.launch(callContext) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The round's driver could not go on; the keeper still has the run and Messages.
            }
        }
    }

    /**
     * Carries what an ended run found to the next swarm: the Nanny's findings the queen did not use, and [extra] rows (given the
     * ledger's rows and the time). Nothing when the queen has no ledger. A failure is swallowed.
     */
    internal suspend fun carryEndedRunFindings(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        extra: (List<com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal>, Long) -> List<com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal>,
    ) {
        val ledger = queenLedger ?: return
        try {
            val now = nowEpochMillis()
            carryNannyFindings(ledger, definition, run, now)
            val more = extra(ledger.state().proposals, now)
            if (more.isNotEmpty()) {
                ledger.merge { state -> state.copy(proposals = state.proposals + more.filter { m -> state.proposals.none { it.id == m.id } }) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Carrying forward is a courtesy to the next swarm.
        }
    }

    /** The disputes already raised (run and report), so each is raised once, also after a restart. */
    private val raisedDisputes = com.hereliesaz.geministrator.queen.RaisedDisputes(
        load = { DurableUiState.store.getString(RAISED_DISPUTES_KEY) },
        save = { DurableUiState.store.putString(RAISED_DISPUTES_KEY, it) },
    )

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

    /** Serializes the keeper's accepts and dismissals of the queen's suggestions (apart from [runtimeMutex], which their side effects take). */
    private val queenDecisionMutex = Mutex()
    /** Non-null while the UI is viewing a historical run; cycling continues on [current]. */
    private var viewingRun: ViewingRun? = null
    val state: StateFlow<ApplicationRuntimeState> = publisher.state

    suspend fun loadLatest() {
        runtimeMutex.withLock {
            publisher.publish(ApplicationRuntimeState.Loading)
            try {
                val projects = persistence.projects.all()
                if (projects.isEmpty()) {
                    retireOutgoing(continuing = null)
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
                    retireOutgoing(continuing = null)
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
                val repairedDefinition = repairKnownNodeCreatureRigCycle(storedDefinition)
                if (repairedDefinition !== storedDefinition) {
                    persistence.definitions.put(repairedDefinition)
                }
                // The run follows the hive it started with, not a later save under the same id.
                val definition = run.definitionSnapshot?.let(::repairKnownNodeCreatureRigCycle) ?: repairedDefinition
                val runtimeState = try {
                    coordinator.resume(run.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw classifyResumeFailure(failure)
                }

                retireOutgoing(continuing = run.id)
                replaceCurrent(Current(project, definition, runtimeState))
                publishCurrent()
                startCycling()
            } catch (cancellation: CancellationException) {
                // A cancelled caller is not a failed load: what was showing keeps showing.
                restorePublishedState()
                throw cancellation
            } catch (failure: Throwable) {
                retireOutgoing(continuing = null)
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
            block(newestRun(snapshot.state.run))
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
            } catch (cancellation: CancellationException) {
                throw cancellation
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
        /** Where [objective] came from; a remote agent's request is not the keeper's own typing. */
        objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
    ) {
        runtimeMutex.withLock {
            val parents = requireKnownParentRuns(continuesWorkflowRunIds)
            val cleanProjectName = projectName.trim()
            val cleanObjective = objective.trim()
            require(cleanProjectName.isNotEmpty()) { "Project name is required" }
            require(cleanObjective.isNotEmpty()) { "Objective is required" }

            val normalizedRepository = repository?.normalized()
            val now = nowEpochMillis()
            val stamp = persistence.uniqueLaunchStamp(now, newProject = existingProject == null)
            val project = existingProject?.copy(
                name = cleanProjectName,
                repository = normalizedRepository ?: existingProject.repository,
                updatedAtEpochMillis = now,
            ) ?: Project(
                id = ProjectId("project-$stamp"),
                name = cleanProjectName,
                repository = normalizedRepository,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            val launchRoles = activeRoles(roles)
            val definition = StarterWorkflowFactory.create(
                id = WorkflowDefinitionId("workflow-$stamp"),
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
                workflowRunId = WorkflowRunId("run-$stamp"),
                objective = cleanObjective,
                nowEpochMillis = now,
                taskRunIdFactory = { id -> TaskRunId("run-$stamp-${id.value}") },
                parentWorkflowRunIds = parents,
                objectiveProvenance = objectiveProvenance,
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
            // Rewinding a run that is still live: it is cancelled (what had finished keeps its result and
            // carries over; what was running starts again in the new run), then forked. The request is
            // checked against what the run will be once cancelled first, so a refused request leaves the
            // live run running.
            var cancelledLive = false
            val live = current?.takeIf { it.state.run.id == sourceRunId && !it.state.run.status.isTerminal() }
            val stored = persistence.runs.get(sourceRunId)
                ?: error("Run ${sourceRunId.value} was not found")
            val storedProject = persistence.projects.get(stored.projectId)
                ?: error("Project ${stored.projectId.value} was not found")
            // A live run is followed by the definition it runs with, not by the saved copy, which a save may have changed.
            val definition = live?.definition
                ?: persistence.definitions.get(stored.workflowDefinitionId)
                ?: error("Workflow definition ${stored.workflowDefinitionId.value} was not found")
            if (live != null) {
                val newest = newestOf(live.state.run, stored)
                val previewAt = nowEpochMillis()
                TaskRerunPlanner.fork(
                    definition = definition,
                    source = cancelledRunView(newest, previewAt),
                    request = request,
                    workflowRunId = WorkflowRunId("run-$previewAt"),
                    nowEpochMillis = previewAt,
                    taskRunIdFactory = { id -> TaskRunId("run-$previewAt-${id.value}") },
                    editedDefinitionId = WorkflowDefinitionId("workflow-$previewAt"),
                )
                coordinator.cancel(live.project, live.definition, live.state, nowEpochMillis())
                cancelledLive = true
            }
            val source = persistence.runs.get(sourceRunId)
                ?: error("Run ${sourceRunId.value} was not found")
            val now = nowEpochMillis()
            val stamp = persistence.uniqueLaunchStamp(now, newProject = false)
            val fork = TaskRerunPlanner.fork(
                definition = definition,
                source = source,
                request = request,
                workflowRunId = WorkflowRunId("run-$stamp"),
                nowEpochMillis = now,
                taskRunIdFactory = { id -> TaskRunId("run-$stamp-${id.value}") },
                editedDefinitionId = WorkflowDefinitionId("workflow-$stamp"),
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

        // A decision never sorts before the run it decides, even if the clock stepped back (newestOf).
        val now = decisionTime(nowEpochMillis(), snapshot.state.run)
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
            // Built on the newest copy of the run, as every other decision is: a change someone else saved
            // since the last cycle is not written over.
            val run = newestRun(snapshot.state.run)
            val waiting = requireNotNull(run.taskRuns[taskDefinitionId]) { "Task ${taskDefinitionId.value} has no runtime state" }
            require(waiting.status == TaskRunStatus.AwaitingApproval) {
                "Task ${taskDefinitionId.value} is not awaiting approval"
            }
            // The plan gate opened for it when it began to wait is the same decision: it is decided here,
            // so it is not left Pending for a second approval that would have nothing to resume.
            val gateId = ApprovalGateId("plan:${run.id.value}:${taskDefinitionId.value}:${waiting.attempt}")
            persistence.approvalGates.get(gateId)
                ?.takeIf { it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying }
                ?.let {
                    ApprovalGateCoordinator(
                        repository = persistence.approvalGates,
                        eventSink = RepositoryWorkflowEventSink(persistence.events),
                    ).decide(
                        id = gateId,
                        approved = true,
                        decidedByRoleId = null,
                        note = "Approved in application",
                        nowEpochMillis = now,
                    )
                }
            WorkflowRuntimeState(
                run = engine.completeHumanApprovalTask(
                    definition = snapshot.definition,
                    run = run,
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
            val run = newestRun(snapshot.state.run)
            if (run.spendPause == null || run.status.isTerminal()) return@withLock
            val lifted = liftSpendPause(run, decisionTime(nowEpochMillis(), run))
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
            val run = newestRun(snapshot.state.run)
            if (run.status.isTerminal() || (run.keeperPause != null) == paused) return@withLock
            val now = decisionTime(nowEpochMillis(), run)
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
     * a creature that is running takes its time allowed alone (read each cycle, so a keeper can give a
     * slow one more time); a creature that is done, a creature's role, executor, condition and policies, and the
     * hive's shape stay as the run has them and change from the next run, as the saved copy in the
     * library: the runtime never writes its own copy over a saved one ([WorkflowRuntimeCoordinator.persist]).
     * Nothing is cancelled and nothing restarts. Returns how many creatures took an edit.
     */
    suspend fun applyLiveDefinitionEdit(edited: WorkflowDefinition): Int = runtimeMutex.withLock {
        val snapshot = current ?: return@withLock 0
        val run = newestRun(snapshot.state.run)
        if (snapshot.definition.id != edited.id || run.status.isTerminal()) return@withLock 0
        when (val merge = LiveDefinitionEdit.merge(snapshot.definition, edited, run)) {
            LiveEditMerge.Nothing -> return@withLock 0
            is LiveEditMerge.Rejected -> {
                appLog("Live edit", merge.reason)
                return@withLock 0
            }
            is LiveEditMerge.Merged -> {
                // The run records the hive it follows, so an edit it took survives a restart and a later
                // save of the library copy never changes what this run resumes against.
                val followed = run.copy(definitionSnapshot = merge.definition)
                persistence.runs.put(followed)
                replaceCurrent(snapshot.copy(definition = merge.definition, state = snapshot.state.copy(run = followed)))
                publishCurrent()
                merge.taken
            }
        }
    }

    /**
     * Adds the staffed steps [extension] to the run on screen after the keeper approved the plan (docs/Keepers-Room.md), for a swarm
     * that asked for it by having the plan-approval step ([com.hereliesaz.geministrator.orchestration.RunExtension.GATE_STEP_ID]).
     * Only a running swarm, not paused, frozen or stopped. The extension meets the validation any plan meets: the pure rules
     * of [com.hereliesaz.geministrator.orchestration.RunExtension.refusal] (every added step comes after the approval, none repeats an id),
     * `validateAgainst` through the factory that builds the steps, the requirements checklist's rules (a step that can change something
     * needs an approval before it), and the graph validator. A step already in the run is never changed. Returns null when the steps were
     * added, or why they were not (nothing is applied then). Never reachable from a remote interface: only the app calls it.
     */
    suspend fun extendRun(extension: List<com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep>): String? = runtimeMutex.withLock {
        val snapshot = current ?: return@withLock "No active workflow is loaded."
        val run = newestRun(snapshot.state.run)
        if (run.status != WorkflowRunStatus.Running) return@withLock "Only a swarm that is running can take more steps."
        if (run.globalPause != null || run.spendPause != null || run.keeperPause != null) return@withLock "The swarm is paused or stopped."
        val definition = snapshot.definition
        val currentSteps = com.hereliesaz.geministrator.orchestration.RunExtension.stepsOf(definition)
            ?: return@withLock "A step of this swarm has no role, so it cannot be extended."
        val gateApproved = run.taskRuns[TaskDefinitionId(com.hereliesaz.geministrator.orchestration.RunExtension.GATE_STEP_ID)]?.status == TaskRunStatus.Completed
        com.hereliesaz.geministrator.orchestration.RunExtension.refusal(currentSteps, extension, gateApproved)?.let { return@withLock it }
        val rolesNow = run.roleSnapshot.filter(RoleDefinition::enabled).ifEmpty { activeRoles(resolveRoleCollection(persistence.roles.all())) }
        val inputs = com.hereliesaz.geministrator.orchestration.RequirementInputs(
            objective = run.objective,
            objectiveExternal = run.objectiveProvenance.isUntrusted(),
            roles = rolesNow,
            availableRoleIds = rolesNow.map { it.id.value }.toSet(),
        )
        com.hereliesaz.geministrator.orchestration.RunExtension.introducedProblem(inputs, currentSteps, extension)?.let { return@withLock it }
        val packet = com.hereliesaz.geministrator.orchestration.OrchestrationPacket(
            objective = run.objective,
            currentState = "extend-run",
            availableAgents = rolesNow.map { role ->
                com.hereliesaz.geministrator.orchestration.OrchestrationRole(role.id.value, role.name, role.authorities.map { it.name }.sorted())
            },
            instruction = "Add the staffed steps to the run after the keeper approved the plan.",
        )
        val built = try {
            com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory.create(
                id = definition.id,
                objective = run.objective,
                plan = com.hereliesaz.geministrator.orchestration.OrchestrationPlan(currentSteps + extension),
                packet = packet,
                roles = rolesNow,
            )
        } catch (failure: IllegalArgumentException) {
            return@withLock failure.message ?: "The steps were not accepted."
        }
        val appended = com.hereliesaz.geministrator.orchestration.RunExtension.append(definition, built, extension.map { it.id }.toSet())
        val ready = when (appended) {
            is com.hereliesaz.geministrator.orchestration.RunExtension.Appended.Refused -> return@withLock appended.reason
            is com.hereliesaz.geministrator.orchestration.RunExtension.Appended.Ready -> appended
        }
        val now = decisionTime(nowEpochMillis(), run)
        val fresh = com.hereliesaz.geministrator.workflow.WorkflowRunFactory.create(
            definition = ready.definition,
            workflowRunId = run.id,
            projectId = run.projectId,
            objective = run.objective,
            nowEpochMillis = now,
            taskRunIdFactory = { id -> TaskRunId("${run.id.value}:${id.value}:extend") },
            roles = rolesNow,
            parentWorkflowRunIds = run.parentWorkflowRunIds,
            objectiveProvenance = run.objectiveProvenance,
        )
        // Every step already in the run keeps its own state; only the added steps take a new one.
        val taskRuns = fresh.taskRuns.mapValues { (id, added) -> run.taskRuns[id] ?: added }
        val followed = com.hereliesaz.geministrator.workflow.WorkflowRunFactory.refreshReadiness(
            definition = ready.definition,
            run = run.copy(taskRuns = taskRuns, definitionSnapshot = ready.definition, updatedAtEpochMillis = now),
            nowEpochMillis = now,
        )
        persistence.runs.put(followed)
        replaceCurrent(snapshot.copy(definition = ready.definition, state = snapshot.state.copy(run = followed)))
        publishCurrent()
        null
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

    /** What the screen showed before a load that was cancelled half way published Loading. */
    private fun restorePublishedState() {
        if (current != null) publishCurrent()
    }

    /** [live], or the saved copy when someone else wrote it since ([newestOf]). */
    private suspend fun newestRun(live: WorkflowRun): WorkflowRun = newestOf(live, persistence.runs.get(live.id))

    /**
     * The run being left by a switch must not keep spending where nothing watches it: only [current]
     * is cycled and held to the keeper's spend caps, so a live run that the next one does not continue
     * is cancelled (every unfinished task, its provider sessions and the remote work behind its system
     * steps), as a new launch cancels the run it replaces. Best effort: the switch still happens.
     */
    private suspend fun retireOutgoing(continuing: WorkflowRunId?) {
        val outgoing = current ?: return
        if (outgoing.state.run.id == continuing) return
        if (outgoing.state.run.status.isTerminal() && outgoing.state.handles.isEmpty()) return
        try {
            if (persistence.runs.get(outgoing.state.run.id) != null) {
                coordinator.cancel(outgoing.project, outgoing.definition, outgoing.state, nowEpochMillis())
            } else {
                // The stored runs were replaced (an import, a recovery): nothing to write back, but its sessions still bill.
                outgoing.state.handles.values.forEach { handle ->
                    try {
                        sessionGateway.cancel(handle)
                        sessionGateway.release(handle)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The run being left stays as persisted; the switch goes on.
        }
    }

    /**
     * The Hall Monitor escalation path, advanced once per cycle: a run already globally paused has its
     * Orchestrator review reconciled (the review session is started, followed and decided here); a run
     * that is not yet paused is paused once a Hall Monitor report has a passing Antagonist review. The
     * keeper's own review and the resume stay explicit ([decideHallMonitorUserReview], [resumeHallMonitorPause]).
     * A governance failure never stops the cycle: the run stays as persisted and is tried again.
     */
    private suspend fun governHallMonitorLocked(project: Project, state: WorkflowRuntimeState): WorkflowRuntimeState {
        if (state.run.status.isTerminal()) return state
        return try {
            val governance = HallMonitorGovernanceService(persistence)
            val now = nowEpochMillis()
            if (state.run.globalPause != null) {
                val reconciled = governance.reconcileOrchestratorReview(project, state.run, sessionGateway, now)
                if (reconciled === state.run) state else state.copy(run = reconciled)
            } else {
                governance.pauseIfApprovedReportReady(state, sessionGateway, now) ?: state
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            state
        }
    }

    /**
     * The queen's tick, in the same cycle: while a run is frozen, re-read its review (the Orchestrator's
     * verdict and the practice runs live in persistence) at most every two seconds. Everything else she
     * says is computed from the run when it is published. Never awaits a model; a failure is swallowed.
     */
    private suspend fun queenTickLocked(run: WorkflowRun) {
        val pause = run.globalPause
        if (pause == null) {
            queenReview = null
            return
        }
        val now = nowEpochMillis()
        val cached = queenReview
        if (cached != null && cached.runId == run.id.value && cached.reportId == pause.reportArtifactId.value &&
            now - cached.loadedAtEpochMillis < QUEEN_REVIEW_REFRESH_MILLIS
        ) return
        try {
            val loaded = loadHallMonitorPauseReviewState(run)
            queenReview = QueenReview(run.id.value, pause.reportArtifactId.value, now, loaded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            queenReview = QueenReview(run.id.value, pause.reportArtifactId.value, now, null)
        }
    }

    /**
     * The queen's suggestions, in the same cycle: reads her rows (at most every few seconds), settles those that no
     * longer stand, and makes at most one new one when the pacing rules allow. Deterministic: no model is asked, and a
     * failure is swallowed so the cycle goes on.
     */
    private suspend fun queenSuggestLocked(definition: WorkflowDefinition, run: WorkflowRun) {
        val ledger = queenLedger ?: return
        val now = nowEpochMillis()
        carryNannyFindings(ledger, definition, run, now)
        noteProgress(ledger, definition, run, now)
        if (now - queenProposalsReadAt < QUEEN_SUGGEST_REFRESH_MILLIS) return
        queenProposalsReadAt = now
        try {
            val signals = queenSignals(definition, run, roles, queenStalled[run.id.value])
            val candidates = queenCandidates(definition, run, signals, queenCaps(), roles) +
                com.hereliesaz.geministrator.queen.wordingCandidates(definition, run, queenWordings[run.id.value].orEmpty())
            launchWordingCheckpoint(definition, run, signals)
            val rows = ledger.state().proposals.filter { it.source == ImprovementSource.Queen }
            // Rows left over from another run are settled; a row for this run is settled once it no longer stands.
            for (row in rows.filter { it.status == ProposalStatus.Proposed }) {
                if (row.cycleRunId != run.id.value || queenIsMoot(row, definition, run, candidates)) {
                    ledger.decide(row.id) { it.copy(status = ProposalStatus.Superseded, decidedAtEpochMillis = now) }
                }
            }
            val fresh = ledger.state().proposals.filter { it.source == ImprovementSource.Queen }
            val made = queenNewProposals(definition, run, candidates, fresh, queenLevel(), now)
            if (made.isNotEmpty()) {
                ledger.merge { state ->
                    state.copy(proposals = state.proposals + made.filter { m -> state.proposals.none { it.id == m.id } })
                }
            }
            queenProposals = ledger.state().proposals.filter { it.source == ImprovementSource.Queen }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Her suggestions are a courtesy; the swarm goes on without them.
        }
    }

    /** Minutes each run has stood still (see [com.hereliesaz.geministrator.queen.stalledMinutes]), by run id; read when she speaks. */
    private val queenStalled = LinkedHashMap<String, Int>()

    /** Notes the run's progress in the ledger (when she first saw it) and keeps how long it has stood still. A failure is swallowed. */
    private suspend fun noteProgress(
        ledger: com.hereliesaz.geministrator.workflow.improvement.ImprovementStore,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ) {
        try {
            var sightings = ledger.state().queenSightings
            val updated = com.hereliesaz.geministrator.queen.observeProgress(sightings, definition, run, now)
            if (updated != sightings) {
                ledger.merge { state -> state.copy(queenSightings = com.hereliesaz.geministrator.queen.observeProgress(state.queenSightings, definition, run, now)) }
                sightings = updated
            }
            val minutes = com.hereliesaz.geministrator.queen.stalledMinutes(definition, run, sightings, now)
            if (minutes == null) queenStalled.remove(run.id.value) else queenStalled[run.id.value] = minutes
            while (queenStalled.size > 32) queenStalled.remove(queenStalled.keys.first())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Her sense of time is a courtesy; the swarm goes on.
        }
    }

    /** Rewordings her model wrote for steps not yet started, by run id; each is offered as a suggestion, never applied. */
    private val queenWordings = LinkedHashMap<String, List<com.hereliesaz.geministrator.queen.Wording>>()

    /** Per run: how many checkpoint calls were made, when the last was, and the checkpoints already taken (one call each). */
    private class WordingBook(var calls: Int = 0, var lastAtEpochMillis: Long? = null, val taken: MutableSet<String> = mutableSetOf())

    private val wordingBooks = LinkedHashMap<String, WordingBook>()
    private var wordingJob: kotlinx.coroutines.Job? = null

    /**
     * At a checkpoint (half done, or a second failure) asks her model, once, for new wording for the steps still to start.
     * Single-flight, off the cycle, through the keeper's Source choice and pacing (the same machinery as her disputes), never
     * for a goal from outside, and only when [com.hereliesaz.geministrator.queen.queenMayCall] allows. The result is kept as
     * candidates for her ordinary suggestion pacing; the keeper's tap applies it.
     */
    private suspend fun launchWordingCheckpoint(definition: WorkflowDefinition, run: WorkflowRun, signals: List<com.hereliesaz.geministrator.queen.QueenSignal>) {
        if (wordingJob?.isActive == true) return
        val checkpoint = com.hereliesaz.geministrator.queen.wordingCheckpoint(definition, run, signals) ?: return
        val runId = run.id.value
        val book = wordingBooks.getOrPut(runId) { WordingBook() }
        if (!book.taken.add(checkpoint.name)) return
        while (wordingBooks.size > 32) wordingBooks.remove(wordingBooks.keys.first())
        val now = nowEpochMillis()
        val caps = queenCaps()
        // Her model is resolved now, so the price check is of the Source she will really use (a pin, or any she may move to).
        val models = try {
            resolveDisputeModels()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (models == null) {
            book.taken.remove(checkpoint.name)
            return
        }
        // Fail closed: under any dollar cap her model must be priced (from the price table) and half the hourly cap must be left.
        val read = com.hereliesaz.geministrator.queen.priceRead(
            models.queen.uses,
            com.hereliesaz.geministrator.providers.ModelPriceTable.Default,
            com.hereliesaz.geministrator.queen.PlanningLimits.QUEEN_INPUT_TOKENS.toLong(),
            com.hereliesaz.geministrator.queen.PlanningLimits.QUEEN_OUTPUT_TOKENS.toLong(),
        )
        val hourly = if (caps.hourlySpendCapUsd != null) {
            com.hereliesaz.geministrator.workflow.hourlySpend(persistence, now, null, com.hereliesaz.geministrator.workflow.AmbientSpendLedger.Shared)
        } else {
            com.hereliesaz.geministrator.workflow.HourlySpend(0.0, false)
        }
        val allowed = com.hereliesaz.geministrator.queen.queenMayCall(
            kind = com.hereliesaz.geministrator.queen.QueenCallKind.Checkpoint,
            level = queenLevel(),
            price = read.status,
            estimateUsd = read.estimateUsd,
            caps = caps,
            hourly = hourly,
            checkpointCalls = book.calls,
            lastCheckpointAtEpochMillis = book.lastAtEpochMillis,
            now = now,
            frozenOrStopped = run.globalPause != null || run.spendPause != null || run.keeperPause != null,
            objectiveOutside = run.objectiveProvenance.isUntrusted(),
            creatureWaiting = com.hereliesaz.geministrator.queen.creatureWaitingForRoom(run, providerRegistry.keylessWithoutRoom()),
        )
        if (!allowed) {
            book.taken.remove(checkpoint.name)
            return
        }
        book.calls += 1
        book.lastAtEpochMillis = now
        val callContext = com.hereliesaz.geministrator.queen.QueenCallContext(pacing = providerRegistry.sourcePacing)
        val rolesNow = roles
        wordingJob = runtimeScope.launch(callContext) {
            try {
                val made = com.hereliesaz.geministrator.queen.generateWordings(models.queen, definition, run, rolesNow, checkpoint)
                if (made.isNotEmpty()) {
                    queenWordings[runId] = made
                    while (queenWordings.size > 32) queenWordings.remove(queenWordings.keys.first())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Her wording is a courtesy; the swarm goes on with the wording it has.
            }
        }
    }

    /** Runs whose unused Nanny findings are already in the ledger, so a finished run is read once. */
    private val nannyCarried = mutableSetOf<String>()

    /**
     * When a run has ended, the Nanny's findings the queen did not use are recorded in the improvement ledger (source
     * Nanny), which the next hive or swarm's planning reads. No model; a failure is swallowed.
     */
    private suspend fun carryNannyFindings(
        ledger: com.hereliesaz.geministrator.workflow.improvement.ImprovementStore,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
    ) {
        if (run.id.value in nannyCarried) return
        if (run.status != WorkflowRunStatus.Completed && run.status != WorkflowRunStatus.Failed && run.status != WorkflowRunStatus.Cancelled) return
        try {
            val carried = com.hereliesaz.geministrator.queen.nannyCarryForward(definition, run, ledger.state().proposals, now)
            if (carried.isNotEmpty()) {
                ledger.merge { state ->
                    state.copy(proposals = state.proposals + carried.filter { c -> state.proposals.none { it.id == c.id } })
                }
            }
            nannyCarried += run.id.value
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Carrying forward is a courtesy to the next run; this one goes on.
        }
    }

    /**
     * The one honest place two creatures are in dispute today: the Antagonist rejected the Nanny's report. Raised once per report,
     * off the cycle (her model calls and an investigation may take a while; the keeper's waiting is never counted). Nothing starts
     * until both creatures agree to her approach; with no model wired she is away and the keeper has it.
     */
    private fun queenDisputeLocked(definition: WorkflowDefinition, run: WorkflowRun) {
        val case = com.hereliesaz.geministrator.queen.nannyDispute(definition, run) ?: return
        if (!raisedDisputes.add(case.key)) return
        val runId = run.id.value
        // Her calls keep the registry's per-Source pacing (a wait is hers, bounded, and never the keeper's), and a move to another
        // Source goes into the Story of this run.
        val callContext = com.hereliesaz.geministrator.queen.QueenCallContext(
            pacing = providerRegistry.sourcePacing,
            onMoved = { from, to, why ->
                rememberDisputeNotes(runId, listOf(com.hereliesaz.geministrator.queen.sourceMovedNote(runId, from, to, why)))
                runtimeMutex.withLock { publishCurrent() }
            },
        )
        runtimeScope.launch(callContext) {
            try {
                val models = resolveDisputeModels()
                val researcher = models?.research
                val researchPass: (suspend (String) -> List<String>?)? =
                    if (researcher == null) null else { missing -> researcher.findings(missing, "dispute-$runId") }
                val investigator = com.hereliesaz.geministrator.queen.DisputeInvestigator(
                    research = researchPass,
                    test = { request -> launchDisputeTestSwarm(runId, case.dispute, request) },
                )
                raiseDispute(
                    runId, case.dispute, case.parties, models?.queen, models?.voices.orEmpty(), investigator,
                    findingsOf = investigator::findingsOf, mayCall = models?.mayCall ?: { true },
                    testFacts = { testRunId -> readBackTestReport(testRunId) },
                )
                runtimeMutex.withLock { publishCurrent() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // She could not raise it; the Nanny's own review and the keeper still stand.
            }
        }
    }

    /**
     * Her models now: from the app's source with the current roles, each call held to the keeper's hourly spend cap (a call the
     * cap leaves no room for fails, and the dispute goes to the keeper); else the fixed [disputeModels]; else none (she is away).
     */
    private suspend fun resolveDisputeModels(): DisputeModels? {
        val source = disputeModelSource ?: return disputeModels
        val roles = activeRoles(resolveRoleCollection(persistence.roles.all()))
        val built = source.models(roles) ?: return null
        val allows: suspend () -> Boolean = { queenCallAllowed() }
        val queen = com.hereliesaz.geministrator.queen.cappedModel(built.queen, allows)
        return DisputeModels(
            queen = queen,
            voices = built.voices.mapValues { (_, voice) -> com.hereliesaz.geministrator.queen.cappedModel(voice, allows) },
            research = built.research?.withFactModel(queen),
            mayCall = built.mayCall,
        )
    }

    /** True when the keeper's hourly spend cap leaves room for one more call of hers (also the tutorial's, see intro/TutorialLaunch.kt). */
    internal suspend fun queenCallAllowed(): Boolean {
        val cap = queenCaps().hourlySpendCapUsd
        return cap == null || com.hereliesaz.geministrator.queen.hourlyCapAllows(
            cap,
            com.hereliesaz.geministrator.workflow.hourlySpend(persistence, nowEpochMillis(), null, com.hereliesaz.geministrator.workflow.AmbientSpendLedger.Shared),
        )
    }

    /** What happened when the keeper accepted one of her suggestions. */
    internal enum class QueenAccept { Applied, NothingChanged }

    /**
     * The keeper accepts one of her suggestions. Only in the app (nothing remote or scripted reaches this). It
     * re-checks that the suggestion still stands, applies it with the call the keeper already has (a live edit of a
     * step that has not started, a rewind of one step and what follows it, the keeper's own pause), and only if that
     * took, marks it Adopted. A second tap, or a suggestion whose step has since started, changes nothing.
     */
    internal suspend fun acceptQueenSuggestion(proposalId: String): QueenAccept = queenDecisionMutex.withLock {
        val ledger = queenLedger ?: return@withLock QueenAccept.NothingChanged
        val proposal = ledger.state().proposals.firstOrNull {
            it.id == proposalId && it.source == ImprovementSource.Queen
        } ?: return@withLock QueenAccept.NothingChanged
        if (proposal.status != ProposalStatus.Proposed) return@withLock QueenAccept.NothingChanged
        val action = QueenAction.parse(proposal.action) ?: return@withLock QueenAccept.NothingChanged
        val seen = runtimeMutex.withLock {
            current?.let { snapshot -> snapshot.definition to newestRun(snapshot.state.run) }
        } ?: return@withLock QueenAccept.NothingChanged
        val (definition, run) = seen
        if (run.id.value != proposal.cycleRunId ||
            queenAcceptRefused(proposal, definition, run, roles, queenCandidates(definition, run, queenSignals(definition, run, roles, queenStalled[run.id.value]), queenCaps(), roles))
        ) return@withLock QueenAccept.NothingChanged
        // Claim the row first (a compare-and-set), so only one tap runs the side effect; give it back if that does not take.
        val now = nowEpochMillis()
        val claimed = ledger.decide(proposalId) {
            it.copy(
                status = ProposalStatus.Adopted,
                decidedAtEpochMillis = now,
                note = "The keeper accepted it.",
            )
        }
        if (!claimed) return@withLock QueenAccept.NothingChanged
        val took = try {
            when (action) {
                QueenAction.RewordStep -> {
                    val edited = definition.copy(
                        tasks = definition.tasks.map { if (it.id.value == proposal.target) it.copy(objective = proposal.newText) else it },
                    )
                    applyLiveDefinitionEdit(edited) > 0
                }
                QueenAction.RedoFromStep -> {
                    rewindToTask(TaskDefinitionId(proposal.target))
                    true
                }
                QueenAction.Rest -> {
                    setKeeperPause(true)
                    true
                }
                // A shape change is only kept for next time: an ordinary note in the Improvement flow, never applied to this swarm.
                QueenAction.AddCheck, QueenAction.SplitStep, QueenAction.ReorderSteps, QueenAction.AddApproval -> {
                    val kept = com.hereliesaz.geministrator.queen.keptForNextTime(proposal, now)
                    ledger.merge { state -> state.copy(proposals = state.proposals + if (state.proposals.any { it.id == kept.id }) emptyList() else listOf(kept)) }
                    true
                }
                else -> false
            }
        } catch (failure: Throwable) {
            ledger.decide(proposalId, ProposalStatus.Adopted) { it.copy(status = ProposalStatus.Proposed, decidedAtEpochMillis = null, note = null) }
            throw failure
        }
        if (!took) {
            ledger.decide(proposalId, ProposalStatus.Adopted) { it.copy(status = ProposalStatus.Proposed, decidedAtEpochMillis = null, note = null) }
            return@withLock QueenAccept.NothingChanged
        }
        refreshQueenProposals()
        QueenAccept.Applied
    }

    /** The keeper says no thanks. The same step and action are not offered again for two weeks. */
    internal suspend fun dismissQueenSuggestion(proposalId: String): Boolean = queenDecisionMutex.withLock {
        val ledger = queenLedger ?: return@withLock false
        val now = nowEpochMillis()
        val changed = ledger.decide(proposalId) {
            it.copy(
                status = ProposalStatus.Dismissed,
                decidedAtEpochMillis = now,
                note = "The keeper dismissed it.",
            )
        }
        refreshQueenProposals()
        changed
    }

    private suspend fun refreshQueenProposals() {
        val ledger = queenLedger ?: return
        queenProposals = ledger.state().proposals.filter { it.source == ImprovementSource.Queen }
        runtimeMutex.withLock { publishCurrent() }
    }

    private fun queenNotesFor(definition: WorkflowDefinition, run: WorkflowRun): List<com.hereliesaz.geministrator.queen.QueenNote> =
        try {
            val review = queenReview?.takeIf { it.runId == run.id.value }?.state
            com.hereliesaz.geministrator.queen.queenUpdate(
                definition, run, roles, queenLevel(), review, researchRecords[run.id.value],
                missing = queenMissing[run.id.value].orEmpty(),
                disputes = queenDisputes[run.id.value].orEmpty(),
                stalledForMinutes = queenStalled[run.id.value],
                hiveSigns = hiveSignBook[run.id.value].orEmpty(),
                blockers = blockerBook[run.id.value].orEmpty(),
                suggestions = if (run.globalPause == null && run.spendPause == null) {
                    queenSuggestionNotes(queenProposals, run)
                } else {
                    emptyList()
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
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
                    // A finished run is cycled only while a session of it could not be stopped yet.
                    if (snapshot.state.run.status.isTerminal() && snapshot.state.handles.isEmpty()) return@withLock

                    try {
                        val nextState = coordinator.cycle(
                            project = snapshot.project,
                            definition = snapshot.definition,
                            state = snapshot.state,
                            nowEpochMillis = nowEpochMillis(),
                            artifactIdFactory = ::artifactId,
                        )
                        if (currentGeneration != snapshotGeneration || current !== snapshot) return@withLock
                        val governed = governHallMonitorLocked(snapshot.project, nextState)
                        queenTickLocked(governed.run)
                        queenSuggestLocked(snapshot.definition, governed.run)
                        queenDisputeLocked(snapshot.definition, governed.run)
                        replaceCurrent(snapshot.copy(state = governed))
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
                        queen = queenNotesFor(viewing.definition, viewing.run),
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
                    queen = queenNotesFor(snapshot.definition, snapshot.state.run),
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
                    retireOutgoing(continuing = null)
                    replaceCurrent(null)
                    publisher.publish(ApplicationRuntimeState.NoRun(project = project, roles = roles))
                    return@withLock
                }

                val definition = persistence.definitionFor(run)
                    ?: error("Workflow definition ${run.workflowDefinitionId.value} was not found")
                val runtimeState = try {
                    coordinator.resume(run.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw classifyResumeFailure(failure)
                }
                retireOutgoing(continuing = run.id)
                replaceCurrent(Current(project, definition, runtimeState))
                publishCurrent()
                startCycling()
            } catch (cancellation: CancellationException) {
                restorePublishedState()
                throw cancellation
            } catch (failure: Throwable) {
                retireOutgoing(continuing = null)
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
        private const val QUEEN_REVIEW_REFRESH_MILLIS = 2_000L
        private const val QUEEN_SUGGEST_REFRESH_MILLIS = 5_000L
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
                failure.message ?: "Source runtime is unavailable",
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
            /** How much the queen tells the keeper in Messages (Keeper's room, This device); Quiet unless provided. */
            queenLevel: () -> com.hereliesaz.geministrator.queen.QueenLevel = { com.hereliesaz.geministrator.queen.QueenLevel.Quiet },
            /** Where the queen's suggestions are kept and decided (the improvement ledger); none unless provided. */
            queenLedger: com.hereliesaz.geministrator.workflow.improvement.ImprovementStore? = null,
            /** What this device's steps have taken, so the queen's Auto time allowance learns (Time allowed); keeps nothing unless provided. */
            deadlineHistory: com.hereliesaz.geministrator.workflow.DeadlineHistory =
                com.hereliesaz.geministrator.workflow.DeadlineHistory.None,
            /** Opt-in OTLP trace export of finished runs (docs/architecture/OBSERVABILITY.md); null exports nothing. */
            traceExport: com.hereliesaz.geministrator.telemetry.WorkflowTraceExport? = null,
            /** The keeper's approval habits (workflow/ProgressiveDelegation.kt); null leaves every gate asking. */
            delegations: ProgressiveDelegationStore? = null,
            /**
             * Where the runtime's own work (the cycle that polls sessions and persists runs) runs. The app passes
             * `Dispatchers.Default` so it is not on the UI thread; null keeps [scope]'s dispatcher, which is what a
             * test's scheduler needs to stay deterministic.
             */
            runtimeDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
        ): ApplicationRuntime {
            val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
            val runtimeScope = runtimeScopeOf(scope, runtimeJob, runtimeDispatcher)
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
                    deadlineAllowance = { task, taskRun ->
                        val provider = taskRun.assignedProviderId?.let(registry::provider)
                        val kind = com.hereliesaz.geministrator.workflow.DeadlinePolicy
                            .kindOf(taskRun.executor ?: task.executor, provider?.workKind)
                        com.hereliesaz.geministrator.workflow.DeadlinePolicy.resolveAuto(
                            kind = kind,
                            size = com.hereliesaz.geministrator.workflow.StepSize(
                                contextTokens = task.objective.length / 4,
                                upstreamArtifacts = task.dependsOn.size,
                            ),
                            source = com.hereliesaz.geministrator.workflow.SourceTrait(keyless = provider?.keyless == true),
                            history = deadlineHistory.samples(kind, taskRun.assignedProviderId?.value, task.id.value),
                        )
                    },
                    deadlineSample = { task, taskRun, activeMillis ->
                        val provider = taskRun.assignedProviderId?.let(registry::provider)
                        val kind = com.hereliesaz.geministrator.workflow.DeadlinePolicy
                            .kindOf(taskRun.executor ?: task.executor, provider?.workKind)
                        if (com.hereliesaz.geministrator.workflow.DeadlinePolicy.isTimed(kind)) {
                            deadlineHistory.record(kind, activeMillis, taskRun.assignedProviderId?.value, task.id.value)
                        }
                    },
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
                    queenLevel,
                    queenLedger,
                    spendCaps,
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


/** [scope]'s context under [job], on [dispatcher] when one is given. */
internal fun runtimeScopeOf(
    scope: CoroutineScope,
    job: Job,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher?,
): CoroutineScope =
    CoroutineScope(scope.coroutineContext + job + (dispatcher ?: kotlin.coroutines.EmptyCoroutineContext))

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

/** Where the keys of raised disputes are kept (the app's durable UI state). */
private const val RAISED_DISPUTES_KEY = "queen.disputes.raised"

/**
 * The parents of a run the user launches as a continuation (one earlier run) or a merge (several):
 * each must be a stored run. Duplicates collapse; order is kept (the first is the primary parent).
 */
internal suspend fun ApplicationRuntime.requireKnownParentRuns(parents: List<WorkflowRunId>): List<WorkflowRunId> =
    parents.distinct().onEach { parent ->
        requireNotNull(persistence.runs.get(parent)) { "Workflow run ${parent.value} to continue was not found" }
    }
