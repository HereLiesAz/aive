package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.keeperName
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.workflow.NodeCharacterGenerationWorkflowFactory
import com.hereliesaz.geministrator.workflow.SettingsNodeCharacterAssetStore
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.improvement.CycleIngest
import com.hereliesaz.geministrator.workflow.improvement.LoopIntegrity
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import com.hereliesaz.geministrator.workflow.improvement.SettingsImprovementStore
import com.hereliesaz.geministrator.workflow.improvement.SwarmEvidence
import com.hereliesaz.geministrator.workflow.schedule.ScheduleTicker
import com.hereliesaz.geministrator.workflow.schedule.objectiveProvenance
import com.hereliesaz.geministrator.workflow.schedule.SettingsWorkflowScheduleStore
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.orchestration.PlanningPhase
import com.hereliesaz.geministrator.orchestration.PlanningStage
import com.hereliesaz.geministrator.orchestration.PlanningStages
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.RoleSurfaceRuntimeRegistry
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.duplex.DuplexSessionAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LaunchProgress
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.app_approval_failed
import com.hereliesaz.geministrator.resources.app_done
import com.hereliesaz.geministrator.resources.app_done_approved
import com.hereliesaz.geministrator.resources.app_done_paused
import com.hereliesaz.geministrator.resources.app_done_resumed
import com.hereliesaz.geministrator.resources.app_done_spend_stop_lifted
import com.hereliesaz.geministrator.resources.app_unreachable
import com.hereliesaz.geministrator.resources.queen_suggest_nothing_changed
import com.hereliesaz.geministrator.resources.app_clear_failed
import com.hereliesaz.geministrator.resources.app_escalation_decision_failed
import com.hereliesaz.geministrator.resources.app_import_failed
import com.hereliesaz.geministrator.resources.app_plan_rejection_failed
import com.hereliesaz.geministrator.resources.app_lift_spend_stop_failed
import com.hereliesaz.geministrator.resources.app_stop_run_failed
import com.hereliesaz.geministrator.resources.app_pause_run_failed
import com.hereliesaz.geministrator.resources.app_provider_message_failed
import com.hereliesaz.geministrator.resources.app_reachable_capabilities
import com.hereliesaz.geministrator.resources.app_recovery_failed
import com.hereliesaz.geministrator.resources.app_reset_swarm_failed
import com.hereliesaz.geministrator.resources.app_retry_failed
import com.hereliesaz.geministrator.resources.app_role_delegation_failed
import com.hereliesaz.geministrator.resources.app_runtime_bootstrap_failed
import com.hereliesaz.geministrator.resources.app_rerun_failed
import com.hereliesaz.geministrator.resources.app_runtime_is_unavailable
import com.hereliesaz.geministrator.resources.app_save_swarm_failed
import com.hereliesaz.geministrator.resources.app_switch_run_failed
import com.hereliesaz.geministrator.resources.app_task_delegation_failed
import com.hereliesaz.geministrator.resources.app_workflow_delegation_failed
import com.hereliesaz.geministrator.resources.app_workflow_launch_failed
import com.hereliesaz.geministrator.resources.app_launch_already_in_progress
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(
    /** Buying breeds from the platform store; null where there is none (prices show, Buy doesn't). */
    breedPurchases: BreedPurchases? = null,
    /** Entire on the desktop (docs/Entire.md); null elsewhere. */
    entire: EntireIntegration? = null,
    providers: Collection<AgentProvider>,
    /** Bound only to an explicitly selected task; default stays disconnected. */
    duplexAttachment: DuplexSessionAttachment? = null,
    executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
    roleSurfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
    orchestrationRuntime: OrchestrationAgentRuntime? = null,
    /** Reviews the planner's plan before a run exists, with the keeper's final say (orchestration/PlanReview.kt); null skips review. */
    planReviewer: PlanReviewer? = null,
    /**
     * The queen's own model for disputes, from the keeper's linked Sources: given the Source the keeper pinned on her role (or null),
     * it returns a model, or null when none is usable and she is away. A pinned Source never switches silently. The Nanny's and the
     * Antagonist's voices use the same model with their own role text.
     */
    queenModelFor: ((pinnedProviderId: String?) -> com.hereliesaz.geministrator.queen.QueenModel?)? = null,
    persistence: WorkflowPersistence? = null,
    projectFileService: ProjectFileService? = null,
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (suspend () -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    onConfigureRepositoryService: (String) -> Unit = {},
    onDisconnectRepositoryService: (String) -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    /** Links a keyless provider at once, with no key (the feed screen's one-tap free start). */
    onConnectFreeProvider: ((String) -> Unit)? = null,
    /** The image-only Sources linked ([ProviderCatalog.linkedImageProviderIds]): run by an executor, so not among [providers], yet connected. */
    linkedImageProviderIds: Set<String> = emptySet(),
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    onRevokeComputeNode: (String) -> Unit = {},
    distributedComputeAvailable: Boolean = true,
    crashReportingSetting: CrashReportingSetting? = null,
    remoteAccessSetting: RemoteAccessSetting? = null,
    platformLimits: PlatformLimits = PlatformLimits(),
    /** The desktop's A2A server switch (docs/A2A.md); null where there is none. */
    a2aServerSetting: A2aServerSetting? = null,
    localPlannerSetting: LocalPlannerSetting? = null,
    localOrchestrationSpecialistSetting: LocalOrchestrationSpecialistSetting? = null,
    onDeviceSmallTaskSetting: OnDeviceSmallTaskSetting? = null,
    memoryLayer: com.hereliesaz.geministrator.memory.MemoryLayerController? = null,
    orchestrationUtilities: LocalOrchestrationUtilityFamily = DeterministicLocalOrchestrationUtilities,
    /** Extra chunk sources (a connected repository) retrieved from beside upstream artifacts. */
    contextChunkSources: List<com.hereliesaz.geministrator.retrieval.ContextChunkSource> = emptyList(),
    /** Workflows another app asked for, oldest first; each is launched once, then handed back. */
    externalWorkflowLaunchRequests: List<ExternalWorkflowLaunchRequest> = emptyList(),
    /**
     * Called once per request when its launch has finished: with null after the workflow started,
     * or with a user-facing reason when it could not. Not called when the launch is cancelled
     * (the UI went away), so the request stays queued and is launched again.
     */
    onExternalWorkflowLaunchHandled: (requestId: Long, failure: String?) -> Unit = { _, _ -> },
    /** Every runtime state, for observers outside the UI (the Android remote service). */
    onRuntimeStateChanged: (ApplicationRuntimeState) -> Unit = {},
    /**
     * When set, the shown run's event timeline after each runtime state change, for observers
     * outside the UI (Android remote clients watching run events). Null, the default, loads nothing.
     */
    onRunTimelineChanged: ((List<com.hereliesaz.geministrator.events.WorkflowEvent>) -> Unit)? = null,
    /** The runtime once it exists (null while it is replaced), for hosts outside the UI (the desktop A2A server). */
    onRuntimeAvailable: (ApplicationRuntime?) -> Unit = {},
    /** The linked planner's swarm draft (null while there is none), for hosts outside the UI (the desktop control API, docs/MCP.md). */
    onSwarmDrafterAvailable: ((suspend (String) -> com.hereliesaz.geministrator.control.ControlDraftOutcome)?) -> Unit = {},
    /** Credential stores for the setup file; null leaves credentials out of it. */
    setupSecretStore: SetupSecretStore? = null,
    /** The MCP host and its approval gate (docs/architecture/MCP_HOST.md); null where there is none. */
    mcpHost: com.hereliesaz.geministrator.mcp.McpHost? = null,
    mcpConsent: com.hereliesaz.geministrator.mcp.PromptingMcpConsentGate? = null,
    /** The script sandbox over [mcpHost], where the platform has one (desktop); its opt-in is in Connections. */
    codeExecution: com.hereliesaz.geministrator.mcp.CodeExecutionToolSource? = null,
    /** Where MCP servers' requests for input are shown; null where no keeper can be asked. */
    mcpElicitation: com.hereliesaz.geministrator.mcp.PromptingMcpElicitationGate? = null,
    /** Whether the keeper turned the app's own control interface on; the queen reads its capability names only then. */
    researchBridgeEnabled: () -> Boolean = { false },
) {
    val scope = rememberCoroutineScope()
    val workflowPersistence = remember(persistence) { persistence ?: com.hereliesaz.geministrator.persistence.WorkflowPersistenceFactory.durable }
    // The keeper's spend caps (Keeper's room, This device), read by the runtime every cycle.
    val spendCapStore = remember { SpendCapStore(com.russhwolf.settings.Settings()) }
    // Publishes the keeper's budget-class numbers for the roles that name a class (BudgetClassStore.kt).
    remember { BudgetClassStore(com.russhwolf.settings.Settings()) }
    val queenLevelStore = remember { QueenLevelStore(com.russhwolf.settings.Settings()) }
    // The one improvement ledger: the queen's suggestions are rows of it, decided in Messages.
    val improvementStore = remember { SettingsImprovementStore() }
    val deadlineHistory = remember {
        com.hereliesaz.geministrator.workflow.DeadlineHistory(com.russhwolf.settings.Settings())
    }
    // A reviewed plan waiting for the keeper, kept across a restart; outside workflow persistence and every export.
    val planStore = remember { com.hereliesaz.geministrator.orchestration.ReviewedPlanStore(com.russhwolf.settings.Settings()) }
    // The keeper's approval habits, on this device only (workflow/ProgressiveDelegation.kt).
    val delegations = remember { com.hereliesaz.geministrator.workflow.ProgressiveDelegationStore.createDefault() }
    var runtimeState by remember { mutableStateOf<ApplicationRuntimeState>(ApplicationRuntimeState.Loading) }
    var runtime by remember { mutableStateOf<ApplicationRuntime?>(null) }
    var runtimeGeneration by remember { mutableStateOf(0) }
    var launching by remember { mutableStateOf(false) }
    // Why the last launch did not start. It stays beside Feed it with what was typed, rather than
    // replacing the screen with a runtime failure (Conveyance: one element carries an act to its end).
    var launchError by remember { mutableStateOf<String?>(null) }
    // A press (Approve, Stop, Pause...) that did not go through. It is said once, on the screen, and dismissed:
    // it never replaces the hive's own state, which a failed button press says nothing about.
    var actionFailure by remember { mutableStateOf<ActionFailure?>(null) }
    // One line, said once and cleared by itself, after a keeper's decision went through (the failures above stay until dismissed).
    var actionDone by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actionDone) {
        if (actionDone != null) {
            delay(4_000)
            actionDone = null
        }
    }
    // A Source the keeper switched off stays connected (its key is kept) but is used by nothing: the runtime never sees it.
    val enabledProviders = providers.filter { ProviderEnablement.isEnabled(it.id.value) }
    val providerReach by produceState(ProviderReach.Unknown, enabledProviders) { value = providerReach(enabledProviders) }
    val launchSteps = remember { LiveStepsFeedModel(maxRows = 5) }
    val launchStepRows by launchSteps.rows.collectAsState()
    // A reviewed plan waiting on the keeper's final say: the run does not exist until it is answered.
    var planDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    // Where the planning of a reviewed launch has got to: the field shows the planner's egg, the reviewer, the first step.
    var planningStage by remember { mutableStateOf<PlanningStage?>(null) }
    val reduceMotion = AzphaltMotion.reduceMotion
    var projectIdToOpenAfterReload by remember { mutableStateOf<String?>(null) }
    var automaticProjectRestoreAttempted by remember(projectFileService) { mutableStateOf(false) }
    // The app's own bundled docs, read once. Offered to a creature only when the queen gave its task an AiveDocs resource.
    val aiveDocsSource = remember { com.hereliesaz.geministrator.retrieval.AiveDocsChunkSource() }

    LaunchedEffect(enabledProviders, duplexAttachment, executorIntegrations, roleSurfaceRuntime, workflowPersistence, orchestrationUtilities, contextChunkSources, runtimeGeneration) {
        runtime?.close()
        runtime = null
        runtimeState = ApplicationRuntimeState.Loading
        try {
            val created = ApplicationRuntime.create(
                providers = enabledProviders,
                scope = scope,
                duplexAttachment = duplexAttachment,
                persistence = workflowPersistence,
                executorIntegrations = executorIntegrations,
                roleSurfaceRuntime = roleSurfaceRuntime,
                orchestrationUtilities = orchestrationUtilities,
                contextRetriever = com.hereliesaz.geministrator.retrieval.SimilarityContextRetriever(
                    // A task's resources (assigned by the queen) narrow or add to these; a task with none gets what it always did.
                    listOf(com.hereliesaz.geministrator.retrieval.ArtifactChunkSource, aiveDocsSource) +
                        contextChunkSources.map { com.hereliesaz.geministrator.retrieval.RepositoryFilesNarrowedSource(it) },
                ),
                spendCaps = { spendCapStore.caps },
                queenLevel = { queenLevelStore.level },
                queenLedger = improvementStore,
                deadlineHistory = deadlineHistory,
                traceExport = com.hereliesaz.geministrator.telemetry.WorkflowTraceExport.durable,
                delegations = delegations,
                habitReplay = HabitReplayHost.planner,
                habitLearningOn = { com.hereliesaz.geministrator.memory.MemoryRuntimeBridge.habitLearningOn() },
                // The runtime's cycle and its saves are not UI work.
                runtimeDispatcher = kotlinx.coroutines.Dispatchers.Default,
            )
            projectIdToOpenAfterReload?.let { projectId ->
                created.openProject(com.hereliesaz.geministrator.domain.ProjectId(projectId))
                projectIdToOpenAfterReload = null
            }
            runtime = created
            created.state.collectLatest { runtimeState = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_runtime_bootstrap_failed))
        }
    }

    LaunchedEffect(runtimeState, projectFileService, automaticProjectRestoreAttempted) {
        if (automaticProjectRestoreAttempted) return@LaunchedEffect
        if (runtimeState is ApplicationRuntimeState.Loading) return@LaunchedEffect
        if (runtimeState !is ApplicationRuntimeState.NoProject) {
            automaticProjectRestoreAttempted = true
            return@LaunchedEffect
        }
        val fileService = projectFileService ?: run {
            automaticProjectRestoreAttempted = true
            return@LaunchedEffect
        }
        automaticProjectRestoreAttempted = true

        for (descriptor in runCatching { fileService.detected() }.getOrDefault(emptyList())) {
            val opened = runCatching { fileService.read(descriptor) }.getOrNull() ?: continue
            val imported = runCatching { runtime?.importProjectFile(opened.content) }.getOrNull() ?: continue
            if (imported != null) {
                projectIdToOpenAfterReload = imported.id.value
                runtimeGeneration += 1
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runtime?.close()
            runtime = null
        }
    }

    // The keeper's stable (docs/Node-Creature-Genome.md, Breeds): which creature each agent wears.
    val stableHost = remember { StableHost() }
    LaunchedEffect(stableHost, breedPurchases) {
        // Breeds paid for while the app was closed join the stable now, once the catalog is known.
        if (!stableHost.load() || breedPurchases == null) return@LaunchedEffect
        val paid = try {
            breedPurchases.unclaimed()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            emptyList()
        }
        stableHost.claim(breedPurchases, paid)
    }

    // Breeding (docs/Swarm-OS.md step 6): when the live run clears, it hatches one config from the
    // agents that did its work, kept unadopted in the roster. The child's id is the run's, so a
    // run hatches once however often this effect runs.
    val liveRun = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    LaunchedEffect(liveRun?.run?.id, liveRun?.run?.status) {
        val presentation = liveRun ?: return@LaunchedEffect
        // Each cleared run counts toward the stable's next generation (counted once per run).
        stableHost.runCleared(presentation.run)
        if (presentation.run.status != com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed) return@LaunchedEffect
        val target = runtime ?: return@LaunchedEffect
        try {
            // Catalog genera are evidence of assigned breed lineage, never inferred from agent names or hue.
            val genera = presentation.roles.mapNotNull { role ->
                val key = stableHost.stable.creatureOf(role.id) ?: return@mapNotNull null
                stableHost.catalog[key]?.genus?.let { role.id to it }
            }.toMap()
            val child = target.hatchWithEcho(presentation.run, presentation.roles, genera) ?: return@LaunchedEffect
            if (target.persistence.roles.all().none { it.id == child.id }) target.saveRole(child)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Hatching is best effort; a failed hatch never disturbs the run.
        }
    }

    // Apoptosis (docs/Node-Creature-Genome.md, Survival): a hatched config whose trophic reserve is spent is retired
    // (disabled, never deleted) once a run settles. Off by default; no control for it yet, so the key is the switch.
    val pruneStarved = rememberDurableBooleanState("trophic.prune").value
    LaunchedEffect(liveRun?.run?.id, liveRun?.run?.status, pruneStarved) {
        val presentation = liveRun ?: return@LaunchedEffect
        if (!pruneStarved || presentation.run.status !in setOf(
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed,
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Failed,
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Cancelled)) return@LaunchedEffect
        try {
            runtime?.pruneStarvedRoles()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Pruning is best effort; a failure leaves the roster as it was.
        }
    }

    // The reviewer as pollinator (docs/Node-Creature-Genome.md): once a run settles, each review a reviewer has made in
    // this workflow has its child, born unadopted to the reviewer and every producer it has reviewed so far.
    LaunchedEffect(liveRun?.run?.id, liveRun?.run?.status) {
        val presentation = liveRun ?: return@LaunchedEffect
        if (presentation.run.status !in setOf(
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed,
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Failed,
                com.hereliesaz.geministrator.domain.WorkflowRunStatus.Cancelled)) return@LaunchedEffect
        val target = runtime ?: return@LaunchedEffect
        try {
            val known = target.persistence.roles.all().mapTo(mutableSetOf()) { it.id }
            target.pollinatedChildren(presentation.run.workflowDefinitionId).filter { it.id !in known }.forEach { target.saveRole(it) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Pollination is best effort; a failure leaves the roster as it was.
        }
    }

    // Self-updating procedural memory (docs/architecture/PROCEDURAL_MEMORY.md §10): the live run's
    // failures, fixes and executor receipts redraft proposed edits of its roles' instructions. This
    // only queues proposals on each creature's Mind tab; a role changes only when the keeper approves.
    LaunchedEffect(liveRun?.run?.id, liveRun?.run?.updatedAtEpochMillis) {
        val presentation = liveRun ?: return@LaunchedEffect
        val target = runtime ?: return@LaunchedEffect
        // Settle first: a busy run restarts this effect, so its history is read once it pauses.
        kotlinx.coroutines.delay(1_500)
        try {
            val events = target.persistence.events.forRun(presentation.run.id)
            val traces = com.hereliesaz.geministrator.memory.MemoryRuntimeBridge.procedureTraceReader
                .traces(presentation.run.id.value)
            RoleInstructionProposalsHost.ledger.observe(
                com.hereliesaz.geministrator.memory.procedural.RoleInstructionObservations.fromRun(presentation.run, events, traces),
                presentation.roles,
                presentation.run.updatedAtEpochMillis,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Proposals are best effort; reading history never disturbs the run.
        }
    }

    // The queen's research before planning: what is already connected here, then at most one question the keeper may decline.
    val researchBridgeOn = researchBridgeEnabled()
    val researchServers = mcpHost?.servers?.collectAsState()?.value.orEmpty()
    val researchSettings = remember { com.hereliesaz.geministrator.research.ResearchSettings() }
    val researchOn by researchSettings.enabled.collectAsState()
    // The repository the keeper is launching against, read by the docs lead when research runs.
    val researchRepository = remember { arrayOfNulls<RepositoryRef>(1) }
    val researchDocNames = remember(contextChunkSources) {
        com.hereliesaz.geministrator.research.ChunkSourceDocNames(contextChunkSources) { researchRepository[0] }
    }
    // Gemini in Google Search, when the keeper added it as a Source and left it on (providers/llm GoogleAiMode).
    val aiModeAnswerer = com.hereliesaz.geministrator.research.GoogleAiModeResearch.answerer
        ?.takeIf { enabledProviders.any { it.id.value == ProviderCatalog.GOOGLE_AI_MODE_ID } }
    val researchStep = remember(runtime, mcpHost, mcpConsent, researchBridgeOn, researchServers.isNotEmpty(), researchOn, researchDocNames, aiModeAnswerer) {
        if (!researchOn) return@remember null
        com.hereliesaz.geministrator.research.ResearchStep(
            local = buildList {
                if (contextChunkSources.isNotEmpty()) {
                    add(com.hereliesaz.geministrator.research.ResearchSource.Repository to com.hereliesaz.geministrator.research.RepositoryDocsResearch(researchDocNames::names))
                }
                if (mcpHost != null && researchServers.isNotEmpty()) {
                    add(com.hereliesaz.geministrator.research.ResearchSource.ConnectedServer to com.hereliesaz.geministrator.research.ConnectedServersResearch(mcpHost))
                }
                if (researchBridgeOn) {
                    add(
                        com.hereliesaz.geministrator.research.ResearchSource.AppBridge to com.hereliesaz.geministrator.research.AppBridgeResearch(
                            { true },
                            listOf("status", "hives", "roles", "providers", "runs", "settings"),
                        ),
                    )
                }
            },
            candidates = if (mcpConsent == null) {
                emptyList()
            } else {
                listOfNotNull(
                    aiModeAnswerer?.let { answerer ->
                        com.hereliesaz.geministrator.research.ResearchCandidate(
                            source = com.hereliesaz.geministrator.research.ResearchSource.GoogleAiMode,
                            serverId = com.hereliesaz.geministrator.research.GoogleAiModeResearch.SERVER_ID,
                            serverName = "Google AI Mode",
                            serverLocation = com.hereliesaz.geministrator.research.GoogleAiModeResearch.LOCATION,
                            toolName = "ask",
                            lookup = com.hereliesaz.geministrator.research.GoogleAiModeLookup(answerer),
                            preview = com.hereliesaz.geministrator.research.GoogleAiModeResearch::question,
                        )
                    },
                    com.hereliesaz.geministrator.research.ResearchCandidate(
                        source = com.hereliesaz.geministrator.research.ResearchSource.SearchA2A,
                        serverId = "research-searcha2a",
                        serverName = "SearchA2A directory",
                        serverLocation = com.hereliesaz.geministrator.research.SearchA2aMcpLookup.ENDPOINT,
                        toolName = "search_agents",
                        lookup = com.hereliesaz.geministrator.research.SearchA2aMcpLookup({
                            com.hereliesaz.geministrator.mcp.McpStreamableHttpTransport(
                                com.hereliesaz.geministrator.a2a.sharedExternalDiscoveryClient,
                                com.hereliesaz.geministrator.research.SearchA2aMcpLookup.ENDPOINT,
                            )
                        }),
                    ),
                )
            },
            consent = mcpConsent ?: com.hereliesaz.geministrator.mcp.McpConsentGate.DenyUnasked,
            appDocs = com.hereliesaz.geministrator.research.AiveDocsResearch(aiveDocsSource),
            observeOutcome = { objective, runKey, outcome, budget ->
                runtime?.collectiveEvidence?.recordResearch(objective, runKey, outcome, budget)
            },
        )
    }
    val researchContext = com.hereliesaz.geministrator.research.ResearchContext(
        connectedServers = researchServers.size,
        bridgeEnabled = researchBridgeOn,
    )

    // Where an adopted specialist's lessons are banked: the memory layer's per-role bank.
    LaunchedEffect(runtime, memoryLayer) { runtime?.attachRoleLessonBank(memoryLayer) }

    // Her models for disputes between creatures (queen/Disputes.kt): the keeper's linked Sources, the research she may commission.
    LaunchedEffect(runtime, researchStep, researchContext, queenModelFor) {
        val active = runtime ?: return@LaunchedEffect
        val modelFor = queenModelFor
        active.collectiveModelFor = modelFor
        active.disputeModelSource = if (modelFor == null) {
            null
        } else {
            com.hereliesaz.geministrator.queen.DisputeModelSource { roles ->
                // The lesson is taught by a queen a model runs: one who runs as code is no teacher (keeper ruling).
                val base = if (queenRunsOnModel(roles)) modelFor(com.hereliesaz.geministrator.queen.orchestratorPin(roles)) else null
                if (base == null) {
                    null
                } else {
                    DisputeModels(
                        queen = base,
                        voices = com.hereliesaz.geministrator.queen.disputeVoices(base, roles),
                        research = researchStep?.let { step ->
                            com.hereliesaz.geministrator.research.ResearchInvestigation(
                                step, { researchContext },
                                observeClaims = active.collectiveEvidence::recordResearchClaims,
                            )
                        },
                    )
                }
            }
        }
    }

    // The keeper's answer to a reviewed plan: shown over the planning field, held until they press Start or Do not start.
    val askKeeper: suspend (com.hereliesaz.geministrator.orchestration.PlanDecision) -> Boolean = { decision ->
        val answer = CompletableDeferred<Boolean>()
        planDecision = PendingPlanDecision(decision, answer)
        try {
            answer.await()
        } finally {
            planDecision = null
        }
    }

    /**
     * Runs [body] under the launch guard and overlay; ignored while a launch is in progress. [onFinished]
     * receives null once it finished, or the failure; it is not called when the launch is cancelled.
     */
    fun runLaunch(onFinished: (Throwable?) -> Unit = {}, body: suspend (ApplicationRuntime) -> Unit) {
        if (launching) {
            appLog("AiveLaunch", "Launch ignored: a launch is already in progress")
            return
        }
        launching = true
        launchError = null
        launchSteps.clear()
        scope.launch(LaunchProgress(launchSteps::push) + PlanningStages { planningStage = it }) {
            try {
                appLog(
                    "AiveLaunch",
                    "Launch started: orchestrated=${orchestrationRuntime != null} runtime=${runtime != null}",
                )
                body(runtime ?: error(getString(Res.string.app_runtime_is_unavailable)))
                // The first step's egg hatches as the run starts; hold the field long enough to see it.
                if (planningStage?.phase == PlanningPhase.Started && !reduceMotion) delay(HATCH_HOLD_MILLIS)
                onFinished(null)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // Catch Throwable, not Exception: on-device model failures such as
                // OutOfMemoryError are Errors and previously escaped this handler,
                // leaving the launch button silently doing nothing.
                appLog("AiveLaunch", "Launch failed: ${failure::class.simpleName}: ${failure.message}")
                val raw = failure.message?.takeIf(String::isNotBlank)
                launchError = if (raw == null) {
                    getString(Res.string.app_workflow_launch_failed)
                } else {
                    // Said plainly; the raw text rides behind it for Technical details (packFailureText).
                    packFailureText(plainFailureLineOutsideComposition(raw), raw.takeUnless(::isPlainReason))
                }
                onFinished(failure)
            } finally {
                launching = false
                planningStage = null
            }
        }
    }

    fun startTutorialIfAsked(objective: String) {
        if (!com.hereliesaz.geministrator.intro.TutorialRouter.isTutorialGoal(objective)) return
        // A new tour: no lesson creature has supplied one yet, so the in-process writer is the fallback until one does.
        com.hereliesaz.geministrator.tour.LessonArtifactBridge.supplied = false
        val modelFor = queenModelFor
        if (modelFor == null) {
            scope.launch {
                if (runtime?.noteQueenAway() != true) QueenAwayNotice.shown = true
                // The opening needs no model: the keeper still sees the hive taught, and the rest waits for a Source.
                com.hereliesaz.geministrator.intro.TutorialLaunch.playOpening()
            }
            return
        }
        // One tour at a time: a second goal would stop the first one's layer out from under it.
        if (TutorialRunGuard.running) return
        TutorialRunGuard.running = true
        com.hereliesaz.geministrator.tour.TourController.preparation = com.hereliesaz.geministrator.tour.TourPreparation.Preparing
        scope.launch {
            // The run the tutorial started on, taken before anything else can replace the current one.
            val tutorialRunId = runtime?.currentRunId()
            try {
                val roles = resolveRoleCollection(workflowPersistence.roles.all())
                val base = modelFor(com.hereliesaz.geministrator.queen.orchestratorPin(roles))
                if (base == null) {
                    appLog("AiveTutorial", "No usable Source for the queen: the tutorial layer is not started")
                    com.hereliesaz.geministrator.tour.TourController.preparation = com.hereliesaz.geministrator.tour.TourPreparation.None
                    // The keeper is told once in Messages, with a link to Connections.
                    if (runtime?.noteQueenAway() != true) QueenAwayNotice.shown = true
                    com.hereliesaz.geministrator.intro.TutorialLaunch.playOpening()
                    return@launch
                }
                val active = runtime
                // Her calls here are held to the keeper's hourly spend cap, as in a dispute (a call it leaves no room for fails).
                val allowed = if (active == null) base else com.hereliesaz.geministrator.queen.cappedModel(base) { active.queenCallAllowed() }
                // Each call keeps the Source's request limit (a 2-per-minute keyless Source waits its turn, never fails a lesson).
                val capped = com.hereliesaz.geministrator.queen.pacedPerCall(allowed, active?.providerRegistry?.sourcePacing)
                val outcome = com.hereliesaz.geministrator.intro.TutorialLaunch.run(
                    goal = objective,
                    base = capped,
                    roles = roles,
                    labels = com.hereliesaz.geministrator.intro.tutorialLabels(),
                    nowMillis = { currentEpochMillis() },
                    // She follows the keeper through the tour and says so in Messages, at her level's pace (no model call for it).
                    level = { active?.queenLevelNow() ?: com.hereliesaz.geministrator.queen.QueenLevel.Quiet },
                    say = { line -> scope.launch { active?.noteTutorial(line, tutorialRunId) } },
                    opening = com.hereliesaz.geministrator.intro.tutorialOpening(),
                    closing = com.hereliesaz.geministrator.intro.tutorialClosing(),
                )
                appLog("AiveTutorial", "Tutorial ended: $outcome")
                // Never shown a lesson: say so, rather than leaving the keeper waiting on a layer that will not come.
                val shown = (outcome as? com.hereliesaz.geministrator.intro.TutorialOutcome.Ended)?.written ?: 0
                com.hereliesaz.geministrator.tour.TourController.preparation =
                    if (shown > 0 || com.hereliesaz.geministrator.tour.TourController.state?.lessons?.isNotEmpty() == true) {
                        com.hereliesaz.geministrator.tour.TourPreparation.None
                    } else {
                        com.hereliesaz.geministrator.tour.TourPreparation.Failed
                    }
            } catch (failure: CancellationException) {
                com.hereliesaz.geministrator.tour.TourController.preparation = com.hereliesaz.geministrator.tour.TourPreparation.None
                throw failure
            } catch (failure: Exception) {
                appLog("AiveTutorial", "Tutorial failed: ${failure::class.simpleName}")
                com.hereliesaz.geministrator.tour.TourController.preparation = com.hereliesaz.geministrator.tour.TourPreparation.Failed
            } finally {
                TutorialRunGuard.running = false
            }
        }
    }

    /**
     * Launches [objective] the way the launch form does; ignored while a launch is in progress.
     * [onFinished] receives null once the workflow started, or the failure; it is not called
     * when the launch is cancelled.
     */
    fun launchObjective(
        projectName: String,
        objective: String,
        repository: RepositoryRef?,
        continuesWorkflowRunIds: List<WorkflowRunId>,
        existingProject: Project?,
        objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
        /** The keeper switched on "have every result reviewed" in the launch form's options. */
        requireReview: Boolean = false,
        onFinished: (Throwable?) -> Unit = {},
    ) = runLaunch(onFinished) { activeRuntime ->
        researchRepository[0] = repository
        if (orchestrationRuntime != null) {
            activeRuntime.launchOrchestratedWorkflow(
                projectName = projectName,
                objective = objective,
                orchestrationRuntime = orchestrationRuntime,
                repository = repository,
                existingProject = existingProject,
                continuesWorkflowRunIds = continuesWorkflowRunIds,
                planReviewer = planReviewer,
                confirmPlan = askKeeper,
                planStore = planStore,
                objectiveProvenance = objectiveProvenance,
                research = researchStep,
                researchContext = researchContext,
                requireReview = requireReview,
            )
        } else {
            launchSteps.push("Starting the starter workflow…")
            activeRuntime.launchStarterWorkflow(
                projectName = projectName,
                objective = objective,
                repository = repository,
                existingProject = existingProject,
                continuesWorkflowRunIds = continuesWorkflowRunIds,
                objectiveProvenance = objectiveProvenance,
            )
        }
        // A goal that asks to be taught the app also starts the tutorial layer: the queen's planner and reviewers plan the
        // lessons and a writer makes them, one ahead of the keeper (intro/TutorialDirector.kt). The hive runs as for any goal.
        startTutorialIfAsked(objective)
    }

    // A reviewed plan the keeper was deciding on when the app last closed comes back, if nothing it was made from
    // has changed; otherwise it is dropped (ApplicationRuntime.resumePendingPlanLaunch).
    LaunchedEffect(runtime, planReviewer) {
        if (runtime != null && planReviewer != null && !launching && planStore.hasPending()) {
            runLaunch { activeRuntime -> activeRuntime.resumePendingPlanLaunch(planStore, askKeeper) }
        }
    }

    /**
     * Runs a library or generated-workflow launch under the same [launching] guard and overlay as
     * [launchObjective], so it never interleaves with another launch on the same runtime. Returns
     * false, without running [block], while another launch is in progress.
     */
    suspend fun guardedSavedWorkflowLaunch(block: suspend (ApplicationRuntime, Project?) -> Unit): Boolean {
        if (launching) return false
        val activeRuntime = runtime ?: error(getString(Res.string.app_runtime_is_unavailable))
        val existingProject = when (val state = runtimeState) {
            is ApplicationRuntimeState.NoRun -> state.project
            is ApplicationRuntimeState.Live -> state.presentation.project
            else -> null
        }
        launching = true
        launchError = null
        launchSteps.clear()
        try {
            // The same progress and planning field as the launch form: a saved rough design is planned and reviewed too.
            withContext(LaunchProgress(launchSteps::push) + PlanningStages { planningStage = it }) {
                block(activeRuntime, existingProject)
                if (planningStage?.phase == PlanningPhase.Started && !reduceMotion) delay(HATCH_HOLD_MILLIS)
            }
        } finally {
            launching = false
            planningStage = null
        }
        return true
    }

    LaunchedEffect(runtimeState) { onRuntimeStateChanged(runtimeState) }
    LaunchedEffect(runtimeState, runtime, onRunTimelineChanged) {
        val observer = onRunTimelineChanged ?: return@LaunchedEffect
        val activeRuntime = runtime ?: return@LaunchedEffect
        if (runtimeState !is ApplicationRuntimeState.Live) return@LaunchedEffect
        val timeline = try {
            activeRuntime.loadRunTimeline()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        observer(timeline)
    }
    LaunchedEffect(runtime) { onRuntimeAvailable(runtime) }
    // Host-lifetime only: no service, alarm, headless wake or inference without the local opt-in.
    LaunchedEffect(runtime) {
        val active = runtime ?: return@LaunchedEffect
        while (true) {
            delay(60_000L)
            runCatchingCancellable { active.reconsiderCollectiveEvidence() }.onFailure {
                com.hereliesaz.geministrator.log.AppLog.record("evidence", "Unattended reconsideration unavailable: ${it::class.simpleName}")
            }
        }
    }
    // The queen mends a swarm that teaches the app while it runs (QueenMending.kt).
    LaunchedEffect(runtime, orchestrationRuntime) {
        val active = runtime
        val planner = orchestrationRuntime
        if (active != null && planner != null) active.mendTeachingSwarms(planner)
    }
    // The queen gives a blocker with a starting template its own step in the swarm that is running (QueenBlockers.kt).
    LaunchedEffect(runtime) { runtime?.assignQueenBlockerSteps() }
    // The swarm's own lesson creatures hand in the overlays; the tour plays them (LessonCreatures.kt).
    LaunchedEffect(runtime) {
        val active = runtime ?: return@LaunchedEffect
        val labels = com.hereliesaz.geministrator.intro.tutorialLabels()
        val rowsOf = { com.hereliesaz.geministrator.intro.TutorialLaunch.rows(labels) }
        com.hereliesaz.geministrator.tour.LessonArtifactBridge.rowsOf = rowsOf
        active.playLessonCreatures(rowsOf)
    }
    // The queen revises lessons not yet played from the keeper's feelings and the run's events (LessonRevisions.kt).
    LaunchedEffect(runtime, orchestrationRuntime) {
        val active = runtime
        val planner = orchestrationRuntime
        if (active != null && planner != null) {
            val store = com.hereliesaz.geministrator.tour.ComfortStore(DurableUiState.store, com.hereliesaz.geministrator.tour.TourTargetId.registeredIds)
            active.reviseLessons(planner) { store.load() }
        }
    }
    LaunchedEffect(runtime, orchestrationRuntime) {
        onSwarmDrafterAvailable(
            orchestrationRuntime?.let { planner ->
                { prompt: String ->
                    val active = runtime
                    if (active == null) {
                        com.hereliesaz.geministrator.control.controlDraftOutcome(SwarmDraftResult.Unavailable)
                    } else {
                        com.hereliesaz.geministrator.control.controlDraftOutcome(active.draftSwarm(prompt, planner))
                    }
                }
            },
        )
    }

    // Requests whose launch finished here. The host drops them from its queue asynchronously, so
    // this keeps a finished request from being launched twice in the meantime.
    val handledExternalRequestIds = remember { mutableSetOf<Long>() }
    handledExternalRequestIds.retainAll(externalWorkflowLaunchRequests.mapTo(hashSetOf()) { it.requestId })
    val nextExternalRequest = externalWorkflowLaunchRequests.firstOrNull { it.requestId !in handledExternalRequestIds }

    // External requests wait for a runtime and for any launch in progress, then go through the
    // same path as the launch form, each in a new project unless one is open without a run.
    // The request is handed back only once its launch finished, with the failure if it failed.
    LaunchedEffect(nextExternalRequest?.requestId, runtime, launching) {
        val request = nextExternalRequest ?: return@LaunchedEffect
        val activeRuntime = runtime ?: return@LaunchedEffect
        if (launching) return@LaunchedEffect
        // Read the runtime's own state: runtimeState is filled asynchronously after a (re)load and
        // can still say Loading here, which would open a new project beside an open, empty one.
        val loaded = activeRuntime.state.first { it !is ApplicationRuntimeState.Loading }
        if (launching || runtime !== activeRuntime) return@LaunchedEffect
        val existingProject = (loaded as? ApplicationRuntimeState.NoRun)?.project
        launchObjective(request.projectName, request.objective, request.repository, emptyList(), existingProject, request.objectiveProvenance) { failure ->
            handledExternalRequestIds += request.requestId
            onExternalWorkflowLaunchHandled(
                request.requestId,
                failure?.let { it.message ?: it::class.simpleName ?: "Launch failed" },
            )
        }
    }

    GeministratorTheme {
        var destinationName by remember {
            mutableStateOf(
                ControlRoomSession.destinationName
                    ?: startupControlRoomDestination(
                        hasConfiguredProvider = enabledProviders.any { it !is OnDeviceTextAgentProvider },
                    ).name,
            )
        }
        LaunchedEffect(destinationName) { ControlRoomSession.destinationName = destinationName }
        val destinations = ControlRoomDestination.available(distributedComputeAvailable)
        val destination = destinations
            .firstOrNull { it.name == destinationName }
            ?: startupControlRoomDestination(hasConfiguredProvider = enabledProviders.any { it !is OnDeviceTextAgentProvider })
        var selectedTaskIdValue by rememberDurableStringState("navigation.selected-task-id")
        val selectedTaskId = selectedTaskIdValue.takeIf(String::isNotBlank)
        var navigationHistory by remember { mutableStateOf(emptyList<NavEntry>()) }
        // Where the keeper is now, with the creature and tab open there: what Back returns to (B1).
        fun hereEntry() = NavEntry(
            destination = destination,
            taskId = selectedTaskId.orEmpty(),
            tab = if (selectedTaskId != null) DurableUiState.store.getString("creature.panel.tab").orEmpty() else "",
        )
        // The one way to go anywhere (Destination.kt): it records where the keeper was, creature and tab included,
        // lands on the destination's place, and hands the rest (the Keeper's room part, the role, the creature)
        // to the control room.
        fun navigate(target: Destination) {
            if (target !is Destination.Place) PendingDestination.request = target
            val place = target.place
            if (place == destination) return
            navigationHistory = historyAfterNavigate(navigationHistory, hereEntry(), place)
            destinationName = place.name
            if (place != ControlRoomDestination.Overview) selectedTaskIdValue = ""
        }
        // The MCP consent question, set aside while the keeper looks at the server's row in Connections: it stays
        // pending and comes back on leaving the Keeper's room.
        // Only the request the keeper set aside stays hidden: the next one that is asked is shown at once.
        val pendingConsent = mcpConsent?.pending?.collectAsState()?.value
        var consentPeekOf by remember { mutableStateOf<com.hereliesaz.geministrator.mcp.McpConsentRequest?>(null) }
        val consentPeek = consentPeekOf != null && consentPeekOf === pendingConsent
        LaunchedEffect(destination, mcpConsent) {
            if (destination != ControlRoomDestination.Settings) consentPeekOf = null
        }
        val taskInspectorVisible = selectedTaskId != null &&
            destination == ControlRoomDestination.Overview

        PlatformBackHandler(
            enabled = taskInspectorVisible ||
                navigationHistory.isNotEmpty() ||
                destination != ControlRoomDestination.Overview,
        ) {
            when (appBackStep(taskInspectorVisible, navigationHistory, destination == ControlRoomDestination.Overview)) {
                AppBackStep.LetCreatureGo -> selectedTaskIdValue = ""
                AppBackStep.PopHistory -> {
                    val back = navigationHistory.last()
                    destinationName = back.destination.name
                    navigationHistory = navigationHistory.dropLast(1)
                    if (back.taskId.isNotEmpty()) {
                        CreatureTabRestore.taskId = back.taskId
                        CreatureTabRestore.tab = back.tab
                        selectedTaskIdValue = back.taskId
                    }
                }
                AppBackStep.ToHive -> destinationName = ControlRoomDestination.Overview.name
                AppBackStep.LeaveApp -> Unit
            }
        }

        LaunchedEffect(azphaltPackageImportRequest?.requestId) {
            if (
                azphaltPackageImportRequest != null &&
                destination != ControlRoomDestination.AddOns
            ) {
                navigate(Destination.Place(ControlRoomDestination.AddOns))
            }
        }

        LaunchedEffect(runtimeState) {
            val live = runtimeState as? ApplicationRuntimeState.Live
            if (
                live != null &&
                selectedTaskId != null &&
                live.presentation.definition.tasks.none { it.id.value == selectedTaskId }
            ) {
                selectedTaskIdValue = ""
            }
        }

        val nodeCharacterAssetStore = remember { SettingsNodeCharacterAssetStore() }

        val workflowLibraryHost = WorkflowLibraryHost(
            persistence = workflowPersistence,
            storeService = azphaltStoreService,
            launchWorkflow = { definition, packageRoles, objective, repository ->
                val started = guardedSavedWorkflowLaunch { activeRuntime, existingProject ->
                    activeRuntime.launchSavedWorkflow(
                        definition = definition,
                        existingProject = existingProject,
                        objective = objective ?: definition.description ?: definition.name,
                        repository = repository ?: existingProject?.repository,
                        supplementalRoles = packageRoles,
                        planner = orchestrationRuntime,
                        planReviewer = planReviewer,
                        confirmPlan = askKeeper,
                        planStore = planStore,
                    )
                }
                if (!started) error(getString(Res.string.app_launch_already_in_progress))
            },
            onPackagesChanged = { runtimeGeneration += 1 },
            onDefinitionSaved = { edited -> runtime?.applyLiveDefinitionEdit(edited) },
            onWorkflowCreated = { authored ->
                if (!authored.id.value.startsWith("node-characters-for-")) {
                    val catalog = resolveRoleCollection(
                        BuiltInRoles.all + workflowPersistence.roles.all(),
                    )
                    val rolesById = catalog.associateBy { it.id }
                    val builtInRoleIds = BuiltInRoles.all.mapTo(hashSetOf()) { it.id }
                    val missingRoleIds = linkedSetOf<RoleDefinitionId>()
                    for (roleId in NodeCharacterGenerationWorkflowFactory.referencedRoleIds(authored)) {
                        val role = rolesById[roleId] ?: continue
                        val hasEstablishedCharacter =
                            roleId in builtInRoleIds ||
                                MascotCharacterCatalog.explicitForRole(role.name) != null
                        if (!hasEstablishedCharacter && nodeCharacterAssetStore.get(roleId) == null) {
                            missingRoleIds += roleId
                        }
                    }
                    if (missingRoleIds.isNotEmpty()) {
                        NodeCharacterGenerationWorkflowFactory.roles.forEach { role ->
                            workflowPersistence.roles.put(role)
                        }
                        val generation = NodeCharacterGenerationWorkflowFactory.create(
                            source = authored,
                            roleCatalog = catalog + NodeCharacterGenerationWorkflowFactory.roles,
                            targetRoleIds = missingRoleIds,
                        )
                        val started = guardedSavedWorkflowLaunch { activeRuntime, existingProject ->
                            activeRuntime.launchSavedWorkflow(
                                definition = generation,
                                existingProject = existingProject,
                                projectName = existingProject?.name ?: authored.name,
                                objective = "Generate Node Creature assets for newly introduced roles in ${authored.name}.",
                                supplementalRoles =
                                    NodeCharacterGenerationWorkflowFactory.roles +
                                        catalog.filter { it.id in missingRoleIds },
                            )
                        }
                        if (!started) {
                            appLog("AiveLaunch", "Node character generation skipped: a launch is already in progress")
                        }
                    }
                }
            },
        ).also { host ->
            // Drafting a swarm only proposes; it needs the linked planner and a live runtime.
            host.swarmDrafter = orchestrationRuntime?.let { planner ->
                { prompt: String -> runtime?.draftSwarm(prompt, planner, planReviewer) ?: SwarmDraftResult.Unavailable }
            }
            host.specialistTrainer = { roleId: String, domain: String -> runtime?.startTrainingFromActive(roleId, domain) ?: false }
            host.peerMailbox = { runtime?.providerRegistry?.peerMailbox }
            host.collectiveChat = { question, conversation, timeBudget ->
                checkNotNull(runtime).collectiveAdvisory(question, conversation, timeBudget)
            }
            host.collectiveHistory = { checkNotNull(runtime).collectiveHistory() }
            host.collectiveNew = { checkNotNull(runtime).newCollectiveConversation() }
            host.collectiveDelete = { id -> checkNotNull(runtime).deleteCollectiveConversation(id) }
            host.collectiveAutomaticEvidence = { id, enabled -> checkNotNull(runtime).setCollectiveAutomaticEvidence(id, enabled) }
            host.collectiveBackgroundEvidence = { id, enabled -> checkNotNull(runtime).setCollectiveBackgroundEvidence(id, enabled) }
            host.collectiveBackgroundRevision = runtime?.collectiveBackgroundRevision
            host.collectiveImportHistory = { id -> checkNotNull(runtime).importCollectiveHistory(id) }
            host.collectiveProfiles = { checkNotNull(runtime).collectiveSpecialistProfiles() }
            host.evidenceLedger = { runtime?.collectiveEvidence }
        }

        // Scheduled hives (docs/Scheduled-Workflows.md): while the app is open, a due schedule starts its
        // hive as drawn, with no planner to ask and nothing approved for anyone. It waits for the active
        // run to finish, says what it decided in the app log, and a hive with an approval waits for the keeper.
        val scheduleStore = remember { SettingsWorkflowScheduleStore() }
        // A finished self-improvement cycle is read into the keeper's Improvement list, once
        // (docs/architecture/SELF_IMPROVEMENT.md). Reading changes nothing: a proposal is adopted only by the keeper.
        val finishedCycle = (runtimeState as? ApplicationRuntimeState.Live)?.presentation?.run
            ?.takeIf { it.workflowDefinitionId.value == SelfImprovementLoop.HIVE_ID && it.status == com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed }
        LaunchedEffect(finishedCycle?.id) {
            val run = finishedCycle ?: return@LaunchedEffect
            if (CycleIngest.ingest(improvementStore, run)) {
                val cycle = improvementStore.state().cycles.firstOrNull { it.runId == run.id.value }
                appLog("Improvement", "Cycle ${run.id.value}: ${cycle?.proposals ?: 0} proposals" + if (cycle?.examinedLoop == true) ", the loop examined itself." else ", and the loop did NOT examine itself.")
            }
        }
        // A cycle that finished while the app was closed, or while another run was on screen, is read from the run history.
        LaunchedEffect(runtime) {
            val active = runtime ?: return@LaunchedEffect
            val history = runCatching { active.loadRunHistory().flatMap { it.second } }.getOrNull() ?: return@LaunchedEffect
            CycleIngest.backfill(improvementStore, history).forEach { run ->
                appLog("Improvement", "Cycle ${run.id.value} was read from the run history.")
            }
        }
        LaunchedEffect(runtime, workflowPersistence) {
            val active = runtime ?: return@LaunchedEffect
            // Starts a schedule's hive as drawn. [event] is a web call or new mail that started it: its text is outside content.
            suspend fun launchScheduled(schedule: com.hereliesaz.geministrator.workflow.schedule.WorkflowSchedule, event: com.hereliesaz.geministrator.workflow.schedule.TriggerEvent?) {
                val definition = workflowPersistence.definitions.get(WorkflowDefinitionId(schedule.workflowId))
                    ?: error("the hive is no longer on this device")
                // The loop runs unattended on the promise that it only reads and reports and never waits on anyone: a hive
                // that no longer keeps that is not started on a schedule (the keeper can still run it by hand).
                if (definition.id.value == SelfImprovementLoop.HIVE_ID) {
                    LoopIntegrity.startRefusal(definition, resolveRoleCollection(workflowPersistence.roles.all()))?.let { error(it) }
                }
                // An evidence-fed schedule (the self-improvement cycle) starts with the swarm's own numbers and the loop as it is now.
                val baseObjective = if (schedule.withEvidence) {
                    val roles = resolveRoleCollection(workflowPersistence.roles.all())
                    schedule.objective + "\n\n" + SwarmEvidence.of(
                        runs = active.loadRunHistory().flatMap { it.second },
                        roles = roles,
                        state = improvementStore.state(),
                        nowEpochMillis = currentEpochMillis(),
                        loopDescription = SelfImprovementLoop.describe(
                            definition = definition,
                            hallMonitor = roles.firstOrNull { it.id == com.hereliesaz.geministrator.domain.HallMonitorRole.id } ?: com.hereliesaz.geministrator.domain.HallMonitorRole.definition,
                        ),
                    )
                } else {
                    schedule.objective
                }
                val objective = if (event != null) com.hereliesaz.geministrator.workflow.schedule.TriggerObjective.compose(baseObjective, event) else baseObjective
                val started = guardedSavedWorkflowLaunch { activeRuntime, existingProject ->
                    activeRuntime.launchSavedWorkflow(
                        definition = definition,
                        existingProject = existingProject,
                        objective = objective,
                        repository = existingProject?.repository,
                        // Outside text never counts as the keeper's: a step that can change something waits at the context-taint gate.
                        objectiveProvenance = if (event != null) com.hereliesaz.geministrator.domain.ContextProvenance.ExternalContent else schedule.objectiveProvenance(),
                    )
                }
                if (!started) error(getString(Res.string.app_launch_already_in_progress))
                // The cause goes on the run's Story as one line.
                if (event != null) {
                    runCatchingCancellable {
                        val latest = active.persistence.projects.all().flatMap { active.persistence.runs.byProject(it.id) }.maxByOrNull { it.updatedAtEpochMillis }
                        if (latest != null) {
                            com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink(active.persistence.events).append(
                                com.hereliesaz.geministrator.events.PolicyDecisionRecorded(
                                    workflowRunId = latest.id,
                                    effect = com.hereliesaz.geministrator.policy.PolicyEffect.Allow,
                                    code = "schedule.trigger",
                                    action = "start",
                                    subjectType = "schedule",
                                    subjectId = schedule.id,
                                    resourceType = "trigger",
                                    resourceId = event.eventId.take(64),
                                    reason = "Started by ${event.cause}",
                                    occurredAtEpochMillis = currentEpochMillis(),
                                ),
                            )
                        }
                    }
                }
            }
            val intake = com.hereliesaz.geministrator.workflow.schedule.TriggerIntake(scheduleStore) { currentEpochMillis() }
            com.hereliesaz.geministrator.workflow.schedule.ScheduleTriggerHub.intake = intake
            try {
                ScheduleTicker(
                    store = scheduleStore,
                    now = { currentEpochMillis() },
                    runActive = { active.hasUnfinishedActiveRun() },
                    deviceUtcOffsetMinutes = { deviceUtcOffsetMinutes() },
                    start = { schedule -> launchScheduled(schedule, null) },
                    startTriggered = { schedule, event -> launchScheduled(schedule, event) },
                    triggers = intake,
                    mail = mcpHost?.let { host ->
                        com.hereliesaz.geministrator.workflow.schedule.MailPoller(
                            scheduleStore,
                            com.hereliesaz.geministrator.workflow.schedule.McpMailArrivalSource(host),
                            intake,
                            { currentEpochMillis() },
                            { line -> appLog("Schedule", line) },
                        )
                    },
                    log = { line -> appLog("Schedule", line) },
                    vet = { schedule -> workflowLibraryHost.scheduleVetting(schedule.workflowId) },
                ).run()
            } finally {
                com.hereliesaz.geministrator.workflow.schedule.ScheduleTriggerHub.intake = null
            }
        }

        // The mascots are labelled with the keeper-facing name (the orchestrator reads Queen), so the rig lookup
        // knows both that and the stored name.
        val rolesByName = (liveRun?.roles.orEmpty() + BuiltInRoles.all)
            .flatMap { listOf(it.keeperName() to it.id, it.name to it.id) }
            .toMap()
        // A role that runs a script or an action rather than an agent is programmatic work: it wears the neuron, never a kept creature.
        val programmaticRoles = (liveRun?.roles.orEmpty() + BuiltInRoles.all)
            .filter { it.executionSource != com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
            .flatMapTo(hashSetOf()) { listOf(it.name, it.keeperName()) }
        CompositionLocalProvider(
            LocalWorkflowLibraryHost provides workflowLibraryHost,
            LocalWorkflowScheduleStore provides scheduleStore,
            LocalScheduleWebhookEndpoint provides ScheduleWebhookEndpoint(supported = a2aServerSetting != null, baseUrl = webhookBaseUrl(a2aServerSetting?.agentCardUrl)),
            LocalImprovementStore provides improvementStore,
            LocalStableHost provides stableHost,
            LocalBreedPurchases provides breedPurchases,
            LocalEntire provides entire,
            LocalMcpHost provides mcpHost,
            LocalCodeExecution provides codeExecution,
            LocalResearchSettings provides researchSettings,
            LocalCreatureRig provides { label ->
                if (label in programmaticRoles) CreatureRigMapping.PROGRAMMATIC_RIG else stableHost.rigFor(rolesByName[label])
            },
        ) {
            Scaffold { paddingValues ->
              Box(modifier = Modifier.fillMaxSize()) {
                mcpConsent?.let {
                    McpConsentDialog(
                        gate = it,
                        hidden = consentPeek && destination == ControlRoomDestination.Settings,
                        onOpenServer = {
                            consentPeekOf = pendingConsent
                            navigate(Destination.KeeperPart(KeeperSection.Connections))
                        },
                    )
                }
                mcpElicitation?.let { McpElicitationDialog(it) }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    ControlRoom(
                        destination = destination,
                        destinations = destinations,
                        onDestinationSelected = { target -> navigate(Destination.Place(target)) },
                        selectedTaskId = selectedTaskId,
                        onTaskSelected = { taskId ->
                            selectedTaskIdValue = if (selectedTaskId == taskId) "" else taskId
                        },
                        onLoadLaunchableRuns = { runtime?.loadLaunchableRuns() ?: emptyList() },
                        onLaunchWorkflow = { projectName, objective, repository, continuesWorkflowRunIds, requireReview ->
                            val existingProject = when (val state = runtimeState) {
                                is ApplicationRuntimeState.NoRun -> state.project
                                // "Continue" from a finished run's view stays in that run's project.
                                is ApplicationRuntimeState.Live ->
                                    state.presentation.project.takeIf { continuesWorkflowRunIds.isNotEmpty() }
                                else -> null
                            }
                            launchObjective(projectName, objective, repository, continuesWorkflowRunIds, existingProject, requireReview = requireReview)
                        },
                        onApproveTask = { taskId ->
                            scope.launch {
                                try {
                                    runtime?.approveTask(TaskDefinitionId(taskId))
                                    actionDone = getString(Res.string.app_done_approved)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_approval_failed), failure)
                                }
                            }
                        },
                        onRejectPlan = { taskId ->
                            scope.launch {
                                try {
                                    runtime?.rejectPlan(TaskDefinitionId(taskId))
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_plan_rejection_failed), failure)
                                }
                            }
                        },
                        onLiftSpendStop = {
                            scope.launch {
                                try {
                                    runtime?.liftSpendStop()
                                    actionDone = getString(Res.string.app_done_spend_stop_lifted)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_lift_spend_stop_failed), failure)
                                }
                            }
                        },
                        onPauseRun = { paused ->
                            scope.launch {
                                try {
                                    runtime?.setKeeperPause(paused)
                                    actionDone = getString(if (paused) Res.string.app_done_paused else Res.string.app_done_resumed)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_pause_run_failed), failure)
                                }
                            }
                        },
                        onStopRun = { runId ->
                            scope.launch {
                                try {
                                    runtime?.cancelActiveRun(runId)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_stop_run_failed), failure)
                                }
                            }
                        },
                        onResolveEscalation = { taskId, approved ->
                            scope.launch {
                                try {
                                    val activeRuntime = runtime ?: error("Runtime is unavailable")
                                    if (approved && orchestrationRuntime != null) {
                                        activeRuntime.repairFailureEscalation(
                                            taskDefinitionId = TaskDefinitionId(taskId),
                                            orchestrationRuntime = orchestrationRuntime,
                                        )
                                    } else {
                                        activeRuntime.decideFailureEscalation(
                                            taskDefinitionId = TaskDefinitionId(taskId),
                                            approved = approved,
                                            note = if (approved) {
                                                "Retry approved in application"
                                            } else {
                                                "Escalation rejected in application"
                                            },
                                        )
                                    }
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_escalation_decision_failed), failure)
                                }
                            }
                        },
                        onNannyReview = { step, approved ->
                            scope.launch {
                                try {
                                    val activeRuntime = runtime ?: error("Runtime is unavailable")
                                    activeRuntime.applyHallMonitorReviewStep(step, approved)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_escalation_decision_failed), failure)
                                }
                            }
                        },
                        onNannyTestSolution = { findingId, solutionIndex ->
                            scope.launch {
                                try {
                                    val activeRuntime = runtime ?: error("Runtime is unavailable")
                                    val planner = orchestrationRuntime
                                        ?: error(
                                            "Nanny solution testing requires the orchestration model runtime on this platform. " +
                                                "The paused workflow and recommendation were not changed.",
                                        )
                                    activeRuntime.testHallMonitorSolution(findingId, solutionIndex, planner)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_escalation_decision_failed), failure)
                                }
                            }
                        },
                        onQueenSuggestion = { proposalId, accept ->
                            scope.launch {
                                try {
                                    val activeRuntime = runtime ?: error("Runtime is unavailable")
                                    if (accept) {
                                        // A suggestion that no longer stands, or was already decided, changes nothing and says so.
                                        if (activeRuntime.acceptQueenSuggestion(proposalId) != ApplicationRuntime.QueenAccept.Applied) {
                                            actionFailure = ActionFailure(getString(Res.string.queen_suggest_nothing_changed), "")
                                        }
                                    } else {
                                        activeRuntime.dismissQueenSuggestion(proposalId)
                                    }
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    actionFailure = actionFailureOf(getString(Res.string.app_escalation_decision_failed), failure)
                                }
                            }
                        },
                        onMessageAgent = { taskId, message ->
                            try {
                                when (val result = runtime?.messageTask(TaskDefinitionId(taskId), message)) {
                                    null -> getString(Res.string.app_runtime_is_unavailable)
                                    ProviderActionResult.Accepted -> {
                                        // What the keeper told an agent is evidence for proposed edits of its
                                        // role's instructions (PROCEDURAL_MEMORY.md §10); it changes no role.
                                        runCatching {
                                            (runtimeState as? ApplicationRuntimeState.Live)?.presentation?.let { live ->
                                                live.run.taskRuns[TaskDefinitionId(taskId)]?.assignedRoleId?.let { roleId ->
                                                    RoleInstructionProposalsHost.ledger.observe(
                                                        listOf(
                                                            com.hereliesaz.geministrator.memory.procedural.RoleInstructionObservations.keeperMessage(
                                                                roleId = roleId,
                                                                workflowRunId = live.run.id.value,
                                                                taskDefinitionId = taskId,
                                                                text = message,
                                                                atEpochMillis = live.run.updatedAtEpochMillis,
                                                            ),
                                                        ),
                                                        live.roles,
                                                        live.run.updatedAtEpochMillis,
                                                    )
                                                }
                                            }
                                        }
                                        null
                                    }
                                    is ProviderActionResult.Rejected -> result.reason
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                failure.message?.takeIf(String::isNotBlank) ?: getString(Res.string.app_provider_message_failed)
                            }
                        },
                        onRewindTask = { taskId ->
                            try {
                                runtime?.rewindToTask(TaskDefinitionId(taskId))
                                null
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                failure.message?.takeIf(String::isNotBlank) ?: getString(Res.string.app_rerun_failed)
                            }
                        },
                        onRerunFromTask = { runId, request ->
                            try {
                                val activeRuntime = runtime
                                if (activeRuntime == null) {
                                    getString(Res.string.app_runtime_is_unavailable)
                                } else {
                                    activeRuntime.rerunFromTask(runId, request)
                                    null
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                failure.message?.takeIf(String::isNotBlank) ?: getString(Res.string.app_rerun_failed)
                            }
                        },
                        onAnswerKeeperQuestion = { taskId, questionKey, answer ->
                            try {
                                when (val result = runtime?.answerKeeperQuestion(TaskDefinitionId(taskId), questionKey, answer)) {
                                    null -> getString(Res.string.app_runtime_is_unavailable)
                                    ProviderActionResult.Accepted -> null
                                    is ProviderActionResult.Rejected -> result.reason
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                failure.message?.takeIf(String::isNotBlank) ?: getString(Res.string.app_provider_message_failed)
                            }
                        },
                        onRecoverFromCorruption = {
                            scope.launch {
                                try {
                                    runtime?.recoverFromCorruption()
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_recovery_failed))
                                }
                            }
                        },
                        onRetryRuntime = {
                            if (runtime == null) {
                                runtimeGeneration += 1
                            } else {
                                scope.launch {
                                    try {
                                        runtime?.refresh()
                                    } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (failure: Exception) {
                                        runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_retry_failed))
                                    }
                                }
                            }
                        },
                        onCheckProviderHealth = {
                            runtime?.checkProviderHealth()?.mapValues { (_, result) ->
                                result.fold(
                                    onSuccess = { caps -> getString(Res.string.app_reachable_capabilities, caps.supported.size) },
                                    onFailure = { failure ->
                                        // First line: short summary with a next step. Anything after
                                        // the newline is the raw text, shown behind a Details toggle.
                                        val raw = failure.message?.trim().orEmpty()
                                        val summary = "${getString(Res.string.app_unreachable)} · " +
                                            friendlyErrorMessageOutsideComposition(raw)
                                        if (raw.isEmpty()) summary else summary + "\n" + raw.take(300)
                                    },
                                )
                            } ?: emptyMap()
                        },
                        onClearWorkflowData = {
                            scope.launch {
                                try {
                                    runtime?.providerRegistry?.peerMailbox?.clear()
                                    runtime?.clearCollectiveConversations()
                                    (runtime?.persistence as? com.hereliesaz.geministrator.persistence.DurableWorkflowPersistence)?.clearWorkflowData()
                                    runtime?.loadLatest()
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_clear_failed))
                                }
                            }
                        },
                        onExportJson = suspend { runtime?.exportJson() },
                        onImportJson = { encoded ->
                            scope.launch {
                                try {
                                    runtime?.importJson(encoded)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_import_failed))
                                }
                            }
                        },
                        projectFileService = projectFileService,
                        roleSurfaceFilePicker = projectFileService as? RoleSurfaceFilePicker,
                        onExportCurrentProjectFile = { runtime?.exportCurrentProjectFile() },
                        onImportProjectFile = { encoded ->
                            val imported = runtime?.importProjectFile(encoded)
                            if (imported != null) {
                                projectIdToOpenAfterReload = imported.id.value
                                runtimeGeneration += 1
                            }
                            imported?.name
                        },
                        setupFileActions = SetupFileActions(
                            credentialsIncluded = setupSecretStore != null,
                            export = { passphrase ->
                                exportSetupFile(workflowPersistence, setupSecretStore, crashReportingSetting?.enabled, passphrase)
                            },
                            import = { encoded, passphrase ->
                                importSetupFile(
                                    persistence = workflowPersistence,
                                    secretStore = setupSecretStore,
                                    encoded = encoded,
                                    passphrase = passphrase,
                                    onCrashReportingChange = crashReportingSetting?.onEnabledChange,
                                    memoryLayer = memoryLayer,
                                ).also { runtimeGeneration += 1 }
                            },
                        ),
                        onLoadRunHistory = { runtime?.loadRunHistory() ?: emptyList() },
                        onSwitchRun = { runId ->
                            scope.launch {
                                try {
                                    runtime?.switchToRun(WorkflowRunId(runId))
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_switch_run_failed))
                                }
                            }
                        },
                        onLoadRunTimeline = { runtime?.loadRunTimeline(shown = true) ?: emptyList() },
                        onLoadEchoHistory = { run -> runtime?.loadEchoHistory(run) ?: emptyMap() },
                        onLoadTrophicHistory = { run -> runtime?.loadTrophicHistory(run) ?: emptyMap() },
                        onLoadOperationalProfiles = { run -> runtime?.loadOperationalProfiles(run) ?: emptyMap() },
                        onLoadLogIntegrity = { runtime?.loadRunLogIntegrity() ?: com.hereliesaz.geministrator.events.ChainStatus.NoChain },
                        onLoadWorkflowDefinitions = { runtime?.persistence?.definitions?.all() ?: emptyList() },
                        onExportDiagnosticBundle = { runtime?.exportDiagnosticBundle() },
                        onValidateWorkflow = { runtime?.validateCurrentWorkflow() ?: emptyList() },
                        onSaveRoleCollectionNow = { roles ->
                            try {
                                runtime?.saveRoleCollection(roles)
                                runtimeGeneration += 1
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_save_swarm_failed))
                                throw failure
                            }
                        },
                        onSaveRoleCollection = { roles ->
                            scope.launch {
                                try {
                                    runtime?.saveRoleCollection(roles)
                                    runtimeGeneration += 1
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_save_swarm_failed))
                                }
                            }
                        },
                        onResetRoleCollection = {
                            scope.launch {
                                try {
                                    runtime?.resetRoleCollection()
                                    runtimeGeneration += 1
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_reset_swarm_failed))
                                }
                            }
                        },
                        onAssignWorkflowCompute = { definitionId, target ->
                            scope.launch {
                                try {
                                    runtime?.assignWorkflowCompute(definitionId, target)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_workflow_delegation_failed))
                                }
                            }
                        },
                        onAssignRoleCompute = { definitionId, roleId, target ->
                            scope.launch {
                                try {
                                    runtime?.assignRoleCompute(definitionId, roleId, target)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_role_delegation_failed))
                                }
                            }
                        },
                        onAssignTaskCompute = { definitionId, taskId, target ->
                            scope.launch {
                                try {
                                    runtime?.assignTaskCompute(definitionId, taskId, target)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_task_delegation_failed))
                                }
                            }
                        },
                        onSearchRepositories = onSearchRepositories,
                        availableRepositorySources = availableRepositorySources,
                        onPickLocalRepository = onPickLocalRepository,
                        connectedRepositoryServiceIds = connectedRepositoryServiceIds,
                        onConfigureRepositoryService = onConfigureRepositoryService,
                        onDisconnectRepositoryService = onDisconnectRepositoryService,
                        onReconfigureProvider = onReconfigureProvider,
                        onDisconnectProvider = onDisconnectProvider,
                        distributedComputeState = distributedComputeState,
                        onSaveDistributedCompute = onSaveDistributedCompute,
                        onDisconnectDistributedCompute = onDisconnectDistributedCompute,
                        onRevokeComputeNode = onRevokeComputeNode,
                        crashReportingSetting = crashReportingSetting,
                        remoteAccessSetting = remoteAccessSetting,
                        platformLimits = platformLimits,
                        a2aServerSetting = a2aServerSetting,
                        localPlannerSetting = localPlannerSetting,
                        localOrchestrationSpecialistSetting = localOrchestrationSpecialistSetting,
                        onDeviceSmallTaskSetting = onDeviceSmallTaskSetting,
                        memoryLayer = memoryLayer,
                        delegations = delegations,
                        azphaltStoreService = azphaltStoreService,
                        azphaltPackageImportRequest = azphaltPackageImportRequest,
                        onAzphaltPackageImportHandled = onAzphaltPackageImportHandled,
                        compact = maxWidth < ControlRoomBreakpoints.Wide,
                        contentPadding = paddingValues,
                        runtimeState = runtimeState,
                        connectedProviderIds = providers.map { it.id.value }.toSet() + linkedImageProviderIds + ProviderEnablement.disabled,
                        providerReach = providerReach,
                        onConnectFreeProvider = onConnectFreeProvider ?: onReconfigureProvider,
                        launchError = launchError,
                        onDismissLaunchError = { launchError = null },
                    )
                }
                actionFailure?.let { failure ->
                    FailureNotice(
                        what = failure.what,
                        raw = failure.raw,
                        seed = "action",
                        onDismiss = { actionFailure = null },
                        modifier = Modifier.align(Alignment.TopCenter).padding(AzphaltSpacing.m).widthIn(max = 560.dp),
                    )
                }
                actionDone?.let { done ->
                    AzphaltNote(
                        seed = "action-done",
                        label = stringResource(Res.string.app_done),
                        value = done,
                        onClick = { actionDone = null },
                        modifier = Modifier.align(Alignment.TopCenter).padding(AzphaltSpacing.m).widthIn(max = 560.dp),
                    )
                }
                if (launching) {
                    val pending = planDecision
                    val stage = planningStage
                    if (stage != null) {
                        PlanningOverlay(
                            stage = stage,
                            decision = pending?.decision,
                            onStart = { pending?.answer?.complete(true) },
                            onDecline = { pending?.answer?.complete(false) },
                            onResize = { pending?.answer?.complete(false) },
                            stepRows = launchStepRows,
                            maxRows = launchSteps.maxRows,
                        )
                    } else {
                        LaunchStepsOverlay(rows = launchStepRows, maxRows = launchSteps.maxRows)
                    }
                }
              }
            }
        }
    }
}

