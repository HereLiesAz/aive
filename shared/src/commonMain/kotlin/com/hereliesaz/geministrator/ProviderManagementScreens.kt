package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import kotlinx.coroutines.launch

@Composable
internal fun CompanyProviderScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    connectedProviderIds: Set<String> = emptySet(),
    onSaveRole: (RoleDefinition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val roles: Collection<RoleDefinition> = liveWorkflow?.roles
        ?: (runtimeState as? ApplicationRuntimeState.NoRun)?.roles
        ?: emptyList()
    var showRoleForm by remember { mutableStateOf(false) }
    var roleIdDraft by remember { mutableStateOf("") }
    var roleNameDraft by remember { mutableStateOf("") }
    var roleDescDraft by remember { mutableStateOf("") }
    var roleInstructionsDraft by remember { mutableStateOf("") }
    var roleProviderDraft by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SWARM", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            "Assign each swarm role to the provider you want. AUTO lets Aive choose a compatible connected provider; explicit assignments let you spread an orchestration across provider quotas.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        AzphaltPill(
            label = if (showRoleForm) "Cancel" else "Add role",
            seed = "add-role-toggle",
            onClick = {
                showRoleForm = !showRoleForm
                if (!showRoleForm) {
                    roleIdDraft = ""
                    roleNameDraft = ""
                    roleDescDraft = ""
                    roleInstructionsDraft = ""
                    roleProviderDraft = null
                }
            },
        )

        if (showRoleForm) {
            OutlinedTextField(
                value = roleNameDraft,
                onValueChange = { roleNameDraft = it },
                label = { Text("Role name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleIdDraft,
                onValueChange = { roleIdDraft = it },
                label = { Text("Role ID (slug)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleDescDraft,
                onValueChange = { roleDescDraft = it },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleInstructionsDraft,
                onValueChange = { roleInstructionsDraft = it },
                label = { Text("Standing instructions") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderChoiceRow(
                selectedProviderId = roleProviderDraft,
                connectedProviderIds = connectedProviderIds,
                onSelected = { roleProviderDraft = it },
            )
            AzphaltPill(
                label = "Save role",
                seed = "save-role",
                onClick = {
                    val id = roleIdDraft.trim().ifBlank { roleNameDraft.trim().lowercase().replace(" ", "-") }
                    if (id.isNotEmpty() && roleNameDraft.isNotBlank()) {
                        onSaveRole(
                            RoleDefinition(
                                id = RoleDefinitionId(id),
                                name = roleNameDraft.trim(),
                                description = roleDescDraft.trim(),
                                instructions = roleInstructionsDraft.trim(),
                                preferredProviderId = roleProviderDraft?.let(::AgentProviderId),
                            ),
                        )
                        showRoleForm = false
                        roleIdDraft = ""
                        roleNameDraft = ""
                        roleDescDraft = ""
                        roleInstructionsDraft = ""
                        roleProviderDraft = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val byDepartment = roles.groupBy { it.providerDepartment() }
        listOf("Executive", "Product", "Engineering", "Assurance", "Delivery", "Custom").forEach { department ->
            val deptRoles = byDepartment[department] ?: return@forEach
            ProviderSectionLabel(department)
            val activeRoleIds = liveWorkflow?.run?.taskRuns?.values
                ?.filter {
                    it.status in setOf(
                        TaskRunStatus.Running,
                        TaskRunStatus.Planning,
                        TaskRunStatus.AwaitingApproval,
                        TaskRunStatus.Verifying,
                    )
                }
                ?.mapNotNull { it.assignedRoleId }
                ?.toSet()
                .orEmpty()
            deptRoles.forEach { role ->
                val assigned = role.preferredProviderId?.value
                val providerLabel = assigned?.let { ProviderCatalog.entry(it)?.displayName ?: it } ?: "Auto"
                AzphaltRecord(
                    seed = "provider-role-${role.id.value}",
                    eyebrow = department,
                    title = role.name,
                    body = "${role.description}\nProvider: $providerLabel",
                    endCap = if (role.id in activeRoleIds) "Working" else "Available",
                    well = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("PROVIDER ROUTING", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                            ProviderChoiceRow(
                                selectedProviderId = assigned,
                                connectedProviderIds = connectedProviderIds,
                                onSelected = { selected ->
                                    onSaveRole(
                                        role.copy(
                                            preferredProviderId = selected?.let(::AgentProviderId),
                                        ),
                                    )
                                },
                            )
                            if (connectedProviderIds.isEmpty()) {
                                Text(
                                    "Connect provider API keys in Settings before assigning roles.",
                                    style = AzphaltType.body,
                                    color = Azphalt.currentGround.onPage,
                                )
                            }
                        }
                    },
                )
            }
        }

        if (liveWorkflow != null) {
            val definition = liveWorkflow.definition
            ProviderSectionLabel("Workflow Policies")
            AzphaltRecord(
                seed = "policy-integration",
                eyebrow = "Integration",
                title = "Integration policy",
                body = when (definition.integrationPolicy) {
                    IntegrationPolicy.Manual -> "Changes integrated manually"
                    IntegrationPolicy.PullRequest -> "Changes delivered via pull request"
                    IntegrationPolicy.AutoMergeAfterVerification -> "Auto-merge after verification passes"
                },
                endCap = definition.integrationPolicy.name,
            )
            AzphaltRecord(
                seed = "policy-concurrency",
                eyebrow = "Concurrency",
                title = "Concurrency policy",
                body = buildString {
                    append("${definition.concurrencyPolicy.maxConcurrentTasks} tasks max")
                    if (definition.concurrencyPolicy.perProviderLimits.isNotEmpty()) {
                        append(" · Per-provider limits: ")
                        append(definition.concurrencyPolicy.perProviderLimits.entries.joinToString { "${it.key.value}=${it.value}" })
                    }
                },
                endCap = "${definition.concurrencyPolicy.maxConcurrentTasks} max",
            )
            AzphaltRecord(
                seed = "policy-tests",
                eyebrow = "Test design",
                title = "Test design policy",
                body = when (definition.testDesignPolicy) {
                    TestDesignPolicy.None -> "No test design injection"
                    TestDesignPolicy.BeforeImplementation -> "Pre-code verification injected before implementation"
                    TestDesignPolicy.AfterImplementation -> "Post-code regression tests injected after implementation"
                    TestDesignPolicy.BeforeAndAfterImplementation -> "Pre-code verification and post-code regression tests injected"
                },
                endCap = definition.testDesignPolicy.name,
            )
            AzphaltRecord(
                seed = "policy-cache",
                eyebrow = "Prompt reuse",
                title = "Prompt reuse policy",
                body = when (definition.promptReusePolicy) {
                    PromptReusePolicy.ProviderDefault -> "Provider decides cache behavior"
                    PromptReusePolicy.PreferCache -> "Cache reads preferred where supported"
                    PromptReusePolicy.DisableCache -> "Prompt caching disabled"
                },
                endCap = definition.promptReusePolicy.name,
            )

        }
    }
}

@Composable
private fun ProviderChoiceRow(
    selectedProviderId: String?,
    connectedProviderIds: Set<String>,
    onSelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = "Auto",
            seed = "provider-choice-auto-${selectedProviderId.orEmpty()}",
            selected = selectedProviderId == null,
            onClick = { onSelected(null) },
        )
        ProviderCatalog.entries
            .filter { it.id in connectedProviderIds }
            .forEach { entry ->
                AzphaltPill(
                    label = entry.displayName,
                    seed = "provider-choice-${entry.id}-${selectedProviderId.orEmpty()}",
                    selected = selectedProviderId == entry.id,
                    onClick = { onSelected(entry.id) },
                )
            }
    }
}

@Composable
internal fun ProviderSettingsScreen(
    connectedProviderIds: Set<String> = emptySet(),
    onCheckProviderHealth: suspend () -> Map<String, String> = { emptyMap() },
    onClearWorkflowData: () -> Unit = {},
    onExportJson: suspend () -> String? = { null },
    onImportJson: (String) -> Unit = {},
    projectFileService: ProjectFileService? = null,
    onExportCurrentProjectFile: suspend () -> IveProjectExport? = { null },
    onImportProjectFile: suspend (String) -> String? = { null },
    onExportDiagnosticBundle: suspend () -> String? = { null },
    onConfigureProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
    distributedComputeState: DistributedComputeUiState = DistributedComputeUiState(),
    onSaveDistributedCompute: (DistributedComputeConfiguration, String?) -> Unit = { _, _ -> },
    onDisconnectDistributedCompute: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var diagnosticBundle by remember { mutableStateOf<String?>(null) }
    var importDraft by rememberDurableStringState("settings.import-json.draft")
    var importMode by rememberDurableBooleanState("settings.import-json.open")
    var clearConfirm by remember { mutableStateOf(false) }
    var healthResults by remember { mutableStateOf<Map<String, String>?>(null) }
    var healthChecking by remember { mutableStateOf(false) }
    var exportTriggered by remember { mutableStateOf(false) }
    var diagnosticTriggered by remember { mutableStateOf(false) }
    var detectedProjectFiles by remember { mutableStateOf<List<ProjectFileDescriptor>>(emptyList()) }
    var projectFileMessage by remember { mutableStateOf<String?>(null) }
    var projectFileRefresh by remember { mutableStateOf(0) }
    val computeConfiguration = distributedComputeState.configuration
    var relayUrlDraft by rememberDurableStringState(
        "settings.compute.relay-url",
        computeConfiguration.relayUrl,
    )
    var poolIdDraft by rememberDurableStringState(
        "settings.compute.pool-id",
        computeConfiguration.poolId,
    )
    var nodeIdDraft by rememberDurableStringState(
        "settings.compute.node-id",
        computeConfiguration.nodeId,
    )
    var nodeNameDraft by rememberDurableStringState(
        "settings.compute.node-name",
        computeConfiguration.displayName,
    )
    // Unsaved secret text is deliberately not copied into general Settings. Once Save is pressed,
    // the token is synchronously committed to platform-secure credential storage.
    var tokenDraft by remember(distributedComputeState.tokenConfigured) { mutableStateOf("") }
    var maxParallelDraft by rememberDurableStringState(
        "settings.compute.max-parallel",
        computeConfiguration.maxParallelLeases.toString(),
    )
    var sharingEnabledDraft by rememberDurableBooleanState(
        "settings.compute.sharing-enabled",
        computeConfiguration.sharingEnabled,
    )
    var allowMeteredDraft by rememberDurableBooleanState(
        "settings.compute.allow-metered",
        computeConfiguration.allowMeteredNetwork,
    )
    var requirePowerDraft by rememberDurableBooleanState(
        "settings.compute.require-power",
        computeConfiguration.requireExternalPower,
    )

    LaunchedEffect(projectFileService, projectFileRefresh) {
        detectedProjectFiles = projectFileService?.detected().orEmpty()
    }

    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SETTINGS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        ProviderSectionLabel("AI Providers")
        Text(
            "Connect as many providers as you have access to. Every swarm role can be routed independently from the Swarm screen.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        ProviderCatalog.entries.forEach { entry ->
            val connected = entry.id in connectedProviderIds
            val health = healthResults?.get(entry.id)
            AzphaltRecord(
                seed = "provider-catalog-${entry.id}",
                eyebrow = "Provider",
                title = entry.displayName,
                body = buildString {
                    append(entry.description)
                    append("\n")
                    append(if (connected) health ?: "Credential configured" else "No credential configured")
                },
                endCap = when {
                    !connected -> "Not configured"
                    health != null -> health.substringBefore(" ·")
                    else -> "Connected"
                },
                well = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            if (connected) "Reconfigure" else "Connect",
                            "configure-provider-${entry.id}",
                            onClick = { onConfigureProvider(entry.id) },
                        )
                        if (connected) {
                            AzphaltPill(
                                "Disconnect",
                                "disconnect-provider-${entry.id}",
                                onClick = { onDisconnectProvider(entry.id) },
                            )
                        }
                        AzphaltPill(
                            "Get API key",
                            "get-key-${entry.id}",
                            onClick = { uriHandler.openUri(entry.apiKeyUrl) },
                        )
                    }
                },
            )
        }

        if (healthChecking) {
            LaunchedEffect(Unit) {
                healthResults = onCheckProviderHealth()
                healthChecking = false
            }
        }
        AzphaltPill(
            if (healthResults == null) "Check provider health" else "Refresh health",
            "health-check",
            onClick = {
                healthChecking = true
                healthResults = null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        ProviderSectionLabel("Compute Pool")
        Text(
            "Connect this device to the same Aive compute pool as your other phones and desktops. " +
                "Every device connects outbound to the relay, so no inbound port or shared local network is required.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        AzphaltRecord(
            seed = "distributed-compute-status",
            eyebrow = "Distributed compute",
            title = if (distributedComputeState.connected) "Connected" else "Not connected",
            body = buildString {
                append(if (distributedComputeState.ready) "Pool configured" else "Relay, pool, device identity, and token are required")
                if (distributedComputeState.onlineNodes.isNotEmpty()) {
                    append("\n")
                    append(distributedComputeState.onlineNodes.size)
                    append(" node")
                    if (distributedComputeState.onlineNodes.size != 1) append("s")
                    append(" online")
                }
                distributedComputeState.lastError?.let {
                    append("\n")
                    append(it)
                }
            },
            endCap = when {
                distributedComputeState.connected -> "Online"
                distributedComputeState.ready -> "Ready"
                else -> "Setup"
            },
        )
        OutlinedTextField(
            value = relayUrlDraft,
            onValueChange = { relayUrlDraft = it },
            label = { Text("Relay URL (wss://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = poolIdDraft,
            onValueChange = { poolIdDraft = it },
            label = { Text("Compute pool ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nodeNameDraft,
            onValueChange = { nodeNameDraft = it },
            label = { Text("This device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nodeIdDraft,
            onValueChange = { nodeIdDraft = it },
            label = { Text("Stable device ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = {
                Text(
                    if (distributedComputeState.tokenConfigured) {
                        "Relay token (leave blank to keep existing)"
                    } else {
                        "Relay token"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxParallelDraft,
            onValueChange = { value -> maxParallelDraft = value.filter(Char::isDigit).take(2) },
            label = { Text("Maximum simultaneous remote jobs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                if (sharingEnabledDraft) "Sharing compute: ON" else "Sharing compute: OFF",
                "distributed-sharing-toggle",
                selected = sharingEnabledDraft,
                onClick = { sharingEnabledDraft = !sharingEnabledDraft },
            )
            AzphaltPill(
                if (allowMeteredDraft) "Metered: allowed" else "Metered: blocked",
                "distributed-metered-toggle",
                selected = allowMeteredDraft,
                onClick = { allowMeteredDraft = !allowMeteredDraft },
            )
        }
        AzphaltPill(
            if (requirePowerDraft) "External power required" else "Battery use allowed",
            "distributed-power-toggle",
            selected = requirePowerDraft,
            onClick = { requirePowerDraft = !requirePowerDraft },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                "Save & connect",
                "distributed-save",
                onClick = {
                    val maxParallel = maxParallelDraft.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    onSaveDistributedCompute(
                        DistributedComputeConfiguration(
                            relayUrl = relayUrlDraft.trim(),
                            poolId = poolIdDraft.trim(),
                            nodeId = nodeIdDraft.trim(),
                            displayName = nodeNameDraft.trim(),
                            sharingEnabled = sharingEnabledDraft,
                            maxParallelLeases = maxParallel,
                            allowMeteredNetwork = allowMeteredDraft,
                            requireExternalPower = requirePowerDraft,
                        ),
                        tokenDraft.trim().takeIf(String::isNotEmpty),
                    )
                    tokenDraft = ""
                },
            )
            if (distributedComputeState.ready) {
                AzphaltPill(
                    "Disconnect",
                    "distributed-disconnect",
                    onClick = onDisconnectDistributedCompute,
                )
            }
        }
        if (distributedComputeState.onlineNodes.isNotEmpty()) {
            Text("ONLINE COMPUTE NODES", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            distributedComputeState.onlineNodes.sortedBy { it.displayName }.forEach { node ->
                AzphaltRecord(
                    seed = "compute-node-" + node.nodeId,
                    eyebrow = node.platform.name,
                    title = node.displayName,
                    body = buildString {
                        append(node.architecture)
                        append(" · ")
                        append(node.logicalProcessors)
                        append(" logical CPUs · ")
                        append(node.memoryMiB)
                        append(" MiB")
                        if (node.accelerators.isNotEmpty()) {
                            append("\n")
                            append(node.accelerators.joinToString { it.name })
                        }
                        if (node.installedModelIds.isNotEmpty()) {
                            append(" · ")
                            append(node.installedModelIds.size)
                            append(" local model(s)")
                        }
                    },
                    endCap = if (node.acceptsWork) "Available" else "Observe only",
                )
            }
        }

        ProviderSectionLabel("Projects")
        if (projectFileService != null) {
            Text(
                "Project files use the .ive extension. The detected list includes Aive's project folder and files you previously selected elsewhere.",
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    "Save current project",
                    "ive-save-default",
                    onClick = {
                        scope.launch {
                            runCatching {
                                val export = onExportCurrentProjectFile()
                                    ?: error("No project is currently loaded")
                                projectFileService.saveDefault(export.fileName, export.content)
                            }.onSuccess { descriptor ->
                                projectFileMessage = "Saved ${descriptor.displayName}"
                                projectFileRefresh += 1
                            }.onFailure { failure ->
                                projectFileMessage = failure.message ?: "Project save failed"
                            }
                        }
                    },
                )
                AzphaltPill(
                    "Save project as…",
                    "ive-save-as",
                    onClick = {
                        scope.launch {
                            runCatching {
                                val export = onExportCurrentProjectFile()
                                    ?: error("No project is currently loaded")
                                projectFileService.saveAs(export.fileName, export.content)
                            }.onSuccess { descriptor ->
                                if (descriptor != null) {
                                    projectFileMessage = "Saved ${descriptor.displayName}"
                                    projectFileRefresh += 1
                                }
                            }.onFailure { failure ->
                                projectFileMessage = failure.message ?: "Project save failed"
                            }
                        }
                    },
                )
            }
            if (detectedProjectFiles.isEmpty()) {
                Text(
                    "No .ive project files detected yet.",
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            } else {
                detectedProjectFiles.forEach { descriptor ->
                    AzphaltRecord(
                        seed = "ive-project-${descriptor.id}",
                        eyebrow = "Project file",
                        title = descriptor.displayName,
                        body = descriptor.locationLabel,
                        endCap = "Load",
                        well = {
                            AzphaltPill(
                                "Load project",
                                "ive-load-${descriptor.id}",
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val opened = projectFileService.read(descriptor)
                                                ?: error("Project file is no longer available")
                                            onImportProjectFile(opened.content)
                                                ?: error("Project could not be loaded")
                                        }.onSuccess { projectName ->
                                            projectFileMessage = "Loaded $projectName"
                                            projectFileRefresh += 1
                                        }.onFailure { failure ->
                                            projectFileMessage = failure.message ?: "Project load failed"
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
            AzphaltPill(
                "Choose .ive file from another location…",
                "ive-open-picker",
                onClick = {
                    scope.launch {
                        runCatching {
                            val opened = projectFileService.chooseAndRead() ?: return@launch
                            onImportProjectFile(opened.content)
                                ?: error("Project could not be loaded")
                        }.onSuccess { projectName ->
                            projectFileMessage = "Loaded $projectName"
                            projectFileRefresh += 1
                        }.onFailure { failure ->
                            projectFileMessage = failure.message ?: "Project load failed"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                "Refresh detected project files",
                "ive-refresh",
                onClick = { projectFileRefresh += 1 },
                modifier = Modifier.fillMaxWidth(),
            )
            projectFileMessage?.let { message ->
                AzphaltRecord(
                    seed = "ive-project-message",
                    eyebrow = "Project files",
                    title = message,
                    body = "Portable project state remains separate from provider API keys and repository tokens.",
                    endCap = "OK",
                    onClick = { projectFileMessage = null },
                )
            }
        }

        ProviderSectionLabel("Data")
        if (exportTriggered) {
            LaunchedEffect(Unit) {
                exportedJson = onExportJson()
                exportTriggered = false
            }
        }
        if (exportedJson == null) {
            AzphaltPill(
                "Export workflow data to JSON",
                "export-trigger",
                onClick = { exportTriggered = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = exportedJson!!,
                onValueChange = {},
                readOnly = true,
                label = { Text("Exported JSON (${exportedJson!!.length} chars)") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            AzphaltPill("Dismiss", "export-dismiss", onClick = { exportedJson = null })
        }

        if (!importMode) {
            AzphaltPill(
                "Import data from JSON",
                "import-mode-enter",
                onClick = { importMode = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = importDraft,
                onValueChange = { importDraft = it },
                label = { Text("Paste exported JSON") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill("Import", "import-confirm", onClick = {
                    if (importDraft.isNotBlank()) {
                        onImportJson(importDraft)
                        importDraft = ""
                        importMode = false
                    }
                })
                AzphaltPill("Cancel", "import-cancel", onClick = {
                    importDraft = ""
                    importMode = false
                })
            }
        }

        ProviderSectionLabel("Diagnostics")
        if (diagnosticTriggered) {
            LaunchedEffect(Unit) {
                diagnosticBundle = onExportDiagnosticBundle()
                diagnosticTriggered = false
            }
        }
        if (diagnosticBundle == null) {
            AzphaltPill(
                "Export diagnostic bundle",
                "diagnostic-trigger",
                onClick = { diagnosticTriggered = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AzphaltRecord(
                seed = "diagnostic-result",
                eyebrow = "Diagnostic",
                title = "Run diagnostic bundle",
                body = diagnosticBundle!!.take(300).let { if (diagnosticBundle!!.length > 300) "$it…" else it },
                endCap = "${diagnosticBundle!!.length} chars",
                onClick = { diagnosticBundle = null },
            )
        }

        ProviderSectionLabel("Privacy")
        if (!clearConfirm) {
            AzphaltPill(
                "Delete all workflow data",
                "clear-data-enter",
                onClick = { clearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AzphaltRecord(
                seed = "clear-confirm",
                eyebrow = "Destructive",
                title = "Delete all workflow data?",
                body = "Permanently removes all projects, runs, events, and artifacts from this device. Provider credentials are managed separately.",
                endCap = "Irreversible",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill("Delete everything", "clear-data-confirm", onClick = {
                    onClearWorkflowData()
                    clearConfirm = false
                })
                AzphaltPill("Cancel", "clear-data-cancel", onClick = { clearConfirm = false })
            }
        }
    }
}

@Composable
private fun ProviderSectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

private fun RoleDefinition.providerDepartment(): String = when (id.value) {
    "orchestrator" -> "Executive"
    "product-manager", "researcher", "ux-designer" -> "Product"
    "architect", "epa-representative", "implementation-engineer" -> "Engineering"
    "crash-test-dummy", "qa-engineer", "adversarial-reviewer", "code-reviewer", "recovery-engineer" -> "Assurance"
    "release-engineer" -> "Delivery"
    else -> "Custom"
}
