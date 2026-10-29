package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.workflow.NodeCharacterGenerationWorkflowFactory
import com.hereliesaz.geministrator.workflow.SettingsNodeCharacterAssetStore
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.app_approval_failed
import com.hereliesaz.geministrator.resources.app_unreachable
import com.hereliesaz.geministrator.resources.app_clear_failed
import com.hereliesaz.geministrator.resources.app_escalation_decision_failed
import com.hereliesaz.geministrator.resources.app_import_failed
import com.hereliesaz.geministrator.resources.app_plan_rejection_failed
import com.hereliesaz.geministrator.resources.app_provider_message_failed
import com.hereliesaz.geministrator.resources.app_reachable_capabilities
import com.hereliesaz.geministrator.resources.app_recovery_failed
import com.hereliesaz.geministrator.resources.app_reset_swarm_failed
import com.hereliesaz.geministrator.resources.app_retry_failed
import com.hereliesaz.geministrator.resources.app_role_delegation_failed
import com.hereliesaz.geministrator.resources.app_runtime_bootstrap_failed
import com.hereliesaz.geministrator.resources.app_runtime_is_unavailable
import com.hereliesaz.geministrator.resources.app_save_swarm_failed
import com.hereliesaz.geministrator.resources.app_switch_run_failed
import com.hereliesaz.geministrator.resources.app_task_delegation_failed
import com.hereliesaz.geministrator.resources.app_workflow_delegation_failed
import com.hereliesaz.geministrator.resources.app_workflow_launch_failed
import com.hereliesaz.geministrator.resources.app_launch_already_in_progress
import org.jetbrains.compose.resources.getString

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
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    distributedComputeAvailable: Boolean = true,
    crashReportingSetting: CrashReportingSetting? = null,
    remoteAccessSetting: RemoteAccessSetting? = null,
    localPlannerSetting: LocalPlannerSetting? = null,
    localOrchestrationSpecialistSetting: LocalOrchestrationSpecialistSetting? = null,
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
    /** Credential stores for the setup file; null leaves credentials out of it. */
    setupSecretStore: SetupSecretStore? = null,
) {
    val scope = rememberCoroutineScope()
    val workflowPersistence = remember(persistence) { persistence ?: com.hereliesaz.geministrator.persistence.WorkflowPersistenceFactory.durable }
    var runtimeState by remember { mutableStateOf<ApplicationRuntimeState>(ApplicationRuntimeState.Loading) }
    var runtime by remember { mutableStateOf<ApplicationRuntime?>(null) }
    var runtimeGeneration by remember { mutableStateOf(0) }
    var launching by remember { mutableStateOf(false) }
    val launchSteps = remember { LiveStepsFeedModel(maxRows = 5) }
    val launchStepRows by launchSteps.rows.collectAsState()
    var projectIdToOpenAfterReload by remember { mutableStateOf<String?>(null) }
    var automaticProjectRestoreAttempted by remember(projectFileService) { mutableStateOf(false) }

    LaunchedEffect(providers, duplexAttachment, executorIntegrations, roleSurfaceRuntime, workflowPersistence, orchestrationUtilities, contextChunkSources, runtimeGeneration) {
        runtime?.close()
        runtime = null
        runtimeState = ApplicationRuntimeState.Loading
        try {
            val created = ApplicationRuntime.create(
                providers = providers,
                scope = scope,
                duplexAttachment = duplexAttachment,
                persistence = workflowPersistence,
                executorIntegrations = executorIntegrations,
                roleSurfaceRuntime = roleSurfaceRuntime,
                orchestrationUtilities = orchestrationUtilities,
                contextRetriever = com.hereliesaz.geministrator.retrieval.SimilarityContextRetriever(
                    listOf(com.hereliesaz.geministrator.retrieval.ArtifactChunkSource) + contextChunkSources,
                ),
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
        stableHost.load()
        // Breeds paid for while the app was closed join the stable now.
        runCatching { breedPurchases?.unclaimed() }.getOrNull()?.forEach(stableHost::bought)
    }

    // Breeding (docs/Swarm-OS.md step 6): when the live run clears, it hatches one config from the
    // agents that did its work, kept unadopted in the roster. The child's id is the run's, so a
    // run hatches once however often this effect runs.
    val liveRun = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    LaunchedEffect(liveRun?.run?.id, liveRun?.run?.status) {
        val presentation = liveRun ?: return@LaunchedEffect
        // Each cleared run counts toward the stable's next generation (counted once per run).
        stableHost.runCleared(presentation.run)
        val child = hatchFromRun(presentation.run, presentation.roles) ?: return@LaunchedEffect
        val target = runtime ?: return@LaunchedEffect
        try {
            if (target.persistence.roles.all().none { it.id == child.id }) target.saveRole(child)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Hatching is best effort; a failed hatch never disturbs the run.
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
        onFinished: (Throwable?) -> Unit = {},
    ) {
        if (launching) {
            platformDebugLog("AiveLaunch", "Launch ignored: a launch is already in progress")
            return
        }
        launching = true
        launchSteps.clear()
        scope.launch(LaunchProgress(launchSteps::push)) {
            try {
                platformDebugLog(
                    "AiveLaunch",
                    "Launch started: orchestrated=${orchestrationRuntime != null} runtime=${runtime != null}",
                )
                val activeRuntime = runtime ?: error(getString(Res.string.app_runtime_is_unavailable))
                if (orchestrationRuntime != null) {
                    activeRuntime.launchOrchestratedWorkflow(
                        projectName = projectName,
                        objective = objective,
                        orchestrationRuntime = orchestrationRuntime,
                        repository = repository,
                        existingProject = existingProject,
                        continuesWorkflowRunIds = continuesWorkflowRunIds,
                    )
                } else {
                    launchSteps.push("Starting the starter workflow…")
                    activeRuntime.launchStarterWorkflow(
                        projectName = projectName,
                        objective = objective,
                        repository = repository,
                        existingProject = existingProject,
                        continuesWorkflowRunIds = continuesWorkflowRunIds,
                    )
                }
                onFinished(null)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // Catch Throwable, not Exception: on-device model failures such as
                // OutOfMemoryError are Errors and previously escaped this handler,
                // leaving the launch button silently doing nothing.
                platformDebugLog("AiveLaunch", "Launch failed: ${failure::class.simpleName}: ${failure.message}")
                runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_workflow_launch_failed))
                onFinished(failure)
            } finally {
                launching = false
            }
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
        launchSteps.clear()
        try {
            block(activeRuntime, existingProject)
        } finally {
            launching = false
        }
        return true
    }

    LaunchedEffect(runtimeState) { onRuntimeStateChanged(runtimeState) }

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
        launchObjective(request.projectName, request.objective, request.repository, emptyList(), existingProject) { failure ->
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
                        hasConfiguredProvider = providers.isNotEmpty(),
                    ).name,
            )
        }
        LaunchedEffect(destinationName) { ControlRoomSession.destinationName = destinationName }
        val destinations = ControlRoomDestination.available(distributedComputeAvailable)
        val destination = destinations
            .firstOrNull { it.name == destinationName }
            ?: startupControlRoomDestination(hasConfiguredProvider = providers.isNotEmpty())
        var selectedTaskIdValue by rememberDurableStringState("navigation.selected-task-id")
        val selectedTaskId = selectedTaskIdValue.takeIf(String::isNotBlank)
        var navigationHistory by remember { mutableStateOf(emptyList<ControlRoomDestination>()) }
        val taskInspectorVisible = selectedTaskId != null &&
            destination == ControlRoomDestination.Overview

        PlatformBackHandler(
            enabled = taskInspectorVisible ||
                navigationHistory.isNotEmpty() ||
                destination != ControlRoomDestination.Overview,
        ) {
            when {
                taskInspectorVisible -> selectedTaskIdValue = ""
                navigationHistory.isNotEmpty() -> {
                    destinationName = navigationHistory.last().name
                    navigationHistory = navigationHistory.dropLast(1)
                }
                destination != ControlRoomDestination.Overview -> {
                    destinationName = ControlRoomDestination.Overview.name
                }
            }
        }

        LaunchedEffect(azphaltPackageImportRequest?.requestId) {
            if (
                azphaltPackageImportRequest != null &&
                destination != ControlRoomDestination.AddOns
            ) {
                navigationHistory = navigationHistory + destination
                destinationName = ControlRoomDestination.AddOns.name
                selectedTaskIdValue = ""
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
                    )
                }
                if (!started) error(getString(Res.string.app_launch_already_in_progress))
            },
            onPackagesChanged = { runtimeGeneration += 1 },
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
                            platformDebugLog("AiveLaunch", "Node character generation skipped: a launch is already in progress")
                        }
                    }
                }
            },
        )

        val rolesByName = (liveRun?.roles.orEmpty() + BuiltInRoles.all).associate { it.name to it.id }
        // A role that runs a script or an action rather than an agent is programmatic work: it wears the neuron, never a kept creature.
        val programmaticRoles = (liveRun?.roles.orEmpty() + BuiltInRoles.all)
            .filter { it.executionSource != com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
            .mapTo(hashSetOf()) { it.name }
        CompositionLocalProvider(
            LocalWorkflowLibraryHost provides workflowLibraryHost,
            LocalStableHost provides stableHost,
            LocalBreedPurchases provides breedPurchases,
            LocalEntire provides entire,
            LocalCreatureRig provides { label ->
                if (label in programmaticRoles) CreatureRigMapping.PROGRAMMATIC_RIG else stableHost.rigFor(rolesByName[label])
            },
        ) {
            Scaffold { paddingValues ->
              Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().launchBackdrop(launching)) {
                    ControlRoom(
                        destination = destination,
                        destinations = destinations,
                        onDestinationSelected = { target ->
                            if (target != destination) {
                                // The hive is home: reaching it clears the way back. Returning to a place
                                // already on the way back pops to it, so Back never ping-pongs and the
                                // history never grows past the places actually open.
                                val earlier = navigationHistory.indexOf(target)
                                navigationHistory = when {
                                    target == ControlRoomDestination.Overview -> emptyList()
                                    earlier >= 0 -> navigationHistory.take(earlier)
                                    else -> navigationHistory + destination
                                }
                                destinationName = target.name
                                if (target != ControlRoomDestination.Overview) {
                                    selectedTaskIdValue = ""
                                }
                            }
                        },
                        selectedTaskId = selectedTaskId,
                        onTaskSelected = { taskId ->
                            selectedTaskIdValue = if (selectedTaskId == taskId) "" else taskId
                        },
                        onLoadLaunchableRuns = { runtime?.loadLaunchableRuns() ?: emptyList() },
                        onLaunchWorkflow = { projectName, objective, repository, continuesWorkflowRunIds ->
                            val existingProject = when (val state = runtimeState) {
                                is ApplicationRuntimeState.NoRun -> state.project
                                // "Continue" from a finished run's view stays in that run's project.
                                is ApplicationRuntimeState.Live ->
                                    state.presentation.project.takeIf { continuesWorkflowRunIds.isNotEmpty() }
                                else -> null
                            }
                            launchObjective(projectName, objective, repository, continuesWorkflowRunIds, existingProject)
                        },
                        onApproveTask = { taskId ->
                            scope.launch {
                                try {
                                    runtime?.approveTask(TaskDefinitionId(taskId))
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_approval_failed))
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
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_plan_rejection_failed))
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
                                    runtimeState = failure.toRuntimeFailureState(getString(Res.string.app_escalation_decision_failed))
                                }
                            }
                        },
                        onMessageAgent = { taskId, message ->
                            try {
                                when (val result = runtime?.messageTask(TaskDefinitionId(taskId), message)) {
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
                        onLoadRunTimeline = { runtime?.loadRunTimeline() ?: emptyList() },
                        onLoadWorkflowDefinitions = { runtime?.persistence?.definitions?.all() ?: emptyList() },
                        onExportDiagnosticBundle = { runtime?.exportDiagnosticBundle() },
                        onValidateWorkflow = { runtime?.validateCurrentWorkflow() ?: emptyList() },
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
                        crashReportingSetting = crashReportingSetting,
                        remoteAccessSetting = remoteAccessSetting,
                        localPlannerSetting = localPlannerSetting,
                        localOrchestrationSpecialistSetting = localOrchestrationSpecialistSetting,
                        memoryLayer = memoryLayer,
                        azphaltStoreService = azphaltStoreService,
                        azphaltPackageImportRequest = azphaltPackageImportRequest,
                        onAzphaltPackageImportHandled = onAzphaltPackageImportHandled,
                        compact = maxWidth < ControlRoomBreakpoints.Wide,
                        contentPadding = paddingValues,
                        runtimeState = runtimeState,
                        connectedProviderIds = providers.map { it.id.value }.toSet(),
                    )
                }
                if (launching) {
                    LaunchStepsOverlay(rows = launchStepRows, maxRows = launchSteps.maxRows)
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
    MaterialTheme(
        colorScheme = GeministratorColors,
        content = content,
    )
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
