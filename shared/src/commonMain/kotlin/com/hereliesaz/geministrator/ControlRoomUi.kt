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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
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
import com.hereliesaz.geministrator.resources.nav_runtime
import com.hereliesaz.geministrator.resources.nav_runtime_disconnected
import com.hereliesaz.geministrator.resources.nav_runtime_loading
import com.hereliesaz.geministrator.resources.nav_runtime_no_project
import com.hereliesaz.geministrator.resources.nav_runtime_no_run
import com.hereliesaz.geministrator.resources.nav_runtime_resume_failed
import com.hereliesaz.geministrator.resources.crumb_here
import com.hereliesaz.geministrator.resources.crumb_hive
import com.hereliesaz.geministrator.resources.crumb_label
import com.hereliesaz.geministrator.resources.nav_the_aive
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

internal object ControlRoomBreakpoints {
    val Wide: Dp = 820.dp
}

enum class ControlRoomDestination(val label: String) {
    Overview("Overview"),
    Workflows("Workflows"),
    Company("Swarm"),
    Inbox("Inbox"),
    Compute("Compute"),
    Memory("Memory swarm"),
    AddOns("AZPHALT STORE"),
    Settings("Keeper's room"),
    ;

    /** The place this destination belongs to: the hive, or the Keeper's room. */
    val navigationItem: ControlRoomDestination
        get() = when {
            this in OverviewSections || this in RetiredSections -> Overview
            this in KeeperParts -> Settings
            else -> this
        }