private fun Throwable.toRuntimeFailureState(fallback: String): ApplicationRuntimeState.ResumeFailed {
    val message = message?.takeIf(String::isNotBlank)
        ?: this::class.simpleName.orEmpty().ifBlank { fallback }
    return ApplicationRuntimeState.ResumeFailed(message)
}

@Composable
private fun GeministratorTheme(content: @Composable () -> Unit) {
    LoadAzphaltFonts()
    // h2g2's motion (the containment nest, stack entrances) reads conveyance's reduced-motion
    // local and H2g2Motion's scale; both follow AzphaltMotion, the one switch the keeper sees.
    val reduce = AzphaltMotion.reduceMotion
    LaunchedEffect(reduce) { com.hereliesaz.conveyance.h2g2.H2g2Motion.updateDurationScale(if (reduce) 0f else 1f) }
    // The library's own pieces (status colours, the job progress bar) read the page ground from here. H2g2Theme itself is
    // not used: it would roll its own ground and write the system animator scale over the keeper's Reduce motion switch.
    val h2g2Ground = com.hereliesaz.conveyance.h2g2.grounds.firstOrNull { it.name == Azphalt.currentGround.name }
        ?: com.hereliesaz.conveyance.h2g2.grounds.first()
    CompositionLocalProvider(
        com.hereliesaz.conveyance.compose.LocalReducedMotion provides reduce,
        com.hereliesaz.conveyance.h2g2.LocalH2g2Ground provides h2g2Ground,
    ) {
        MaterialTheme(
            colorScheme = GeministratorColors,
        ) {
            // HG2Gui has no press, hover or focus state: the capsule's own motion is the feedback, so no ripple under any control.
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides NoPressIndication, content)
        }
    }
}


