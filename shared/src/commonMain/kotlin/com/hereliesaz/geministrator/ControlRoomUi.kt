package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.resources.hive_show_timeline
import com.hereliesaz.geministrator.resources.hive_more_editing
import com.hereliesaz.geministrator.resources.hive_show_hive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.district_surface
import com.hereliesaz.geministrator.resources.nav_runtime
import com.hereliesaz.geministrator.resources.nav_runtime_disconnected
import com.hereliesaz.geministrator.resources.nav_runtime_loading
import com.hereliesaz.geministrator.resources.nav_runtime_no_project
import com.hereliesaz.geministrator.resources.nav_runtime_no_run
import com.hereliesaz.geministrator.resources.nav_runtime_resume_failed
import com.hereliesaz.geministrator.resources.nav_swarm_os
import com.hereliesaz.geministrator.resources.nav_the_aive
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

internal object ControlRoomBreakpoints {
    val Wide: Dp = 820.dp
}

enum class ControlRoomDestination(val label: String) {
    Overview("Overview"),
    Runs("Runs"),
    Workflows("Workflows"),
    Company("Swarm"),
    Artifacts("Artifacts"),
    Inbox("Inbox"),
    Repositories("Repositories"),
    Compute("Compute"),
    Memory("Memory"),
    AddOns("AZPHALT STORE"),
    Settings("Settings"),
    ;

    /** The main-navigation entry that is highlighted while this destination is open. */
    val navigationItem: ControlRoomDestination
        get() = if (this in OverviewSections || this in RetiredSections) Overview else this