    companion object {
        /**
         * The hive's own places, in the row across its top (docs/Keepers-Room.md). There is no other
         * navigation: the breadcrumb leads back up, the header opens the Inbox and the Keeper's room.
         */
        // Memory is a swarm of its own, running beside every run: it lives in the hive, not the Keeper's room.
        // The Store is also the district's edge. The Swarm keeps adding, arranging and resetting roles;
        // one role's mind is changed in its creature's panel.
        val OverviewSections: List<ControlRoomDestination> = listOf(Company, Memory, AddOns)

        /**
         * Reachable, but not in the hive's row (docs/Keepers-Room.md, stage 3): Workflows opens from a
         * hive's More editing; the Inbox is the Hall Monitor's pause. Runs became the hive's combs and
         * Artifacts each creature's Work.
         */
        val RetiredSections: Set<ControlRoomDestination> = setOf(Workflows, Inbox)

        /** Parts of the Keeper's room ([Settings]) that once had their own place (docs/Keepers-Room.md). */
        val KeeperParts: Set<ControlRoomDestination> = setOf(Compute)

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
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    // Semantic zoom (docs/Swarm-OS.md): the district is all the way out; a neighbourhood is one workflow.
    // Held here so the breadcrumb, Back and the hive agree on where the keeper is.
    val district = rememberDurableBooleanState("overview.zoom.district")
    // A hive that never ran opens at rest (its id here); a hive that ran opens on its latest swarm.
    val restingHive = rememberDurableStringState("overview.resting-hive")
    val restingDefinition by produceState<WorkflowDefinition?>(null, restingHive.value) {
        value = restingHive.value.takeIf(String::isNotBlank)?.let { id ->
            runCatching { onLoadWorkflowDefinitions() }.getOrDefault(emptyList()).firstOrNull { it.id.value == id }
        }
    }
    // The Keeper's room's open part, held here so the breadcrumb can name it.
    val keeperSectionName = rememberDurableStringState("keeper.section", KeeperSection.Connections.name)
    val keeperOpen = rememberDurableBooleanState("keeper.open")
    val inspectorVisible = selectedTaskId != null &&
        liveWorkflow != null &&
        destination == ControlRoomDestination.Overview &&
        !district.value
    // Out to the district: the creature panel belongs to a hive, so it closes on the way.
    val zoomOut = {
        if (inspectorVisible) selectedTaskId?.let(onTaskSelected)
        district.value = true
    }
    // The creature panel's tab (Needs, Work, Mind). A creature opens on Needs when it waits on you,
    // else on Work; the tab survives a restart while the same creature stays open.
    var creatureTabName by rememberDurableStringState("creature.panel.tab", CreatureTab.Work.name)
    var creatureTabTask by rememberDurableStringState("creature.panel.task")
    val creatureTab = CreatureTab.entries.firstOrNull { it.name == creatureTabName } ?: CreatureTab.Work
    LaunchedEffect(selectedTaskId) {
        if (selectedTaskId != null && selectedTaskId != creatureTabTask) {
            creatureTabName = openingTab(liveWorkflow?.run?.taskRuns?.get(TaskDefinitionId(selectedTaskId))).name
            creatureTabTask = selectedTaskId
        }
    }
    // Unread items: decisions creatures wait on, and the Hall Monitor pause. The badge opens the first
    // creature waiting on you, on its Needs (the Inbox retired into it, docs/Keepers-Room.md stage 3);
    // only the Hall Monitor, which speaks for the whole hive, still has a place of its own. An item is
    // read once its creature's panel has been open, so the badge counts what is still unseen.
    val inboxReadLog = remember { InboxReadLog(Settings()) }
    val inboxKeys = remember(runtimeState) { inboxItemKeys(runtimeState) }
    val unreadInbox = unreadInboxCount(inboxKeys, inboxReadLog.read)
    LaunchedEffect(destination, inboxKeys) {
        if (destination == ControlRoomDestination.Inbox) inboxReadLog.markRead(inboxKeys)
    }
    LaunchedEffect(selectedTaskId, inspectorVisible, inboxKeys) {
        val run = liveWorkflow?.run ?: return@LaunchedEffect
        if (inspectorVisible && selectedTaskId != null) inboxReadLog.markRead(inboxItemKeys(run, selectedTaskId))
    }
    val openInbox = {
        val waiting = liveWorkflow?.takeUnless { it.run.globalPause != null || isHallMonitorTrial(it) }
            ?.let { live -> firstCreatureNeedingYou(live, unseen = { key -> key !in inboxReadLog.read }) }
        if (waiting != null) {
            district.value = false
            if (selectedTaskId != waiting) onTaskSelected(waiting)
            creatureTabName = CreatureTab.Needs.name
            creatureTabTask = waiting
            onDestinationSelected(ControlRoomDestination.Overview)
        } else {
            onDestinationSelected(ControlRoomDestination.Inbox)
        }
    }
    // Back zooms out of a hive to the district before it leaves the app; the panel and other places
    // handle Back themselves first.
    PlatformBackHandler(
        enabled = destination == ControlRoomDestination.Overview && !district.value && !inspectorVisible &&
            (liveWorkflow != null || restingHive.value.isNotBlank()),
    ) { zoomOut() }
    val crumbs = shellCrumbs(
        destination = destination,
        inDistrict = district.value,
        hiveName = liveWorkflow?.definition?.name ?: restingDefinition?.name,
        creatureName = selectedTaskId?.takeIf { inspectorVisible }?.let { id -> creatureCrumb(id, liveWorkflow, restingDefinition) },
        creatureTab = creatureTab.takeIf { inspectorVisible }?.label(),
        keeperPart = KeeperSection.entries.firstOrNull { it.name == keeperSectionName.value }
            ?.takeIf { !compact || keeperOpen.value }?.let { stringResource(it.title) },
        onOpenDistrict = {
            if (destination == ControlRoomDestination.Overview) {
                zoomOut()
            } else {
                // From a place opened off the hive, Hive goes back to the hive you came from.
                onDestinationSelected(ControlRoomDestination.Overview)
            }
        },
        onOpenHive = {
            if (inspectorVisible) selectedTaskId?.let(onTaskSelected)
            district.value = false
            onDestinationSelected(ControlRoomDestination.Overview)
        },
        onCloseCreature = { selectedTaskId?.let(onTaskSelected) },
        onCloseKeeperPart = if (compact && keeperOpen.value) ({ keeperOpen.value = false }) else null,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground.page)
            .padding(contentPadding),
    ) {
        Column(Modifier.fillMaxSize()) {
            ShellHeader(
                crumbs = crumbs,
                runtimeState = runtimeState,
                unreadInbox = unreadInbox,
                onOpenInbox = openInbox,
                keeperOpen = destination.navigationItem == ControlRoomDestination.Settings,
                onOpenKeeper = {
                    // The gear opens the room itself, its list on a phone, not whichever part was left open.
                    if (destination.navigationItem != ControlRoomDestination.Settings) keeperOpen.value = false
                    onDestinationSelected(ControlRoomDestination.Settings)
                },
                compact = compact,
            )
            MainDestination(
                destination = destination,
                onOpenSettings = { onDestinationSelected(ControlRoomDestination.Settings) },
                computeAvailable = ControlRoomDestination.Compute in destinations,
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
                compact = compact,
                district = district,
                restingHive = restingHive,
                onZoomOut = zoomOut,
                keeperSectionName = keeperSectionName,
                keeperOpen = keeperOpen,
                runtimeState = runtimeState,
            )
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
                        tab = creatureTab,
                        onTabChange = { creatureTabName = it.name },
                        onSaveRoleCollection = onSaveRoleCollection,
                        connectedProviderIds = connectedProviderIds,
                        roleSurfaceFilePicker = roleSurfaceFilePicker,
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

/** One step of the breadcrumb: where the keeper is, and the way back up. */
internal data class ShellCrumb(val label: String, val seed: String, val onClick: (() -> Unit)?)

/**
 * The breadcrumb (docs/Keepers-Room.md, stage 2): Hive › a hive › a creature, or Hive › a place
 * opened from it. It replaces the navigation and the page titles; the last step is where you are.
 */
@Composable
private fun shellCrumbs(
    destination: ControlRoomDestination,
    inDistrict: Boolean,
    hiveName: String?,
    creatureName: String?,
    creatureTab: String?,
    keeperPart: String?,
    onOpenDistrict: () -> Unit,
    onOpenHive: () -> Unit,
    onCloseCreature: () -> Unit,
    onCloseKeeperPart: (() -> Unit)?,
): List<ShellCrumb> = buildList {
    add(ShellCrumb(stringResource(Res.string.crumb_hive), "crumb-hive", onOpenDistrict))
    when {
        destination == ControlRoomDestination.Overview -> {
            if (inDistrict) return@buildList
            hiveName?.takeIf(String::isNotBlank)?.let { add(ShellCrumb(it, "crumb-a-hive", onOpenHive)) }
            creatureName?.let { add(ShellCrumb(it, "crumb-creature", onCloseCreature)) }
            if (creatureName != null) creatureTab?.let { add(ShellCrumb(it, "crumb-creature-tab", null)) }
        }
        destination.navigationItem == ControlRoomDestination.Settings -> {
            add(ShellCrumb(ControlRoomDestination.Settings.label, "crumb-keeper", onCloseKeeperPart))
            keeperPart?.let { add(ShellCrumb(it, "crumb-keeper-part", null)) }
        }
        else -> add(ShellCrumb(destination.label, "crumb-${destination.name}", null))
    }
}.let { crumbs ->
    // Where you are is never a way to go: the last step reads as a place, not a button.
    crumbs.mapIndexed { index, crumb -> if (index == crumbs.lastIndex) crumb.copy(onClick = null) else crumb }
}

/** The selected creature's name: its role, else what does its work. */
private fun creatureCrumb(taskId: String, live: LiveWorkflowPresentation?, resting: WorkflowDefinition?): String? {
    val id = TaskDefinitionId(taskId)
    live?.let { workflow ->
        val task = workflow.definition.tasks.firstOrNull { it.id == id } ?: return null
        val run = workflow.run.taskRuns[id]
        val roleId = run?.assignedRoleId ?: task.roleId
        return workflow.roles.firstOrNull { it.id == roleId }?.name ?: (run?.executor ?: task.effectiveExecutor()).displayName()
    }
    return resting?.tasks?.firstOrNull { it.id == id }?.name
}

/**
 * The one header, on every screen and size: the Aive and how its runtime is, the breadcrumb, then
 * the Inbox and the Keeper's room. On a phone the breadcrumb takes its own line under them.
 */
@Composable
private fun ShellHeader(
    crumbs: List<ShellCrumb>,
    runtimeState: ApplicationRuntimeState,
    unreadInbox: Int,
    onOpenInbox: () -> Unit,
    keeperOpen: Boolean,
    onOpenKeeper: () -> Unit,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(stringResource(Res.string.nav_the_aive), style = AzphaltType.lead, color = azphaltOnSurface())
                Text(
                    runtimeStatusLabel(runtimeState),
                    style = AzphaltType.eyebrow,
                    color = azphaltOnSurface(),
                    modifier = Modifier.azphaltLiveRegion(),
                )
            }
            if (!compact) Breadcrumb(crumbs, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            InboxBadge(unread = unreadInbox, onClick = onOpenInbox)
            KeeperButton(selected = keeperOpen, onClick = onOpenKeeper)
        }
        if (compact) Breadcrumb(crumbs, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Breadcrumb(crumbs: List<ShellCrumb>, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.crumb_label)
    val here = stringResource(Res.string.crumb_here)
    val scroll = rememberScrollState()
    // A long trail scrolls so where you are, its last step, stays in view.
    LaunchedEffect(crumbs.map { it.label }, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = modifier
            .semantics { contentDescription = label }
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text("›", style = AzphaltType.lead, color = azphaltOnSurface(), modifier = Modifier.clearAndSetSemantics {})
            val onClick = crumb.onClick
            if (onClick != null) {
                AzphaltPill(label = crumb.label, seed = crumb.seed, onClick = onClick)
            } else {
                // A step that goes nowhere is a place, not a button: the last is where you are.
                AzphaltPill(
                    label = crumb.label,
                    seed = crumb.seed,
                    selected = index == crumbs.lastIndex,
                    stateDescription = if (index == crumbs.lastIndex) here else null,
                    onClick = {},
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = crumb.label
                        heading()
                        if (index == crumbs.lastIndex) stateDescription = here
                    },
                )
            }
        }
    }
}

/**
 * The hive's own places across its top: its Swarm, the Memory swarm and the Store.
 * Tapping the open one returns to the hive.
 */
@Composable
private fun OverviewSectionTabs(
    compact: Boolean,
    destination: ControlRoomDestination,
    onDestinationSelected: (ControlRoomDestination) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = hiveGutter(compact), vertical = 6.dp),
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
    computeAvailable: Boolean = true,
    district: MutableState<Boolean> = remember { mutableStateOf(false) },
    restingHive: MutableState<String> = remember { mutableStateOf("") },
    onZoomOut: () -> Unit = { district.value = true },
    keeperSectionName: MutableState<String> = remember { mutableStateOf(KeeperSection.Connections.name) },
    keeperOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    Column(modifier.fillMaxSize()) {
        // A first launch is one step: no places to wander off to until there is a hive.
        if (destination.navigationItem == ControlRoomDestination.Overview && runtimeState !is ApplicationRuntimeState.NoProject) {
            OverviewSectionTabs(compact, destination, onDestinationSelected)
        }
        AzphaltPlaceTransition(target = destination, modifier = Modifier.weight(1f).fillMaxWidth()) { place ->
            when (place) {
                ControlRoomDestination.Overview -> {
                    var inDistrict by district
                    var restingHiveId by restingHive
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
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = hiveGutter(compact)),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RestingHive(
                                definitionId = restingHiveId,
                                onLoadDefinitions = onLoadWorkflowDefinitions,
                                selectedTaskId = selectedTaskId,
                                onTaskSelected = onTaskSelected,
                                onSurface = onZoomOut,
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
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.padding(horizontal = hiveGutter(compact)),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Out to the district is the breadcrumb's first step.
                                if (live != null) {
                                    AzphaltPill(
                                        label = stringResource(if (showTimeline) Res.string.hive_show_hive else Res.string.hive_show_timeline),
                                        seed = "hive-timeline",
                                        selected = showTimeline,
                                        onClick = { showTimeline = !showTimeline },
                                    )
                                    // Combine, Save as and the rest live in Workflows, opened on this hive's workflow.
                                    AzphaltPill(
                                        label = stringResource(Res.string.hive_more_editing),
                                        seed = "hive-more-editing",
                                        onClick = {
                                            hiveScope.launch {
                                                val id = live.run.workflowDefinitionId.value
                                                hiveHost?.entries()?.firstOrNull { it.definition.id.value == id }?.let { entry ->
                                                    DurableUiState.store.putString("workflows.selected-key", entry.key)
                                                    DurableUiState.store.putString("workflows.tab", WorkflowLibraryTab.Overview.name)
                                                }
                                                onDestinationSelected(ControlRoomDestination.Workflows)
                                            }
                                        },
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
                            onSurface = onZoomOut,
                            runtimeState = runtimeState,
                            hasProvider = connectedProviderIds.isNotEmpty(),
                        )
                            }
                            live?.let { HiveLaunchBar(it.definition) }
                        }
                    }
                }
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
                ControlRoomDestination.Inbox -> LiveInboxScreen(runtimeState, onApproveTask, onRejectPlan, onResolveEscalation, Modifier.fillMaxSize())
                ControlRoomDestination.Memory -> MemoryLayerScreen(
                    controller = memoryLayer,
                    connectedProviderIds = connectedProviderIds,
                    compact = compact,
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
                ControlRoomDestination.Settings,
                ControlRoomDestination.Compute -> KeeperRoom(
                    sections = KeeperSection.entries.filter {
                        it != KeeperSection.Compute || computeAvailable
                    },
                    opening = KeeperSection.of(place),
                    selectedName = keeperSectionName,
                    open = keeperOpen,
                    compact = compact,
                    statusOf = { section ->
                        when (section) {
                            KeeperSection.Connections -> keeperCount(connectedProviderIds.size + connectedRepositoryServiceIds.size)
                            KeeperSection.Compute -> if (distributedComputeState.connected) null else keeperCount(0)
                            else -> null
                        }
                    },
                    content = { section, partModifier ->
                            ProviderSettingsScreen(
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
                                section = section,
                                compact = compact,
                                trailing = {
                                    when (section) {
                                        KeeperSection.Connections -> RepositoryConnections(
                                            connectedServiceIds = connectedRepositoryServiceIds,
                                            onConfigureService = onConfigureRepositoryService,
                                            onDisconnectService = onDisconnectRepositoryService,
                                            compact = compact,
                                        )
                                        KeeperSection.Compute -> ComputeDelegationScreen(
                                            runtimeState = runtimeState,
                                            distributedComputeState = distributedComputeState,
                                            onAssignWorkflow = onAssignWorkflowCompute,
                                            onAssignRole = onAssignRoleCompute,
                                            onAssignTask = onAssignTaskCompute,
                                            embedded = true,
                                        )
                                        else -> Unit
                                    }
                                },
                                modifier = partModifier,
                            )
                    },
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

/** The first creature, in plan order, waiting on the keeper. */
internal fun firstCreatureNeedingYou(live: LiveWorkflowPresentation): String? =
    firstCreatureNeedingYou(live) { true }

/**
 * The first creature, in plan order, waiting on the keeper, preferring one whose wait is still
 * [unseen] (its Inbox key unread), so the badge walks through every waiting creature in turn.
 */
internal fun firstCreatureNeedingYou(live: LiveWorkflowPresentation, unseen: (String) -> Boolean): String? {
    val waiting = live.definition.tasks.filter { task -> live.run.taskRuns[task.id]?.let(::creatureNeedsYou) == true }
    return (waiting.firstOrNull { task -> inboxItemKeys(live.run, task.id.value).any(unseen) } ?: waiting.firstOrNull())?.id?.value
}

/** The hive's left edge: its rows of places and actions line up with the hive's own content. */
internal fun hiveGutter(compact: Boolean) = if (compact) 16.dp else 26.dp