/**
 * Process-lifetime memory of the visible control-room destination. A platform shell can swap [App]
 * out for a full-screen credential form (Android: connecting a provider) and then bring it back,
 * which rebuilds [App]'s own state; restoring from here keeps the user on the screen they left
 * (Settings) instead of bouncing to the startup destination. Deliberately not persisted, so a
 * fresh launch still opens on [startupControlRoomDestination].
 */
internal object ControlRoomSession {
    var destinationName: String? = null
}

/**
 * Every launch opens on the hive (docs/Keepers-Room.md): with no provider yet, the first hive asks
 * for one as its first step rather than the app opening on the Keeper's room.
 */
@Suppress("UNUSED_PARAMETER")
internal fun startupControlRoomDestination(
    hasConfiguredProvider: Boolean,
): ControlRoomDestination = ControlRoomDestination.Overview

/** A reviewed plan waiting for the keeper, and the answer that lets the launch go on (true) or end (false). */
private class PendingPlanDecision(
    val decision: com.hereliesaz.geministrator.orchestration.PlanDecision,
    val answer: kotlinx.coroutines.CompletableDeferred<Boolean>,
)

/** How long the field stays on screen after the keeper starts a reviewed plan, so the first step's egg is seen to hatch. */
private const val HATCH_HOLD_MILLIS = 1_400L

/** An [androidx.compose.foundation.Indication] that draws nothing (HG2Gui: no press, hover or focus state). */
private object NoPressIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : androidx.compose.ui.Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
            override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() = drawContent()
        }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}
