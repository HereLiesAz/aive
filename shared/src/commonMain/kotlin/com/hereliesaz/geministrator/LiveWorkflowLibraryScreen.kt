package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.workflow.WorkflowComposer
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.library_tasks
import com.hereliesaz.geministrator.resources.library_active
import com.hereliesaz.geministrator.resources.library_add_after_end
import com.hereliesaz.geministrator.resources.library_after
import com.hereliesaz.geministrator.resources.library_combine_workflows
import com.hereliesaz.geministrator.resources.library_depends_on
import com.hereliesaz.geministrator.resources.library_draft
import com.hereliesaz.geministrator.resources.library_entry
import com.hereliesaz.geministrator.resources.library_expand
import com.hereliesaz.geministrator.resources.library_expand_role_into_workflow
import com.hereliesaz.geministrator.resources.library_filter_installed_workflows_and_roles
import com.hereliesaz.geministrator.resources.library_filter_roles
import com.hereliesaz.geministrator.resources.library_find_workflow_or_role_to_add
import com.hereliesaz.geministrator.resources.library_installed
import com.hereliesaz.geministrator.resources.library_installed_workflow
import com.hereliesaz.geministrator.resources.library_installed_workflows_saved_compositions_and_reusable
import com.hereliesaz.geministrator.resources.library_integration_concurrency
import com.hereliesaz.geministrator.resources.library_library_error
import com.hereliesaz.geministrator.resources.library_pinned_change
import com.hereliesaz.geministrator.resources.library_pinned_clear
import com.hereliesaz.geministrator.resources.library_tab_combine
import com.hereliesaz.geministrator.resources.library_tab_library
import com.hereliesaz.geministrator.resources.library_tab_overview
import com.hereliesaz.geministrator.resources.library_tab_bowl
import com.hereliesaz.geministrator.resources.library_tab_save
import com.hereliesaz.geministrator.resources.library_tab_store
import com.hereliesaz.geministrator.resources.library_tab_tasks
import com.hereliesaz.geministrator.resources.library_loading_workflow_library
import com.hereliesaz.geministrator.resources.library_no_dependencies
import com.hereliesaz.geministrator.resources.library_no_library_entries_match_this_filter
import com.hereliesaz.geministrator.resources.library_optional_workflows_may_use_this_as
import com.hereliesaz.geministrator.resources.library_reassign_role
import com.hereliesaz.geministrator.resources.library_reset_edits
import com.hereliesaz.geministrator.resources.library_reusable_role
import com.hereliesaz.geministrator.resources.library_reusable_role_1
import com.hereliesaz.geministrator.resources.library_run_loaded
import com.hereliesaz.geministrator.resources.library_run_objective_inputs
import com.hereliesaz.geministrator.resources.library_save_composition
import com.hereliesaz.geministrator.resources.library_save_reusable_workflow
import com.hereliesaz.geministrator.resources.library_saved
import com.hereliesaz.geministrator.resources.library_saved_composition
import com.hereliesaz.geministrator.resources.library_started
import com.hereliesaz.geministrator.resources.library_unassigned
import com.hereliesaz.geministrator.resources.library_workflow_could_not_be_saved
import com.hereliesaz.geministrator.resources.library_workflow_id
import com.hereliesaz.geministrator.resources.library_workflow_launch_failed
import com.hereliesaz.geministrator.resources.library_workflow_library_could_not_be_loaded
import com.hereliesaz.geministrator.resources.library_workflow_name
import com.hereliesaz.geministrator.resources.library_workflows
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.hereliesaz.geministrator.resources.library_task_count
import org.jetbrains.compose.resources.pluralStringResource
private enum class WorkflowLibraryFilter(val label: String) {
    All("All"),
    Installed("Installed"),
    Authored("Mine"),
    Roles("Roles"),
}

