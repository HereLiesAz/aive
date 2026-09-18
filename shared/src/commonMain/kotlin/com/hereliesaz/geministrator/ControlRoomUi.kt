package com.hereliesaz.geministrator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import kotlin.math.roundToInt

internal object ControlRoomBreakpoints {
    val Wide: Dp = 820.dp
}

private val InspectorEase = CubicBezierEasing(0f, .9f, .1f, 1f)

enum class ControlRoomDestination(val label: String) {
    Overview("Overview"),
    Runs("Runs"),
    Workflows("Workflows"),
    Company("Company"),
    Artifacts("Artifacts"),
    Inbox("Inbox"),
    Repositories("Repositories"),
    AddOns("ADD-ONS"),
    Settings("Settings"),
}

internal enum class WorkState(val label: String) {
    Complete("Complete"),
    Working("Working"),
    Waiting("Waiting"),
    Gate("Gate"),
    Blocked("Blocked"),
}

internal data class WorkNode(
    val id: String,
    val position: String,
    val assignment: String,
    val state: WorkState,
    val progress: Float? = null,
    val staffing: String? = null,
    val detail: String? = null,
    val injectedReason: String? = null,
)

internal val ActiveWorkflow = listOf(
    WorkNode("product", "Product Manager", "Define authentication requirements", WorkState.Complete, detail = "Requirements approved"),
    WorkNode("architect", "Architect", "Define authentication architecture", WorkState.Complete, staffing = "Jules", detail = "Plan approved"),
    WorkNode("pre-code", "Crash Test Dummy", "Build pre-code verification contract", WorkState.Complete, staffing = "Jules", detail = "4 verification artifacts", injectedReason = "Pre-code verification policy"),
    WorkNode("epa", "EPA Representative", "Specify execution environment", WorkState.Complete, staffing = "Jules", detail = "Ephemeral · restricted network", injectedReason = "Provider environment policy"),
    WorkNode("implementation", "Implementation Engineer", "Implement authentication", WorkState.Working, progress = .62f, staffing = "Jules", detail = "Attempt 1 · active 08:41"),
    WorkNode("post-code", "Crash Test Dummy", "Author regression tests", WorkState.Waiting, staffing = "Jules", detail = "Waiting for implementation", injectedReason = "Post-code test policy"),
    WorkNode("qa", "QA Engineer", "Falsify completion claims", WorkState.Blocked, detail = "Blocked by post-code tests"),
    WorkNode("review", "Code Reviewer", "Review implementation independently", WorkState.Blocked, detail = "Blocked by QA"),
    WorkNode("release", "Release Engineer", "Approve integration and release", WorkState.Blocked, detail = "Blocked by review"),
)

