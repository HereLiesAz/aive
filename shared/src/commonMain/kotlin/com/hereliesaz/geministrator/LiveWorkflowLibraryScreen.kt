package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private enum class WorkflowLibrarySurface(val label: String) {
    Library("MY LIBRARY"),
    Store("AZPHALT STORE"),
}

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
    var surfaceName by rememberDurableStringState("workflows.surface", WorkflowLibrarySurface.Library.name)
    val surface = WorkflowLibrarySurface.entries.firstOrNull { it.name == surfaceName }
        ?: WorkflowLibrarySurface.Library

    LaunchedEffect(azphaltPackageImportRequest?.requestId) {
        if (azphaltPackageImportRequest != null) {
            surfaceName = WorkflowLibrarySurface.Store.name
        }
    }

    if (surface == WorkflowLibrarySurface.Store) {
        Column(modifier = modifier.fillMaxSize()) {
            WorkflowLibrarySurfaceSwitcher(
                surface = surface,
                onSelected = { surfaceName = it.name },
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            )
            AzphaltStoreScreen(
                service = azphaltStoreService,
                importRequest = azphaltPackageImportRequest,
                onImportHandled = onAzphaltPackageImportHandled,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    var entries by remember { mutableStateOf<List<WorkflowLibraryEntry>?>(null) }
    var roles by remember { mutableStateOf<List<RoleDefinition>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var query by rememberDurableStringState("workflows.query")
    var filterName by rememberDurableStringState("workflows.filter", WorkflowLibraryFilter.All.name)
    val filter = WorkflowLibraryFilter.entries.firstOrNull { it.name == filterName } ?: WorkflowLibraryFilter.All
    var selectedKeyValue by rememberDurableStringState("workflows.selected-key")
    val selectedKey = selectedKeyValue.takeIf(String::isNotBlank)
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
    var refreshGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(runtimeState, host, refreshGeneration) {
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
            loadError = failure.message ?: "Workflow library could not be loaded."
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

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("WORKFLOWS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            "Installed workflows, saved compositions, and reusable roles share one graph library.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        WorkflowLibrarySurfaceSwitcher(
            surface = surface,
            onSelected = { surfaceName = it.name },
        )

        loadError?.let { AzphaltNote("workflow-library-error", "LIBRARY ERROR", it) }
        status?.let { AzphaltNote("workflow-library-status", "WORKFLOWS", it) }

        when (val loaded = entries) {
            null -> Text("Loading workflow library…", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter installed workflows and roles") },
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
                        "No library entries match this filter.",
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }

                visibleEntries.forEach { entry ->
                    val selected = selectedKey == entry.key
                    val isActive = entry.definition.id.value == activeId
                    AzphaltRecord(
                        seed = "workflow-library-${entry.key}",
                        eyebrow = when (entry.origin) {
                            WorkflowLibraryOrigin.Installed -> entry.packageId?.let { "Installed · $it" } ?: "Installed workflow"
                            WorkflowLibraryOrigin.Authored -> "Saved composition"
                            WorkflowLibraryOrigin.Role -> entry.packageId?.let { "Reusable role · $it" } ?: "Reusable role"
                        },
                        title = entry.definition.name,
                        body = entry.definition.description
                            ?: "${entry.definition.tasks.size} task${if (entry.definition.tasks.size == 1) "" else "s"}",
                        endCap = if (isActive) "Active" else "${entry.definition.tasks.size} task${if (entry.definition.tasks.size == 1) "" else "s"}",
                        selected = selected,
                        onClick = { selectedKeyValue = if (selected) "" else entry.key },
                        well = if (selected) {
                            {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    entry.packageId?.let {
                                        Text(it, style = AzphaltType.eyebrow, color = Azphalt.White)
                                    }
                                    AzphaltPill(
                                        label = "Load",
                                        seed = "workflow-load-${entry.key}",
                                        endCap = entry.origin.name,
                                        onClick = {
                                            draft = entry.definition
                                            draftOriginName = entry.origin.name
                                            selectedTaskIdValue = ""
                                            status = "Loaded ${entry.definition.name}."
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
                                        },
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }
        }

        draft?.let { currentDraft ->
            Text("LOADED WORKFLOW", style = AzphaltType.section, color = Azphalt.currentGround.onPage)
            AzphaltRecord(
                seed = "workflow-loaded-${currentDraft.id.value}",
                eyebrow = draftOrigin?.name ?: "Draft",
                title = currentDraft.name,
                body = currentDraft.description,
                endCap = "${currentDraft.tasks.size} tasks",
                selected = true,
                well = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Integration · ${currentDraft.integrationPolicy.name} · Concurrency ${currentDraft.concurrencyPolicy.maxConcurrentTasks}",
                            style = AzphaltType.body,
                            color = Azphalt.White,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (host != null) {
                                AzphaltPill(
                                    label = "Run loaded",
                                    seed = "workflow-run-loaded",
                                    onClick = {
                                        scope.launch {
                                            runCatching { host.run(currentDraft) }
                                                .onSuccess { status = "Started ${currentDraft.name}." }
                                                .onFailure { loadError = it.message ?: "Workflow launch failed." }
                                        }
                                    },
                                )
                            }
                            AzphaltPill(
                                label = "Reset edits",
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

            currentDraft.tasks.forEach { task ->
                val selected = selectedTaskId == task.id.value
                val executor = task.executor ?: task.roleId?.let(TaskExecutor::RoleAgent)
                val roleName = task.roleId?.let { roleId -> roles.firstOrNull { it.id == roleId }?.name }
                AzphaltRecord(
                    seed = "loaded-task-${task.id.value}",
                    eyebrow = roleName ?: executor?.displayName() ?: "Unassigned",
                    title = task.name,
                    body = task.objective,
                    endCap = if (task.dependsOn.isEmpty()) "Entry" else "After ${task.dependsOn.size}",
                    selected = selected,
                    onClick = { selectedTaskIdValue = if (selected) "" else task.id.value },
                    well = if (selected) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    task.dependsOn.takeIf { it.isNotEmpty() }
                                        ?.joinToString(prefix = "Depends on · ") { it.value }
                                        ?: "No dependencies",
                                    style = AzphaltType.body,
                                    color = Azphalt.White,
                                )
                                if (roles.isNotEmpty()) {
                                    Text("REASSIGN ROLE", style = AzphaltType.eyebrow, color = Azphalt.White)
                                    OutlinedTextField(
                                        value = roleQuery,
                                        onValueChange = { roleQuery = it },
                                        label = { Text("Filter roles") },
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
                                    Text("EXPAND ROLE INTO WORKFLOW", style = AzphaltType.eyebrow, color = Azphalt.White)
                                    composableWorkflows(entries.orEmpty(), currentDraft, composeQuery).take(10).forEach { candidate ->
                                        AzphaltPill(
                                            label = candidate.definition.name,
                                            seed = "expand-${task.id.value}-${candidate.key}",
                                            endCap = "Expand",
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

            Text("COMBINE WORKFLOWS", style = AzphaltType.section, color = Azphalt.currentGround.onPage)
            OutlinedTextField(
                value = composeQuery,
                onValueChange = { composeQuery = it },
                label = { Text("Find workflow or role to add") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            composableWorkflows(entries.orEmpty(), currentDraft, composeQuery).take(16).forEach { candidate ->
                AzphaltRecord(
                    seed = "compose-${candidate.key}",
                    eyebrow = candidate.origin.name,
                    title = candidate.definition.name,
                    body = candidate.definition.description,
                    endCap = "${candidate.definition.tasks.size} task${if (candidate.definition.tasks.size == 1) "" else "s"}",
                    well = {
                        AzphaltPill(
                            label = "Add after end",
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

            if (host != null) {
                Text("SAVE COMPOSITION", style = AzphaltType.section, color = Azphalt.currentGround.onPage)
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Workflow name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = saveId,
                    onValueChange = { saveId = it },
                    label = { Text("Workflow id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = "Save reusable workflow",
                    seed = "workflow-save-composition",
                    endCap = "${currentDraft.tasks.size} tasks",
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
                                saveId = saved.id.value
                                saveName = saved.name
                                status = "Saved ${saved.name}."
                                refreshGeneration += 1
                            }.onFailure { failure ->
                                loadError = failure.message ?: "Workflow could not be saved."
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkflowLibrarySurfaceSwitcher(
    surface: WorkflowLibrarySurface,
    onSelected: (WorkflowLibrarySurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkflowLibrarySurface.entries.forEach { item ->
            AzphaltPill(
                label = item.label,
                seed = "workflow-surface-${item.name}",
                selected = surface == item,
                onClick = { onSelected(item) },
            )
        }
    }
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