@Composable
internal fun LiveWorkflowLibraryScreen(
    runtimeState: ApplicationRuntimeState,
    onLoadDefinitions: suspend () -> List<WorkflowDefinition>,
    azphaltStoreService: AzphaltStoreService? = null,
    azphaltPackageImportRequest: AzphaltPackageImportRequest? = null,
    onAzphaltPackageImportHandled: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val host = LocalWorkflowLibraryHost.current
    val canSave = host != null

    // The selected tab and the pinned workflow live in durable UI state, so both survive leaving
    // this destination and coming back. Every change goes through WorkflowLibraryTabState.
    var tabNameValue by rememberDurableStringState("workflows.tab", WorkflowLibraryTab.Library.name)
    var selectedKeyValue by rememberDurableStringState("workflows.selected-key")
    val tabState = WorkflowLibraryTabState(tabNameValue, selectedKeyValue)
    val tab = tabState.resolvedTab(canSave)
    val selectedKey = selectedKeyValue.takeIf(String::isNotBlank)
    val applyTabState: (WorkflowLibraryTabState) -> Unit = { next ->
        tabNameValue = next.tabName
        selectedKeyValue = next.selectedKey
    }

    LaunchedEffect(azphaltPackageImportRequest?.requestId) {
        if (azphaltPackageImportRequest != null) {
            tabNameValue = WorkflowLibraryTab.Store.name
        }
    }

    var entries by remember { mutableStateOf<List<WorkflowLibraryEntry>?>(null) }
    var roles by remember { mutableStateOf<List<RoleDefinition>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var query by rememberDurableStringState("workflows.query")
    var filterName by rememberDurableStringState("workflows.filter", WorkflowLibraryFilter.All.name)
    val filter = WorkflowLibraryFilter.entries.firstOrNull { it.name == filterName } ?: WorkflowLibraryFilter.All
    var draft by rememberDurableJsonState(
        key = "workflows.draft",
        serializer = WorkflowDefinition.serializer().nullable,
        initialValue = null,
    )
    var draftOriginName by rememberDurableStringState("workflows.draft-origin")
    val draftOrigin = WorkflowLibraryOrigin.entries.firstOrNull { it.name == draftOriginName }
    var selectedTaskIdValue by rememberDurableStringState("workflows.selected-task-id")
    val selectedTaskId = selectedTaskIdValue.takeIf(String::isNotBlank)
    var roleQuery by rememberDurableStringState("workflows.role-query")
    var composeQuery by rememberDurableStringState("workflows.compose-query")
    var saveId by rememberDurableStringState("workflows.save-id")
    var saveName by rememberDurableStringState("workflows.save-name")
    var runObjective by rememberDurableStringState("workflows.run-objective")
    var refreshGeneration by remember { mutableStateOf(0) }

    // Selecting a library card works on that workflow: it becomes the editable draft.
    val loadEntry: (WorkflowLibraryEntry) -> Unit = { entry ->
        draft = entry.definition
        draftOriginName = entry.origin.name
        selectedTaskIdValue = ""
        saveId = when (entry.origin) {
            WorkflowLibraryOrigin.Authored -> entry.definition.id.value
            WorkflowLibraryOrigin.Installed -> "custom-${entry.definition.id.value}"
            WorkflowLibraryOrigin.Role -> "custom-${entry.definition.id.value}"
        }
        saveName = if (entry.origin == WorkflowLibraryOrigin.Authored) {
            entry.definition.name
        } else {
            "${entry.definition.name} Custom"
        }
    }

    // Reload when the Store tab is left too, so a package installed there shows up in the library.
    val storeVisible = tab == WorkflowLibraryTab.Store
    LaunchedEffect(runtimeState, host, refreshGeneration, storeVisible) {
        runCatching {
            if (host != null) {
                host.entries() to host.installedRoles()
            } else {
                onLoadDefinitions().map { definition ->
                    WorkflowLibraryEntry(
                        key = "persisted:${definition.id.value}",
                        definition = definition,
                        origin = WorkflowLibraryOrigin.Authored,
                    )
                } to emptyList()
            }
        }.onSuccess { (loadedEntries, loadedRoles) ->
            entries = loadedEntries
            roles = loadedRoles
            loadError = null
        }.onFailure { failure ->
            entries = emptyList()
            roles = emptyList()
            loadError = failure.message ?: getString(Res.string.library_workflow_library_could_not_be_loaded)
        }
    }

    // A pinned key with no draft (restored state, or an entry that has since gone) is resolved
    // once the library has loaded, so the pinned header never shows without something to work on.
    LaunchedEffect(selectedKey, entries) {
        val loaded = entries ?: return@LaunchedEffect
        val key = selectedKey ?: return@LaunchedEffect
        if (draft == null) {
            val match = loaded.firstOrNull { it.key == key }
            if (match != null) {
                loadEntry(match)
            } else {
                applyTabState(WorkflowLibraryTabState(tabNameValue, selectedKeyValue).clearSelection())
            }
        }
    }

    val activeId = (runtimeState as? ApplicationRuntimeState.Live)
        ?.presentation
        ?.definition
        ?.id
        ?.value
    val needle = query.trim().lowercase()
    val visibleEntries = entries.orEmpty().filter { entry ->
        val originMatches = when (filter) {
            WorkflowLibraryFilter.All -> true
            WorkflowLibraryFilter.Installed -> entry.origin == WorkflowLibraryOrigin.Installed
            WorkflowLibraryFilter.Authored -> entry.origin == WorkflowLibraryOrigin.Authored
            WorkflowLibraryFilter.Roles -> entry.origin == WorkflowLibraryOrigin.Role
        }
        originMatches && (
            needle.isEmpty() || listOf(
                entry.definition.name,
                entry.definition.id.value,
                entry.definition.description.orEmpty(),
                entry.packageId.orEmpty(),
                entry.role?.name.orEmpty(),
            ).any { it.lowercase().contains(needle) }
        )
    }

    val selectedEntry = selectedKey?.let { key -> entries.orEmpty().firstOrNull { it.key == key } }
    val pinnedDefinition = if (tabState.hasSelection) draft ?: selectedEntry?.definition else null

    Column(modifier = modifier.fillMaxSize()) {
        // Pinned area: the selected workflow, the latest status or error, and the tab row. It stays
        // put while the tab content below scrolls and while tabs change.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = AzphaltSpacing.s),
            verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.m),
        ) {
            if (pinnedDefinition != null) {
                val taskCount = pluralStringResource(
                    Res.plurals.library_task_count,
                    pinnedDefinition.tasks.size,
                    pinnedDefinition.tasks.size,
                )
                PinnedWorkflowHeader(
                    kind = workflowKindLabel(draftOrigin ?: selectedEntry?.origin, selectedEntry?.packageId),
                    name = pinnedDefinition.name,
                    summary = if (pinnedDefinition.id.value == activeId) {
                        "$taskCount · ${stringResource(Res.string.library_active)}"
                    } else {
                        taskCount
                    },
                    onChange = { applyTabState(tabState.selectTab(WorkflowLibraryTab.Library, canSave)) },
                    onClear = {
                        draft = null
                        draftOriginName = ""
                        selectedTaskIdValue = ""
                        status = null
                        applyTabState(tabState.clearSelection())
                    },
                )
            } else if (tab == WorkflowLibraryTab.Library) {
                Text(stringResource(Res.string.library_workflows), style = AzphaltType.hero, color = azphaltOnSurface())
                Text(
                    stringResource(Res.string.library_installed_workflows_saved_compositions_and_reusable),
                    style = AzphaltType.body,
                    color = azphaltOnSurface(),
                )
            }

            loadError?.let {
                AzphaltNote(
                    "workflow-library-error",
                    stringResource(Res.string.library_library_error),
                    it,
                    modifier = Modifier.azphaltLiveRegion(),
                )
            }
            status?.let {
                AzphaltNote(
                    "workflow-library-status",
                    stringResource(Res.string.library_workflows),
                    it,
                    modifier = Modifier.azphaltLiveRegion(),
                )
            }

            AzphaltTabs(
                tabs = tabState.availableTabs(canSave).map { item ->
                    AzphaltTabItem(
                        key = item.name,
                        label = when (item) {
                            WorkflowLibraryTab.Library -> stringResource(Res.string.library_tab_library)
                            WorkflowLibraryTab.Store -> stringResource(Res.string.library_tab_store)
                            WorkflowLibraryTab.Overview -> stringResource(Res.string.library_tab_overview)
                            WorkflowLibraryTab.Bowl -> stringResource(Res.string.library_tab_bowl)
                            WorkflowLibraryTab.Tasks -> stringResource(Res.string.library_tab_tasks)
                            WorkflowLibraryTab.Combine -> stringResource(Res.string.library_tab_combine)
                            WorkflowLibraryTab.Save -> stringResource(Res.string.library_tab_save)
                        },
                        endCap = if (item == WorkflowLibraryTab.Tasks) pinnedDefinition?.tasks?.size?.toString() else null,
                    )
                },
                selectedKey = tab.name,
                onSelected = { name ->
                    WorkflowLibraryTab.fromName(name)?.let { applyTabState(tabState.selectTab(it, canSave)) }
                },
                seedPrefix = "workflow-tab",
            )
        }

        if (tab == WorkflowLibraryTab.Store) {
            AzphaltStoreScreen(
                service = azphaltStoreService,
                importRequest = azphaltPackageImportRequest,
                onImportHandled = onAzphaltPackageImportHandled,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Keyed by tab so each tab opens at its own top instead of inheriting the scroll offset.
            key(tab) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 26.dp, end = 26.dp, top = AzphaltSpacing.s, bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val currentDraft = draft
                    when (tab) {
                        WorkflowLibraryTab.Store -> Unit
                        WorkflowLibraryTab.Library -> when (val loaded = entries) {
                            null -> Text(stringResource(Res.string.library_loading_workflow_library), style = AzphaltType.body, color = azphaltOnSurface())
                            else -> {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    label = { Text(stringResource(Res.string.library_filter_installed_workflows_and_roles)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WorkflowLibraryFilter.entries.forEach { item ->
                                        AzphaltPill(
                                            label = item.label,
                                            seed = "workflow-filter-${item.name}",
                                            selected = filter == item,
                                            endCap = when (item) {
                                                WorkflowLibraryFilter.All -> loaded.size
                                                WorkflowLibraryFilter.Installed -> loaded.count { it.origin == WorkflowLibraryOrigin.Installed }
                                                WorkflowLibraryFilter.Authored -> loaded.count { it.origin == WorkflowLibraryOrigin.Authored }
                                                WorkflowLibraryFilter.Roles -> loaded.count { it.origin == WorkflowLibraryOrigin.Role }
                                            }.toString(),
                                            onClick = { filterName = item.name },
                                        )
                                    }
                                }

                                if (visibleEntries.isEmpty()) {
                                    Text(
                                        stringResource(Res.string.library_no_library_entries_match_this_filter),
                                        style = AzphaltType.body,
                                        color = azphaltOnSurface(),
                                    )
                                }

                                visibleEntries.forEach { entry ->
                                    val selected = selectedKey == entry.key
                                    val isActive = entry.definition.id.value == activeId
                                    AzphaltRecord(
                                        seed = "workflow-library-${entry.key}",
                                        eyebrow = workflowKindLabel(entry.origin, entry.packageId),
                                        title = entry.definition.name,
                                        body = entry.definition.description
                                            ?: pluralStringResource(Res.plurals.library_task_count, entry.definition.tasks.size, entry.definition.tasks.size),
                                        endCap = if (isActive) stringResource(Res.string.library_active) else pluralStringResource(Res.plurals.library_task_count, entry.definition.tasks.size, entry.definition.tasks.size),
                                        selected = selected,
                                        onClick = {
                                            if (selected) {
                                                applyTabState(tabState.openSelected())
                                            } else {
                                                loadEntry(entry)
                                                status = null
                                                applyTabState(tabState.selectWorkflow(entry.key))
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        WorkflowLibraryTab.Overview -> if (currentDraft != null) {
                            AzphaltRecord(
                                seed = "workflow-loaded-${currentDraft.id.value}",
                                eyebrow = workflowKindLabel(draftOrigin ?: selectedEntry?.origin, selectedEntry?.packageId),
                                title = currentDraft.name,
                                body = currentDraft.description,
                                endCap = stringResource(Res.string.library_tasks, currentDraft.tasks.size),
                                selected = true,
                                well = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            stringResource(Res.string.library_integration_concurrency, currentDraft.integrationPolicy.name, currentDraft.concurrencyPolicy.maxConcurrentTasks),
                                            style = AzphaltType.body,
                                            color = azphaltOnSurface(),
                                        )
                                        OutlinedTextField(
                                            value = runObjective,
                                            onValueChange = { runObjective = it },
                                            label = { Text(stringResource(Res.string.library_run_objective_inputs)) },
                                            placeholder = { Text(stringResource(Res.string.library_optional_workflows_may_use_this_as)) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (host != null) {
                                                AzphaltPill(
                                                    label = stringResource(Res.string.library_run_loaded),
                                                    seed = "workflow-run-loaded",
                                                    onClick = {
                                                        scope.launch {
                                                            runCatching { host.run(currentDraft, runObjective) }
                                                                .onSuccess { status = getString(Res.string.library_started, currentDraft.name) }
                                                                .onFailure { loadError = it.message ?: getString(Res.string.library_workflow_launch_failed) }
                                                        }
                                                    },
                                                )
                                            }
                                            AzphaltPill(
                                                label = stringResource(Res.string.library_reset_edits),
                                                seed = "workflow-reset-draft",
                                                onClick = {
                                                    val source = entries.orEmpty().firstOrNull { it.key == selectedKey }
                                                    if (source != null) {
                                                        draft = source.definition
                                                        selectedTaskIdValue = ""
                                                        status = "Reset loaded workflow."
                                                    }
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        WorkflowLibraryTab.Bowl -> if (currentDraft != null) {
                            // Phase 1 of docs/Fishbowl-Builder.md: the bowl with no run. Dropping one
                            // creature on another edits the unsaved draft; Save persists it.
                            GeministratorWorkflowTerrarium(
                                definition = currentDraft,
                                run = remember(currentDraft.id) { restingWorkflowRun(currentDraft) },
                                roles = roles,
                                selectedTaskId = selectedTaskId,
                                onTaskSelected = { taskId -> selectedTaskIdValue = if (selectedTaskId == taskId) "" else taskId },
                                onStageDefinition = { candidate ->
                                    draft = candidate
                                    candidate
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        WorkflowLibraryTab.Tasks -> if (currentDraft != null) {
                            currentDraft.tasks.forEach { task ->
                                val selected = selectedTaskId == task.id.value
                                val executor = task.executor ?: task.roleId?.let(TaskExecutor::RoleAgent)
                                val roleName = task.roleId?.let { roleId -> roles.firstOrNull { it.id == roleId }?.name }
                                AzphaltRecord(
                                    seed = "loaded-task-${task.id.value}",
                                    eyebrow = roleName ?: executor?.displayName() ?: stringResource(Res.string.library_unassigned),
                                    title = task.name,
                                    body = task.objective,
                                    endCap = if (task.dependsOn.isEmpty()) stringResource(Res.string.library_entry) else stringResource(Res.string.library_after, task.dependsOn.size),
                                    selected = selected,
                                    onClick = { selectedTaskIdValue = if (selected) "" else task.id.value },
                                    well = if (selected) {
                                        {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(
                                                    task.dependsOn.takeIf { it.isNotEmpty() }
                                                        ?.joinToString(prefix = stringResource(Res.string.library_depends_on)) { it.value }
                                                        ?: stringResource(Res.string.library_no_dependencies),
                                                    style = AzphaltType.body,
                                                    color = azphaltOnSurface(),
                                                )
                                                if (roles.isNotEmpty()) {
                                                    Text(stringResource(Res.string.library_reassign_role), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                                                    OutlinedTextField(
                                                        value = roleQuery,
                                                        onValueChange = { roleQuery = it },
                                                        label = { Text(stringResource(Res.string.library_filter_roles)) },
                                                        singleLine = true,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                    val roleNeedle = roleQuery.trim().lowercase()
                                                    roles.filter { role ->
                                                        roleNeedle.isEmpty() || listOf(role.name, role.id.value, role.description)
                                                            .any { it.lowercase().contains(roleNeedle) }
                                                    }.take(12).forEach { role ->
                                                        AzphaltPill(
                                                            label = role.name,
                                                            seed = "assign-${task.id.value}-${role.id.value}",
                                                            selected = task.roleId == role.id,
                                                            endCap = role.id.value,
                                                            onClick = {
                                                                mutateDraft(
                                                                    currentDraft = currentDraft,
                                                                    onSuccess = { next -> draft = next },
                                                                    onFailure = { loadError = it },
                                                                ) {
                                                                    currentDraft.copy(
                                                                        tasks = currentDraft.tasks.map { candidate ->
                                                                            if (candidate.id == task.id) {
                                                                                candidate.copy(
                                                                                    roleId = role.id,
                                                                                    executor = TaskExecutor.RoleAgent(role.id),
                                                                                )
                                                                            } else candidate
                                                                        },
                                                                    )
                                                                }
                                                            },
                                                        )
                                                    }
                                                }

                                                if (executor is TaskExecutor.RoleAgent || task.roleId != null) {
                                                    Text(stringResource(Res.string.library_expand_role_into_workflow), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                                                    composableWorkflows(entries.orEmpty(), currentDraft, composeQuery).take(10).forEach { candidate ->
                                                        AzphaltPill(
                                                            label = candidate.definition.name,
                                                            seed = "expand-${task.id.value}-${candidate.key}",
                                                            endCap = stringResource(Res.string.library_expand),
                                                            onClick = {
                                                                mutateDraft(
                                                                    currentDraft = currentDraft,
                                                                    onSuccess = { next ->
                                                                        draft = next
                                                                        selectedTaskIdValue = ""
                                                                        status = "Expanded ${task.name} into ${candidate.definition.name}."
                                                                    },
                                                                    onFailure = { loadError = it },
                                                                ) {
                                                                    WorkflowComposer.expandRoleTask(
                                                                        definition = currentDraft,
                                                                        taskId = task.id,
                                                                        replacement = candidate.definition,
                                                                        namespace = uniqueNamespace(currentDraft, candidate.definition),
                                                                    )
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else null,
                                )
                            }
                        }

                        WorkflowLibraryTab.Combine -> if (currentDraft != null) {
                            Text(stringResource(Res.string.library_combine_workflows), style = AzphaltType.section, color = azphaltOnSurface())
                            OutlinedTextField(
                                value = composeQuery,
                                onValueChange = { composeQuery = it },
                                label = { Text(stringResource(Res.string.library_find_workflow_or_role_to_add)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            composableWorkflows(entries.orEmpty(), currentDraft, composeQuery).take(16).forEach { candidate ->
                                AzphaltRecord(
                                    seed = "compose-${candidate.key}",
                                    eyebrow = candidate.origin.name,
                                    title = candidate.definition.name,
                                    body = candidate.definition.description,
                                    endCap = pluralStringResource(Res.plurals.library_task_count, candidate.definition.tasks.size, candidate.definition.tasks.size),
                                    well = {
                                        AzphaltPill(
                                            label = stringResource(Res.string.library_add_after_end),
                                            seed = "inline-${candidate.key}",
                                            onClick = {
                                                mutateDraft(
                                                    currentDraft = currentDraft,
                                                    onSuccess = { next ->
                                                        draft = next
                                                        status = "Added ${candidate.definition.name}."
                                                    },
                                                    onFailure = { loadError = it },
                                                ) {
                                                    WorkflowComposer.inline(
                                                        parent = currentDraft,
                                                        child = candidate.definition,
                                                        namespace = uniqueNamespace(currentDraft, candidate.definition),
                                                        connectFrom = WorkflowComposer.exitPoints(currentDraft),
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        WorkflowLibraryTab.Save -> if (currentDraft != null && host != null) {
                            Text(stringResource(Res.string.library_save_composition), style = AzphaltType.section, color = azphaltOnSurface())
                            OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                label = { Text(stringResource(Res.string.library_workflow_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = saveId,
                                onValueChange = { saveId = it },
                                label = { Text(stringResource(Res.string.library_workflow_id)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.library_save_reusable_workflow),
                                seed = "workflow-save-composition",
                                endCap = stringResource(Res.string.library_tasks, currentDraft.tasks.size),
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            host.saveAuthoredAs(
                                                definition = currentDraft,
                                                id = WorkflowDefinitionId(saveId.trim()),
                                                name = saveName.trim(),
                                            )
                                        }.onSuccess { saved ->
                                            draft = saved
                                            draftOriginName = WorkflowLibraryOrigin.Authored.name
                                            // Keep the pinned selection on the workflow that was just saved.
                                            selectedKeyValue = "authored:${saved.id.value}"
                                            saveId = saved.id.value
                                            saveName = saved.name
                                            status = getString(Res.string.library_saved, saved.name)
                                            refreshGeneration += 1
                                        }.onFailure { failure ->
                                            loadError = failure.message ?: getString(Res.string.library_workflow_could_not_be_saved)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact pinned header for the selected workflow: what it is, how big it is, and how to change
 * or drop the selection. Only the text is a live region, so the pills keep their own actions and
 * a newly pinned workflow is announced without moving focus.
 */
@Composable
private fun PinnedWorkflowHeader(
    kind: String,
    name: String,
    summary: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Azphalt.Ink)
            .padding(horizontal = AzphaltSpacing.l, vertical = AzphaltSpacing.m),
        verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
    ) {
        Column(
            modifier = Modifier.azphaltLiveRegion(),
            verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.xs),
        ) {
            AzphaltSurface(Azphalt.Ink) {
                Text("$kind · $summary".uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                Text(name, style = AzphaltType.lead, color = azphaltOnSurface())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AzphaltSpacing.s)) {
            AzphaltPill(
                label = stringResource(Res.string.library_pinned_change),
                seed = "workflow-pinned-change",
                onClick = onChange,
            )
            AzphaltPill(
                label = stringResource(Res.string.library_pinned_clear),
                seed = "workflow-pinned-clear",
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun workflowKindLabel(origin: WorkflowLibraryOrigin?, packageId: String?): String = when (origin) {
    WorkflowLibraryOrigin.Installed ->
        packageId?.let { stringResource(Res.string.library_installed, it) }
            ?: stringResource(Res.string.library_installed_workflow)
    WorkflowLibraryOrigin.Authored -> stringResource(Res.string.library_saved_composition)
    WorkflowLibraryOrigin.Role ->
        packageId?.let { stringResource(Res.string.library_reusable_role_1, it) }
            ?: stringResource(Res.string.library_reusable_role)
    null -> stringResource(Res.string.library_draft)
}

private fun composableWorkflows(
    entries: List<WorkflowLibraryEntry>,
    current: WorkflowDefinition,
    query: String,
): List<WorkflowLibraryEntry> {
    val needle = query.trim().lowercase()
    return entries.filter { entry ->
        entry.definition.id != current.id &&
            (needle.isEmpty() || listOf(
                entry.definition.name,
                entry.definition.id.value,
                entry.definition.description.orEmpty(),
                entry.packageId.orEmpty(),
            ).any { it.lowercase().contains(needle) })
    }
}

private fun mutateDraft(
    currentDraft: WorkflowDefinition,
    onSuccess: (WorkflowDefinition) -> Unit,
    onFailure: (String) -> Unit,
    mutation: () -> WorkflowDefinition,
) {
    runCatching(mutation)
        .map { candidate ->
            val errors = WorkflowGraphValidator.validate(candidate)
            require(errors.isEmpty()) { "Invalid composed workflow: ${errors.joinToString("; ")}" }
            candidate
        }
        .onSuccess(onSuccess)
        .onFailure { failure -> onFailure(failure.message ?: "Workflow composition failed.") }
}

private fun uniqueNamespace(parent: WorkflowDefinition, child: WorkflowDefinition): String {
    val base = child.id.value
        .trim()
        .replace(Regex("\\s+"), "-")
        .ifBlank { "workflow" }
    val taskIds = parent.tasks.mapTo(mutableSetOf()) { it.id.value }
    var candidate = base
    var suffix = 2
    while (taskIds.any { it == candidate || it.startsWith("$candidate.") }) {
        candidate = "$base-$suffix"
        suffix += 1
    }
    return candidate
}