@Composable
fun ControlRoom(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?) -> Unit,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    onMessageAgent: suspend (String, String) -> String? = { _, _ -> "Messaging is unavailable" },
    onRecoverFromCorruption: () -> Unit,
    onRetryRuntime: () -> Unit = {},
    onCheckProviderHealth: suspend () -> Map<String, String> = { emptyMap() },
    onClearWorkflowData: () -> Unit = {},
    onExportJson: suspend () -> String? = { null },
    onImportJson: (String) -> Unit = {},
    onLoadRunHistory: suspend () -> List<Pair<Project, List<WorkflowRun>>> = { emptyList() },
    onSwitchRun: (String) -> Unit = {},
    onLoadRunTimeline: suspend () -> List<WorkflowEvent> = { emptyList() },
    onLoadWorkflowDefinitions: suspend () -> List<WorkflowDefinition> = { emptyList() },
    onExportDiagnosticBundle: suspend () -> String? = { null },
    onValidateWorkflow: () -> List<String> = { emptyList() },
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    onResetRoleCollection: () -> Unit = {},
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (() -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onConfigureRepositoryService: (String) -> Unit = {},
    onDisconnectRepositoryService: (String) -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    compact: Boolean,
    contentPadding: PaddingValues,
    runtimeState: ApplicationRuntimeState,
    connectedProviderIds: Set<String> = emptySet(),
) {
    val ground = Azphalt.currentGround
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val inspectorVisible = selectedTaskId != null &&
        liveWorkflow != null &&
        (destination == ControlRoomDestination.Overview || destination == ControlRoomDestination.Runs)
    var dismissedInspectorId by remember { mutableStateOf<String?>(null) }
    val compactInspectorVisible = inspectorVisible && selectedTaskId != dismissedInspectorId

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground.page)
            .padding(contentPadding),
    ) {
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                CompactHeader()
                CompactNavigation(destination, onDestinationSelected)
                MainDestination(
                    destination = destination,
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = { taskId ->
                        dismissedInspectorId = null
                        onTaskSelected(taskId)
                    },
                    onLaunchWorkflow = onLaunchWorkflow,
                    onApproveTask = onApproveTask,
                    onRejectPlan = onRejectPlan,
                    onResolveEscalation = onResolveEscalation,
                    onMessageAgent = onMessageAgent,
                    onRecoverFromCorruption = onRecoverFromCorruption,
                    onRetryRuntime = onRetryRuntime,
                    onCheckProviderHealth = onCheckProviderHealth,
                    onClearWorkflowData = onClearWorkflowData,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                    onLoadRunHistory = onLoadRunHistory,
                    onSwitchRun = onSwitchRun,
                    onLoadRunTimeline = onLoadRunTimeline,
                    onLoadWorkflowDefinitions = onLoadWorkflowDefinitions,
                    onExportDiagnosticBundle = onExportDiagnosticBundle,
                    onValidateWorkflow = onValidateWorkflow,
                    onSaveRoleCollection = onSaveRoleCollection,
                    onResetRoleCollection = onResetRoleCollection,
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
                    azphaltStoreService = azphaltStoreService,
                    azphaltPackageImportRequest = azphaltPackageImportRequest,
                    onAzphaltPackageImportHandled = onAzphaltPackageImportHandled,
                    connectedProviderIds = connectedProviderIds,
                    modifier = Modifier.weight(1f),
                    compact = true,
                    runtimeState = runtimeState,
                )
            }

            AnimatedVisibility(
                visible = compactInspectorVisible,
                enter = slideInVertically(animationSpec = tween(280, easing = InspectorEase)) { it } + fadeIn(tween(140)),
                exit = slideOutVertically(animationSpec = tween(220, easing = InspectorEase)) { it } + fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                selectedTaskId?.let { taskId ->
                    CompactInspectorSheet(
                        taskId = taskId,
                        liveWorkflow = liveWorkflow,
                        onDismiss = { dismissedInspectorId = taskId },
                        onApproveTask = onApproveTask,
                        onRejectPlan = onRejectPlan,
                        onResolveEscalation = onResolveEscalation,
                        onMessageAgent = onMessageAgent,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                PillNavigation(
                    destination = destination,
                    onDestinationSelected = onDestinationSelected,
                    runtimeState = runtimeState,
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
                MainDestination(
                    destination = destination,
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = onTaskSelected,
                    onLaunchWorkflow = onLaunchWorkflow,
                    onApproveTask = onApproveTask,
                    onRejectPlan = onRejectPlan,
                    onResolveEscalation = onResolveEscalation,
                    onMessageAgent = onMessageAgent,
                    onRecoverFromCorruption = onRecoverFromCorruption,
                    onRetryRuntime = onRetryRuntime,
                    onCheckProviderHealth = onCheckProviderHealth,
                    onClearWorkflowData = onClearWorkflowData,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                    onLoadRunHistory = onLoadRunHistory,
                    onSwitchRun = onSwitchRun,
                    onLoadRunTimeline = onLoadRunTimeline,
                    onLoadWorkflowDefinitions = onLoadWorkflowDefinitions,
                    onExportDiagnosticBundle = onExportDiagnosticBundle,
                    onValidateWorkflow = onValidateWorkflow,
                    onSaveRoleCollection = onSaveRoleCollection,
                    onResetRoleCollection = onResetRoleCollection,
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
                    azphaltStoreService = azphaltStoreService,
                    azphaltPackageImportRequest = azphaltPackageImportRequest,
                    onAzphaltPackageImportHandled = onAzphaltPackageImportHandled,
                    connectedProviderIds = connectedProviderIds,
                    modifier = Modifier.weight(1f),
                    runtimeState = runtimeState,
                )
                AnimatedVisibility(
                    visible = inspectorVisible,
                    enter = expandHorizontally(
                        animationSpec = tween(360, easing = InspectorEase),
                        expandFrom = Alignment.Start,
                    ) + fadeIn(tween(150)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(280, easing = InspectorEase),
                        shrinkTowards = Alignment.Start,
                    ) + fadeOut(tween(120)),
                ) {
                    selectedTaskId?.let { taskId ->
                        TechnicalInspector(
                            selectedTaskId = taskId,
                            liveWorkflow = liveWorkflow,
                            onApproveTask = onApproveTask,
                            onRejectPlan = onRejectPlan,
                            onResolveEscalation = onResolveEscalation,
                            onMessageAgent = onMessageAgent,
                            modifier = Modifier.width(310.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInspectorSheet(
    taskId: String,
    liveWorkflow: LiveWorkflowPresentation?,
    onDismiss: () -> Unit,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    onMessageAgent: suspend (String, String) -> String?,
) {
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 84.dp.toPx() }
    var dragOffsetPx by remember(taskId) { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.48f)
            .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Azphalt.Ink),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .pointerInput(taskId, dismissThresholdPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dragOffsetPx >= dismissThresholdPx) {
                                onDismiss()
                            } else {
                                dragOffsetPx = 0f
                            }
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 54.dp, height = 5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Azphalt.White.copy(alpha = 0.35f)),
            )
            Text(
                text = "×",
                style = AzphaltType.section,
                color = Azphalt.White,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
        TechnicalInspector(
            selectedTaskId = taskId,
            liveWorkflow = liveWorkflow,
            onApproveTask = onApproveTask,
            onRejectPlan = onRejectPlan,
            onResolveEscalation = onResolveEscalation,
            onMessageAgent = onMessageAgent,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun PillNavigation(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
    runtimeState: ApplicationRuntimeState,
    modifier: Modifier = Modifier,
) {
    val entrance = remember { AzphaltEntrance.roll() }
    Column(
        modifier = modifier.padding(start = 14.dp, top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("THE HAIVE", style = AzphaltType.section, color = Azphalt.currentGround.onPage)
        Text("COMPANY OS", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
        Spacer(Modifier.height(12.dp))
        ControlRoomDestination.entries.forEachIndexed { index, item ->
            AzphaltPill(
                label = item.label,
                seed = "nav-$index-${item.name}",
                selected = item == destination,
                endCap = null,
                onClick = { onDestinationSelected(item) },
                modifier = Modifier
                    .fillMaxWidth(if (item == destination) 0.96f else 0.84f - (index % 3) * 0.03f)
                    .azphaltEntrance(entrance, index, ControlRoomDestination.entries.size),
            )
        }
        Spacer(Modifier.weight(1f))
        AzphaltPill(
            label = Azphalt.currentGround.name,
            seed = "ground",
            endCap = "Reroll",
            onClick = { Azphalt.rerollGround() },
            modifier = Modifier.fillMaxWidth(0.9f),
        )
        Text(runtimeStatusLabel(runtimeState), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
    }
}

@Composable
private fun CompactHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("THE HAIVE", style = AzphaltType.lead, color = Azphalt.currentGround.onPage)
            Text("COMPANY OS", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
        }
        AzphaltPill("Ground", "compact-ground", endCap = Azphalt.currentGround.name, onClick = { Azphalt.rerollGround() })
    }
}

@Composable
private fun CompactNavigation(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    val entrance = remember { AzphaltEntrance.roll() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlRoomDestination.entries.forEachIndexed { index, item ->
            AzphaltPill(
                label = item.label,
                seed = "compact-$index-${item.name}",
                selected = item == destination,
                onClick = { onDestinationSelected(item) },
                modifier = Modifier.azphaltEntrance(entrance, index, ControlRoomDestination.entries.size),
            )
        }
    }
}

@Composable
private fun MainDestination(
    destination: ControlRoomDestination,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?) -> Unit,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    onMessageAgent: suspend (String, String) -> String?,
    onRecoverFromCorruption: () -> Unit,
    onRetryRuntime: () -> Unit,
    onCheckProviderHealth: suspend () -> Map<String, String>,
    onClearWorkflowData: () -> Unit,
    onExportJson: suspend () -> String?,
    onImportJson: (String) -> Unit,
    onLoadRunHistory: suspend () -> List<Pair<Project, List<WorkflowRun>>>,
    onSwitchRun: (String) -> Unit,
    onLoadRunTimeline: suspend () -> List<WorkflowEvent>,
    onLoadWorkflowDefinitions: suspend () -> List<WorkflowDefinition>,
    onExportDiagnosticBundle: suspend () -> String?,
    onValidateWorkflow: () -> List<String>,
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit,
    onResetRoleCollection: () -> Unit,
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion>,
    availableRepositorySources: Set<RepositorySource>,
    onPickLocalRepository: (() -> String?)?,
    connectedRepositoryServiceIds: Set<String>,
    onConfigureRepositoryService: (String) -> Unit,
    onDisconnectRepositoryService: (String) -> Unit,
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    connectedProviderIds: Set<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    runtimeState: ApplicationRuntimeState,
) {
    AzphaltPlaceTransition(target = destination, modifier = modifier.fillMaxSize()) { place ->
        when (place) {
            ControlRoomDestination.Overview -> MindMapRunScreen(
                modifier = Modifier.fillMaxSize(),
                selectedTaskId = selectedTaskId,
                onTaskSelected = onTaskSelected,
                onLaunchWorkflow = onLaunchWorkflow,
                onRecoverFromCorruption = onRecoverFromCorruption,
                onRetryRuntime = onRetryRuntime,
                onReconfigureProvider = onReconfigureProvider,
                onValidateWorkflow = onValidateWorkflow,
                availableRepositorySources = availableRepositorySources,
                onPickLocalRepository = onPickLocalRepository,
                connectedRepositoryServiceIds = connectedRepositoryServiceIds,
                onSearchRepositories = onSearchRepositories,
                compact = compact,
                runtimeState = runtimeState,
            )
            ControlRoomDestination.Runs -> RunsScreen(
                runtimeState = runtimeState,
                onLoadRunHistory = onLoadRunHistory,
                onSwitchRun = onSwitchRun,
                onLoadRunTimeline = onLoadRunTimeline,
                modifier = Modifier.fillMaxSize(),
            )
            ControlRoomDestination.Workflows -> LiveWorkflowLibraryScreen(
                runtimeState = runtimeState,
                onLoadDefinitions = onLoadWorkflowDefinitions,
                modifier = Modifier.fillMaxSize(),
            )
            ControlRoomDestination.Company -> CustomCompanyProviderScreen(
                runtimeState = runtimeState,
                connectedProviderIds = connectedProviderIds,
                onSaveRoleCollection = onSaveRoleCollection,
                onResetRoleCollection = onResetRoleCollection,
                modifier = Modifier.fillMaxSize(),
            )
            ControlRoomDestination.Artifacts -> LiveArtifactBrowserScreen(runtimeState, Modifier.fillMaxSize())
            ControlRoomDestination.Inbox -> LiveInboxScreen(runtimeState, onApproveTask, onRejectPlan, onResolveEscalation, Modifier.fillMaxSize())
            ControlRoomDestination.Repositories -> RepositoryServiceScreen(
                connectedServiceIds = connectedRepositoryServiceIds,
                onConfigureService = onConfigureRepositoryService,
                onDisconnectService = onDisconnectRepositoryService,
                modifier = Modifier.fillMaxSize(),
            )
            ControlRoomDestination.AddOns -> AzphaltStoreScreen(
                service = azphaltStoreService,
                importRequest = azphaltPackageImportRequest,
                onImportHandled = onAzphaltPackageImportHandled,
                modifier = Modifier.fillMaxSize(),
            )
            ControlRoomDestination.Settings -> ProviderSettingsScreen(
                connectedProviderIds = connectedProviderIds,
                onCheckProviderHealth = onCheckProviderHealth,
                onClearWorkflowData = onClearWorkflowData,
                onExportJson = onExportJson,
                onImportJson = onImportJson,
                onExportDiagnosticBundle = onExportDiagnosticBundle,
                onConfigureProvider = onReconfigureProvider,
                onDisconnectProvider = onDisconnectProvider,
                distributedComputeState = distributedComputeState,
                onSaveDistributedCompute = onSaveDistributedCompute,
                onDisconnectDistributedCompute = onDisconnectDistributedCompute,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun runtimeStatusLabel(state: ApplicationRuntimeState): String = when (state) {
    ApplicationRuntimeState.Loading -> "RUNTIME · LOADING"
    ApplicationRuntimeState.NoProject -> "RUNTIME · NO PROJECT"
    is ApplicationRuntimeState.NoRun -> "RUNTIME · NO RUN"
    is ApplicationRuntimeState.Live -> "RUNTIME · ${state.presentation.run.status.name.uppercase()}"
    is ApplicationRuntimeState.Disconnected -> "RUNTIME · DISCONNECTED"
    is ApplicationRuntimeState.ResumeFailed -> "RUNTIME · RESUME FAILED"
}
