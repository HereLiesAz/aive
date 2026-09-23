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
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.RoleSurfaceRuntimeRegistry
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import com.hereliesaz.geministrator.orchestration.LaunchProgress
import kotlinx.coroutines.launch

@Composable
fun App(
    providers: Collection<AgentProvider>,
    executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
    roleSurfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
    orchestrationRuntime: OrchestrationAgentRuntime? = null,
    persistence: WorkflowPersistence? = null,
    projectFileService: ProjectFileService? = null,
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (() -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    onConfigureRepositoryService: (String) -> Unit = {},
    onDisconnectRepositoryService: (String) -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    crashReportingSetting: CrashReportingSetting? = null,
) {
    val scope = rememberCoroutineScope()
    val workflowPersistence = remember(persistence) { persistence ?: SettingsWorkflowPersistence.createDefault() }
    var runtimeState by remember { mutableStateOf<ApplicationRuntimeState>(ApplicationRuntimeState.Loading) }
    var runtime by remember { mutableStateOf<ApplicationRuntime?>(null) }
    var runtimeGeneration by remember { mutableStateOf(0) }
    var launching by remember { mutableStateOf(false) }
    val launchSteps = remember { LiveStepsFeedModel(maxRows = 5) }
    val launchStepRows by launchSteps.rows.collectAsState()
    var projectIdToOpenAfterReload by remember { mutableStateOf<String?>(null) }
    var automaticProjectRestoreAttempted by remember(projectFileService) { mutableStateOf(false) }

    LaunchedEffect(providers, executorIntegrations, roleSurfaceRuntime, workflowPersistence, runtimeGeneration) {
        runtime?.close()
        runtime = null
        runtimeState = ApplicationRuntimeState.Loading
        try {
            val created = ApplicationRuntime.create(
                providers = providers,
                scope = scope,
                persistence = workflowPersistence,
                executorIntegrations = executorIntegrations,
                roleSurfaceRuntime = roleSurfaceRuntime,
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
            runtimeState = failure.toRuntimeFailureState("Runtime bootstrap failed")
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

    GeministratorTheme {
        var destinationName by remember {
            mutableStateOf(
                startupControlRoomDestination(
                    hasConfiguredProvider = providers.isNotEmpty(),
                ).name,
            )
        }
        val destination = ControlRoomDestination.entries
            .firstOrNull { it.name == destinationName }
            ?: startupControlRoomDestination(hasConfiguredProvider = providers.isNotEmpty())
        var selectedTaskIdValue by rememberDurableStringState("navigation.selected-task-id")
        val selectedTaskId = selectedTaskIdValue.takeIf(String::isNotBlank)
        var navigationHistory by remember { mutableStateOf(emptyList<ControlRoomDestination>()) }
        val taskInspectorVisible = selectedTaskId != null &&
            (destination == ControlRoomDestination.Overview || destination == ControlRoomDestination.Runs)

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
            launchWorkflow = { definition, packageRoles, objective ->
                val activeRuntime = runtime ?: error("Runtime is unavailable")
                val existingProject = when (val state = runtimeState) {
                    is ApplicationRuntimeState.NoRun -> state.project
                    is ApplicationRuntimeState.Live -> state.presentation.project
                    else -> null
                }
                activeRuntime.launchSavedWorkflow(
                    definition = definition,
                    existingProject = existingProject,
                    objective = objective ?: definition.description ?: definition.name,
                    supplementalRoles = packageRoles,
                )
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
                        val activeRuntime = runtime ?: error("Runtime is unavailable")
                        val existingProject = when (val state = runtimeState) {
                            is ApplicationRuntimeState.NoRun -> state.project
                            is ApplicationRuntimeState.Live -> state.presentation.project
                            else -> null
                        }
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
                }
            },
        )

        CompositionLocalProvider(LocalWorkflowLibraryHost provides workflowLibraryHost) {
            Scaffold { paddingValues ->
              Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().launchBackdrop(launching)) {
                    ControlRoom(
                        destination = destination,
                        onDestinationSelected = { target ->
                            if (target != destination) {
                                navigationHistory = navigationHistory + destination
                                destinationName = target.name
                                if (
                                    target != ControlRoomDestination.Overview &&
                                    target != ControlRoomDestination.Runs
                                ) {
                                    selectedTaskIdValue = ""
                                }
                            }
                        },
                        selectedTaskId = selectedTaskId,
                        onTaskSelected = { taskId ->
                            selectedTaskIdValue = if (selectedTaskId == taskId) "" else taskId
                        },
                        onLaunchWorkflow = onLaunch@{ projectName, objective, repository ->
                            val existingProject = (runtimeState as? ApplicationRuntimeState.NoRun)?.project
                            if (launching) {
                                platformDebugLog("AiveLaunch", "Launch ignored: a launch is already in progress")
                                return@onLaunch
                            }
                            launching = true
                            launchSteps.clear()
                            scope.launch(LaunchProgress(launchSteps::push)) {
                                try {
                                    platformDebugLog(
                                        "AiveLaunch",
                                        "Launch started: orchestrated=${orchestrationRuntime != null} runtime=${runtime != null}",
                                    )
                                    val activeRuntime = runtime ?: error("Runtime is unavailable")
                                    if (orchestrationRuntime != null) {
                                        activeRuntime.launchOrchestratedWorkflow(
                                            projectName = projectName,
                                            objective = objective,
                                            orchestrationRuntime = orchestrationRuntime,
                                            repository = repository,
                                            existingProject = existingProject,
                                        )
                                    } else {
                                        launchSteps.push("Starting the starter workflow…")
                                        activeRuntime.launchStarterWorkflow(
                                            projectName = projectName,
                                            objective = objective,
                                            repository = repository,
                                            existingProject = existingProject,
                                        )
                                    }
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Throwable) {
                                    // Catch Throwable, not Exception: on-device model failures such as
                                    // OutOfMemoryError are Errors and previously escaped this handler,
                                    // leaving the launch button silently doing nothing.
                                    platformDebugLog("AiveLaunch", "Launch failed: ${failure::class.simpleName}: ${failure.message}")
                                    runtimeState = failure.toRuntimeFailureState("Workflow launch failed")
                                } finally {
                                    launching = false
                                }
                            }
                        },
                        onApproveTask = { taskId ->
                            scope.launch {
                                try {
                                    runtime?.approveTask(TaskDefinitionId(taskId))
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState("Approval failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Plan rejection failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Escalation decision failed")
                                }
                            }
                        },
                        onMessageAgent = { taskId, message ->
                            try {
                                when (val result = runtime?.messageTask(TaskDefinitionId(taskId), message)) {
                                    null -> "Runtime is unavailable"
                                    ProviderActionResult.Accepted -> null
                                    is ProviderActionResult.Rejected -> result.reason
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                failure.message?.takeIf(String::isNotBlank) ?: "Provider message failed"
                            }
                        },
                        onRecoverFromCorruption = {
                            scope.launch {
                                try {
                                    runtime?.recoverFromCorruption()
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState("Recovery failed")
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
                                        runtimeState = failure.toRuntimeFailureState("Retry failed")
                                    }
                                }
                            }
                        },
                        onCheckProviderHealth = {
                            runtime?.checkProviderHealth()?.mapValues { (_, result) ->
                                result.fold(
                                    onSuccess = { caps -> "Reachable · ${caps.supported.size} capabilities" },
                                    onFailure = { failure ->
                                        "Unreachable · ${failure.message?.take(60) ?: "unknown error"}"
                                    },
                                )
                            } ?: emptyMap()
                        },
                        onClearWorkflowData = {
                            scope.launch {
                                try {
                                    (runtime?.persistence as? SettingsWorkflowPersistence)?.clearWorkflowData()
                                    runtime?.loadLatest()
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState("Clear failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Import failed")
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
                        onLoadRunHistory = { runtime?.loadRunHistory() ?: emptyList() },
                        onSwitchRun = { runId ->
                            scope.launch {
                                try {
                                    runtime?.switchToRun(WorkflowRunId(runId))
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    runtimeState = failure.toRuntimeFailureState("Switch run failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Save swarm failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Reset swarm failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Workflow delegation failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Role delegation failed")
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
                                    runtimeState = failure.toRuntimeFailureState("Task delegation failed")
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


internal fun startupControlRoomDestination(
    hasConfiguredProvider: Boolean,
): ControlRoomDestination =
    if (hasConfiguredProvider) ControlRoomDestination.Overview else ControlRoomDestination.Settings
