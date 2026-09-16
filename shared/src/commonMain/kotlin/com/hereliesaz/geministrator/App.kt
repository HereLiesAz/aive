package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun App(
    providers: Collection<AgentProvider>,
    executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
    orchestrationRuntime: OrchestrationAgentRuntime? = null,
    azphaltStoreService: AzphaltStoreService? = null,
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (() -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    onConfigureRepositoryService: (String) -> Unit = {},
    onDisconnectRepositoryService: (String) -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var runtimeState by remember { mutableStateOf<ApplicationRuntimeState>(ApplicationRuntimeState.Loading) }
    var runtime by remember { mutableStateOf<ApplicationRuntime?>(null) }
    var runtimeGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(providers, executorIntegrations, runtimeGeneration) {
        runtime?.close()
        runtime = null
        runtimeState = ApplicationRuntimeState.Loading
        try {
            val created = ApplicationRuntime.create(
                providers = providers,
                scope = scope,
                executorIntegrations = executorIntegrations,
            )
            runtime = created
            created.state.collectLatest { runtimeState = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            runtimeState = failure.toRuntimeFailureState("Runtime bootstrap failed")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runtime?.close()
            runtime = null
        }
    }

    GeministratorTheme {
        var destination by remember { mutableStateOf(ControlRoomDestination.Settings) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(runtimeState) {
            val live = runtimeState as? ApplicationRuntimeState.Live
            if (live == null) {
                selectedTaskId = null
            } else if (selectedTaskId != null && live.presentation.definition.tasks.none { it.id.value == selectedTaskId }) {
                selectedTaskId = null
            }
        }

        Scaffold { paddingValues ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                ControlRoom(
                    destination = destination,
                    onDestinationSelected = { destination = it },
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = { taskId ->
                        selectedTaskId = if (selectedTaskId == taskId) null else taskId
                    },
                    onLaunchWorkflow = { projectName, objective, repository ->
                        val existingProject = (runtimeState as? ApplicationRuntimeState.NoRun)?.project
                        scope.launch {
                            try {
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
                                    activeRuntime.launchStarterWorkflow(
                                        projectName = projectName,
                                        objective = objective,
                                        repository = repository,
                                        existingProject = existingProject,
                                    )
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                runtimeState = failure.toRuntimeFailureState("Workflow launch failed")
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
                                runtimeState = failure.toRuntimeFailureState("Save company failed")
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
                                runtimeState = failure.toRuntimeFailureState("Reset company failed")
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
                    azphaltStoreService = azphaltStoreService,
                    compact = maxWidth < ControlRoomBreakpoints.Wide,
                    contentPadding = paddingValues,
                    runtimeState = runtimeState,
                    connectedProviderIds = providers.map { it.id.value }.toSet(),
                )
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