    companion object {
        /**
         * Opened from the section row at the top of [Overview], not from the main navigation.
         * Overview itself shows the run mind map until one of these is tapped.
         */
        // Runs and Workflows are retired into the hive (docs/Swarm-OS.md); Workflows stays reachable from a hive's More editing.
        val OverviewSections: List<ControlRoomDestination> = listOf(Company)

        /** Reachable, but no longer shown in Overview's section row. */
        val RetiredSections: Set<ControlRoomDestination> = setOf(Runs, Workflows)

        /** Left out of the main navigation: Overview's sections, and Inbox (its header badge opens it). */
        val NotInNavigation: Set<ControlRoomDestination> = OverviewSections.toSet() + RetiredSections + Inbox

        /** Destinations shown in navigation; [Compute] only where the build offers distributed compute. */
        fun available(distributedComputeAvailable: Boolean): List<ControlRoomDestination> =
            entries.filter { distributedComputeAvailable || it != Compute }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlRoom(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
    destinations: List<ControlRoomDestination> = ControlRoomDestination.entries,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?, List<WorkflowRunId>) -> Unit,
    onLoadLaunchableRuns: suspend () -> List<LaunchableRun> = { emptyList() },
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
    projectFileService: ProjectFileService? = null,
    roleSurfaceFilePicker: RoleSurfaceFilePicker? = null,
    onExportCurrentProjectFile: suspend () -> IveProjectExport? = { null },
    onImportProjectFile: suspend (String) -> String? = { null },
    setupFileActions: SetupFileActions? = null,
    onLoadRunHistory: suspend () -> List<Pair<Project, List<WorkflowRun>>> = { emptyList() },
    onSwitchRun: (String) -> Unit = {},
    onLoadRunTimeline: suspend () -> List<WorkflowEvent> = { emptyList() },
    onLoadWorkflowDefinitions: suspend () -> List<WorkflowDefinition> = { emptyList() },
    onExportDiagnosticBundle: suspend () -> String? = { null },
    onValidateWorkflow: () -> List<String> = { emptyList() },
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    onResetRoleCollection: () -> Unit = {},
    onAssignWorkflowCompute: (WorkflowDefinitionId, ComputeDelegationTarget) -> Unit = { _, _ -> },
    onAssignRoleCompute: (WorkflowDefinitionId, RoleDefinitionId, ComputeDelegationTarget) -> Unit = { _, _, _ -> },
    onAssignTaskCompute: (WorkflowDefinitionId, TaskDefinitionId, ComputeDelegationTarget) -> Unit = { _, _, _ -> },
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (suspend () -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onConfigureRepositoryService: (String) -> Unit = {},
    onDisconnectRepositoryService: (String) -> Unit = {},
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    crashReportingSetting: CrashReportingSetting? = null,
    remoteAccessSetting: RemoteAccessSetting? = null,
    localPlannerSetting: LocalPlannerSetting? = null,
    localOrchestrationSpecialistSetting: LocalOrchestrationSpecialistSetting? = null,
    memoryLayer: com.hereliesaz.geministrator.memory.MemoryLayerController? = null,
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    compact: Boolean,
    contentPadding: PaddingValues,
    runtimeState: ApplicationRuntimeState,
    connectedProviderIds: Set<String> = emptySet(),
) {
    val ground = Azphalt.currentGround
    // Unread Inbox items: pending decisions, and the Hall Monitor pause. Opening the Inbox marks
    // everything it lists as read.
    val inboxReadLog = remember { InboxReadLog(Settings()) }
    val inboxKeys = remember(runtimeState) { inboxItemKeys(runtimeState) }
    val unreadInbox = unreadInboxCount(inboxKeys, inboxReadLog.read)
    LaunchedEffect(destination, inboxKeys) {
        if (destination == ControlRoomDestination.Inbox) inboxReadLog.markRead(inboxKeys)
    }
    val openInbox = { onDestinationSelected(ControlRoomDestination.Inbox) }
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val inspectorVisible = selectedTaskId != null &&
        liveWorkflow != null &&
        (destination == ControlRoomDestination.Overview || destination == ControlRoomDestination.Runs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground.page)
            .padding(contentPadding),
    ) {
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                CompactHeader(unreadInbox = unreadInbox, onOpenInbox = openInbox)
                CompactNavigation(destination, onDestinationSelected, destinations)
                MainDestination(
                    destination = destination,
                    onOpenSettings = { onDestinationSelected(ControlRoomDestination.Settings) },
                    onDestinationSelected = onDestinationSelected,
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = onTaskSelected,
                    onLaunchWorkflow = onLaunchWorkflow,
                onLoadLaunchableRuns = onLoadLaunchableRuns,
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
                    projectFileService = projectFileService,
                    roleSurfaceFilePicker = roleSurfaceFilePicker,
                    onExportCurrentProjectFile = onExportCurrentProjectFile,
                    onImportProjectFile = onImportProjectFile,
                    setupFileActions = setupFileActions,
                    onLoadRunHistory = onLoadRunHistory,
                    onSwitchRun = onSwitchRun,
                    onLoadRunTimeline = onLoadRunTimeline,
                    onLoadWorkflowDefinitions = onLoadWorkflowDefinitions,
                    onExportDiagnosticBundle = onExportDiagnosticBundle,
                    onValidateWorkflow = onValidateWorkflow,
                    onSaveRoleCollection = onSaveRoleCollection,
                    onResetRoleCollection = onResetRoleCollection,
                    onAssignWorkflowCompute = onAssignWorkflowCompute,
                    onAssignRoleCompute = onAssignRoleCompute,
                    onAssignTaskCompute = onAssignTaskCompute,
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
                    connectedProviderIds = connectedProviderIds,
                    modifier = Modifier.weight(1f),
                    compact = true,
                    runtimeState = runtimeState,
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                PillNavigation(
                    destination = destination,
                    onDestinationSelected = onDestinationSelected,
                    destinations = destinations,
                    runtimeState = runtimeState,
                    unreadInbox = unreadInbox,
                    onOpenInbox = openInbox,
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
                MainDestination(
                    destination = destination,
                    onOpenSettings = { onDestinationSelected(ControlRoomDestination.Settings) },
                    onDestinationSelected = onDestinationSelected,
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = onTaskSelected,
                    onLaunchWorkflow = onLaunchWorkflow,
                onLoadLaunchableRuns = onLoadLaunchableRuns,
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
                    projectFileService = projectFileService,
                    roleSurfaceFilePicker = roleSurfaceFilePicker,
                    onExportCurrentProjectFile = onExportCurrentProjectFile,
                    onImportProjectFile = onImportProjectFile,
                    setupFileActions = setupFileActions,
                    onLoadRunHistory = onLoadRunHistory,
                    onSwitchRun = onSwitchRun,
                    onLoadRunTimeline = onLoadRunTimeline,
                    onLoadWorkflowDefinitions = onLoadWorkflowDefinitions,
                    onExportDiagnosticBundle = onExportDiagnosticBundle,
                    onValidateWorkflow = onValidateWorkflow,
                    onSaveRoleCollection = onSaveRoleCollection,
                    onResetRoleCollection = onResetRoleCollection,
                    onAssignWorkflowCompute = onAssignWorkflowCompute,
                    onAssignRoleCompute = onAssignRoleCompute,
                    onAssignTaskCompute = onAssignTaskCompute,
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
                    connectedProviderIds = connectedProviderIds,
                    modifier = Modifier.weight(1f),
                    runtimeState = runtimeState,
                )
            }
        }

        if (inspectorVisible) {
            selectedTaskId?.let { taskId ->
                ModalBottomSheet(
                    onDismissRequest = { onTaskSelected(taskId) },
                    containerColor = Azphalt.Ink,
                    contentColor = Azphalt.Ink.onOpposite(),
                    dragHandle = { BottomSheetDefaults.DragHandle(color = Azphalt.Ink.mutedOnOpposite()) },
                ) {
                    AzphaltThemedSurface(Azphalt.Ink) {
                    TechnicalInspector(
                        selectedTaskId = taskId,
                        liveWorkflow = liveWorkflow,
                        onApproveTask = onApproveTask,
                        onRejectPlan = onRejectPlan,
                        onResolveEscalation = onResolveEscalation,
                        onMessageAgent = onMessageAgent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (compact) 0.72f else 0.62f),
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun PillNavigation(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
    destinations: List<ControlRoomDestination>,
    runtimeState: ApplicationRuntimeState,
    unreadInbox: Int,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entrance = remember { AzphaltEntrance.roll() }
    Column(
        modifier = modifier.padding(start = 14.dp, top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.nav_the_aive), style = AzphaltType.section, color = azphaltOnSurface())
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.nav_swarm_os),
                style = AzphaltType.eyebrow,
                color = azphaltOnSurface(),
                modifier = Modifier.weight(1f),
            )
            InboxBadge(unread = unreadInbox, onClick = onOpenInbox)
        }
        Spacer(Modifier.height(12.dp))
        val items = destinations.filterNot { it in ControlRoomDestination.NotInNavigation }
        items.forEachIndexed { index, item ->
            AzphaltPill(
                label = item.label,
                seed = "nav-$index-${item.name}",
                selected = item == destination.navigationItem,
                endCap = null,
                onClick = { onDestinationSelected(item) },
                modifier = Modifier
                    .fillMaxWidth(if (item == destination.navigationItem) 0.96f else 0.84f - (index % 3) * 0.03f)
                    .azphaltEntrance(entrance, index, items.size),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            runtimeStatusLabel(runtimeState),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
            modifier = Modifier.azphaltLiveRegion(),
        )
    }
}

@Composable
private fun CompactHeader(unreadInbox: Int, onOpenInbox: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(Res.string.nav_the_aive), style = AzphaltType.lead, color = azphaltOnSurface())
            Text(stringResource(Res.string.nav_swarm_os), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        }
        InboxBadge(unread = unreadInbox, onClick = onOpenInbox)
    }
}

@Composable
private fun CompactNavigation(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
    destinations: List<ControlRoomDestination>,
) {
    val entrance = remember { AzphaltEntrance.roll() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val items = destinations.filterNot { it in ControlRoomDestination.NotInNavigation }
        items.forEachIndexed { index, item ->
            AzphaltPill(
                label = item.label,
                seed = "compact-$index-${item.name}",
                selected = item == destination.navigationItem,
                onClick = { onDestinationSelected(item) },
                modifier = Modifier.azphaltEntrance(entrance, index, items.size),
            )
        }
    }
}

/**
 * Runs, Workflows and Swarm across the top of Overview. Tapping the open one returns to the
 * Overview mind map.
 */
@Composable
private fun OverviewSectionTabs(
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlRoomDestination.OverviewSections.forEachIndexed { index, section ->
            AzphaltPill(
                label = section.label,
                seed = "overview-section-$index-${section.name}",
                selected = section == destination,
                onClick = {
                    onDestinationSelected(if (section == destination) ControlRoomDestination.Overview else section)
                },
            )
        }
    }
}

@Composable
private fun MainDestination(
    destination: ControlRoomDestination,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?, List<WorkflowRunId>) -> Unit,
    onLoadLaunchableRuns: suspend () -> List<LaunchableRun> = { emptyList() },
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
    projectFileService: ProjectFileService?,
    roleSurfaceFilePicker: RoleSurfaceFilePicker?,
    onExportCurrentProjectFile: suspend () -> IveProjectExport?,
    onImportProjectFile: suspend (String) -> String?,
    setupFileActions: SetupFileActions?,
    onLoadRunHistory: suspend () -> List<Pair<Project, List<WorkflowRun>>>,
    onSwitchRun: (String) -> Unit,
    onLoadRunTimeline: suspend () -> List<WorkflowEvent>,
    onLoadWorkflowDefinitions: suspend () -> List<WorkflowDefinition>,
    onExportDiagnosticBundle: suspend () -> String?,
    onValidateWorkflow: () -> List<String>,
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit,
    onResetRoleCollection: () -> Unit,
    onAssignWorkflowCompute: (WorkflowDefinitionId, ComputeDelegationTarget) -> Unit,
    onAssignRoleCompute: (WorkflowDefinitionId, RoleDefinitionId, ComputeDelegationTarget) -> Unit,
    onAssignTaskCompute: (WorkflowDefinitionId, TaskDefinitionId, ComputeDelegationTarget) -> Unit,
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion>,
    availableRepositorySources: Set<RepositorySource>,
    onPickLocalRepository: (suspend () -> String?)?,
    connectedRepositoryServiceIds: Set<String>,
    onConfigureRepositoryService: (String) -> Unit,
    onDisconnectRepositoryService: (String) -> Unit,
    onReconfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    crashReportingSetting: CrashReportingSetting? = null,
    remoteAccessSetting: RemoteAccessSetting? = null,
    localPlannerSetting: LocalPlannerSetting? = null,
    localOrchestrationSpecialistSetting: LocalOrchestrationSpecialistSetting? = null,
    memoryLayer: com.hereliesaz.geministrator.memory.MemoryLayerController? = null,
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    connectedProviderIds: Set<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    runtimeState: ApplicationRuntimeState,
    onOpenSettings: () -> Unit = {},
    onDestinationSelected: (ControlRoomDestination) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        if (destination.navigationItem == ControlRoomDestination.Overview) {
            OverviewSectionTabs(destination, onDestinationSelected)
        }
        AzphaltPlaceTransition(target = destination, modifier = Modifier.weight(1f).fillMaxWidth()) { place ->
            when (place) {
                ControlRoomDestination.Overview -> {
                    // Semantic zoom (docs/Swarm-OS.md): the district is all the way out; a neighbourhood is one workflow.
                    var inDistrict by rememberDurableBooleanState("overview.zoom.district")
                    // A hive that never ran opens at rest (its id here); a hive that ran opens on its latest swarm.
                    var restingHiveId by rememberDurableStringState("overview.resting-hive")
                    var showTimeline by rememberDurableBooleanState("overview.timeline")
                    val hiveHost = LocalWorkflowLibraryHost.current
                    val hiveScope = rememberCoroutineScope()
                    if (inDistrict) {
                        DistrictScreen(
                            onLoadDefinitions = onLoadWorkflowDefinitions,
                            onLoadRuns = { onLoadRunHistory().map { (project, runs) -> project.name to runs } },
                            onEnterNeighbourhood = { neighbourhood ->
                                val runId = neighbourhood.latestRunId
                                if (runId != null) {
                                    onSwitchRun(runId)
                                    restingHiveId = ""
                                } else {
                                    restingHiveId = neighbourhood.definitionId
                                }
                                inDistrict = false
                            },
                            onNewHive = {
                                hiveHost?.let { host ->
                                    hiveScope.launch {
                                        val hive = createEmptyHive(host, onLoadWorkflowDefinitions())
                                        restingHiveId = hive.id.value
                                        inDistrict = false
                                    }
                                }
                            },
                            onImportAgent = { onDestinationSelected(ControlRoomDestination.AddOns) },
                            edge = {
                                azphaltStoreService?.let { store ->
                                HiveEdge(
                                    onLoadPackages = { store.search().packages },
                                    onBringIn = { item ->
                                        // Opens the Store on this package, where its install is approved.
                                        DurableUiState.store.putString("azphalt-store.query", "")
                                        DurableUiState.store.putString("azphalt-store.category", "All")
                                        DurableUiState.store.putString("azphalt-store.selected-package", item.id)
                                        onDestinationSelected(ControlRoomDestination.AddOns)
                                    },
                                )
                                }
                            },
                            roles = (runtimeState as? ApplicationRuntimeState.Live)?.presentation?.roles.orEmpty(),
                            onAdopt = { child ->
                                val roles = (runtimeState as? ApplicationRuntimeState.Live)?.presentation?.roles.orEmpty()
                                onSaveRoleCollection(
                                    roles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID } + child.copy(enabled = true),
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (restingHiveId.isNotBlank()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            AzphaltPill(
                                label = stringResource(Res.string.district_surface),
                                seed = "zoom-out-district",
                                onClick = { inDistrict = true },
                            )
                            RestingHive(
                                definitionId = restingHiveId,
                                onLoadDefinitions = onLoadWorkflowDefinitions,
                                selectedTaskId = selectedTaskId,
                                onTaskSelected = onTaskSelected,
                                onSurface = { inDistrict = true },
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.hive_more_editing),
                                seed = "hive-more-editing",
                                onClick = { onDestinationSelected(ControlRoomDestination.Workflows) },
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            val live = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AzphaltPill(
                                    label = stringResource(Res.string.district_surface),
                                    seed = "zoom-out-district",
                                    onClick = { inDistrict = true },
                                )
                                if (live != null) {
                                    AzphaltPill(
                                        label = stringResource(if (showTimeline) Res.string.hive_show_hive else Res.string.hive_show_timeline),
                                        seed = "hive-timeline",
                                        selected = showTimeline,
                                        onClick = { showTimeline = !showTimeline },
                                    )
                                }
                            }
                            live?.let {
                                // The hive's combs: its finished swarms. Opening one switches to it.
                                RunScars(
                                    currentRun = it.run,
                                    onLoadRuns = { onLoadRunHistory().flatMap { pair -> pair.second } },
                                    onReplay = onSwitchRun,
                                )
                            }
                            if (live != null && showTimeline) {
                                RunTimeline(
                                    runKey = live.run.id.value + live.run.updatedAtEpochMillis,
                                    onLoadTimeline = onLoadRunTimeline,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
        MindMapRunScreen(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            selectedTaskId = selectedTaskId,
                            onTaskSelected = onTaskSelected,
                            onLaunchWorkflow = onLaunchWorkflow,
                            onLoadLaunchableRuns = onLoadLaunchableRuns,
                            projectFileService = projectFileService,
                            onImportProjectFile = onImportProjectFile,
                            onRecoverFromCorruption = onRecoverFromCorruption,
                            onRetryRuntime = onRetryRuntime,
                            onOpenSettings = onOpenSettings,
                            onValidateWorkflow = onValidateWorkflow,
                            availableRepositorySources = availableRepositorySources,
                            onPickLocalRepository = onPickLocalRepository,
                            connectedRepositoryServiceIds = connectedRepositoryServiceIds,
                            onSearchRepositories = onSearchRepositories,
                            compact = compact,
                            onSurface = { inDistrict = true },
                            runtimeState = runtimeState,
                        )
                            }
                            live?.let { HiveLaunchBar(it.definition) }
                        }
                    }
                }
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
                    azphaltStoreService = azphaltStoreService,
                    azphaltPackageImportRequest = azphaltPackageImportRequest,
                    onAzphaltPackageImportHandled = onAzphaltPackageImportHandled,
                    modifier = Modifier.fillMaxSize(),
                )
                ControlRoomDestination.Company -> CustomCompanyProviderScreen(
                    runtimeState = runtimeState,
                    connectedProviderIds = connectedProviderIds,
                    onSaveRoleCollection = onSaveRoleCollection,
                    onResetRoleCollection = onResetRoleCollection,
                    roleSurfaceFilePicker = roleSurfaceFilePicker,
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
                ControlRoomDestination.Compute -> ComputeDelegationScreen(
                    runtimeState = runtimeState,
                    distributedComputeState = distributedComputeState,
                    onAssignWorkflow = onAssignWorkflowCompute,
                    onAssignRole = onAssignRoleCompute,
                    onAssignTask = onAssignTaskCompute,
                    modifier = Modifier.fillMaxSize(),
                )
                ControlRoomDestination.Memory -> MemoryLayerScreen(
                    controller = memoryLayer,
                    connectedProviderIds = connectedProviderIds,
                    modifier = Modifier.fillMaxSize(),
                )
                ControlRoomDestination.AddOns -> AzphaltStoreScreen(
                    service = azphaltStoreService,
                    importRequest = azphaltPackageImportRequest,
                    onImportHandled = onAzphaltPackageImportHandled,
                    connectedProviderIds = connectedProviderIds,
                    onConnectProvider = onReconfigureProvider,
                    onDisconnectProvider = onDisconnectProvider,
                    modifier = Modifier.fillMaxSize(),
                )
                ControlRoomDestination.Settings -> ProviderSettingsScreen(
                    connectedProviderIds = connectedProviderIds,
                    onCheckProviderHealth = onCheckProviderHealth,
                    onClearWorkflowData = onClearWorkflowData,
                    onExportJson = onExportJson,
                    onImportJson = onImportJson,
                    projectFileService = projectFileService,
                    onExportCurrentProjectFile = onExportCurrentProjectFile,
                    onImportProjectFile = onImportProjectFile,
                    setupFileActions = setupFileActions,
                    onExportDiagnosticBundle = onExportDiagnosticBundle,
                    onConfigureProvider = onReconfigureProvider,
                    onDisconnectProvider = onDisconnectProvider,
                    distributedComputeState = distributedComputeState,
                    onSaveDistributedCompute = onSaveDistributedCompute,
                    onDisconnectDistributedCompute = onDisconnectDistributedCompute,
                    crashReportingSetting = crashReportingSetting,
                    remoteAccessSetting = remoteAccessSetting,
                    localPlannerSetting = localPlannerSetting,
                    localOrchestrationSpecialistSetting = localOrchestrationSpecialistSetting,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun runtimeStatusLabel(state: ApplicationRuntimeState): String = when (state) {
    ApplicationRuntimeState.Loading -> stringResource(Res.string.nav_runtime_loading)
    is ApplicationRuntimeState.NoProject -> stringResource(Res.string.nav_runtime_no_project)
    is ApplicationRuntimeState.NoRun -> stringResource(Res.string.nav_runtime_no_run)
    is ApplicationRuntimeState.Live -> stringResource(Res.string.nav_runtime, state.presentation.run.status.name.uppercase())
    is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.nav_runtime_disconnected)
    is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.nav_runtime_resume_failed)
}
