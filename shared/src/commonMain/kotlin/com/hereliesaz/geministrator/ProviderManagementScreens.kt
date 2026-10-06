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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.common_connect
import com.hereliesaz.geministrator.resources.common_connected
import com.hereliesaz.geministrator.resources.common_dismiss
import com.hereliesaz.geministrator.resources.common_retry
import com.hereliesaz.geministrator.resources.settings_chars
import com.hereliesaz.geministrator.resources.settings_download_and_a_capable_machine
import com.hereliesaz.geministrator.resources.settings_of_roles_currently_have
import com.hereliesaz.geministrator.resources.settings_ai_providers
import com.hereliesaz.geministrator.resources.settings_automatic_crash_reports_off
import com.hereliesaz.geministrator.resources.settings_automatic_crash_reports_on
import com.hereliesaz.geministrator.resources.settings_available
import com.hereliesaz.geministrator.resources.settings_battery_use_allowed
import com.hereliesaz.geministrator.resources.settings_check_provider_health
import com.hereliesaz.geministrator.resources.settings_choose_ive_file_from_another_location
import com.hereliesaz.geministrator.resources.settings_compute_pool
import com.hereliesaz.geministrator.resources.settings_compute_pool_id
import com.hereliesaz.geministrator.resources.settings_connect_as_many_providers_as_you
import com.hereliesaz.geministrator.resources.settings_connect_this_device_to_the_same
import com.hereliesaz.geministrator.resources.settings_crash_reports
import com.hereliesaz.geministrator.resources.settings_credential_configured
import com.hereliesaz.geministrator.resources.settings_data
import com.hereliesaz.geministrator.resources.settings_delete_all_workflow_data
import com.hereliesaz.geministrator.resources.settings_delete_all_workflow_data_2
import com.hereliesaz.geministrator.resources.settings_delete_everything
import com.hereliesaz.geministrator.resources.settings_destructive
import com.hereliesaz.geministrator.resources.settings_device_model_no_workflow_content_or
import com.hereliesaz.geministrator.resources.settings_diagnostic
import com.hereliesaz.geministrator.resources.settings_diagnostics
import com.hereliesaz.geministrator.resources.settings_disconnect
import com.hereliesaz.geministrator.resources.settings_distributed_compute
import com.hereliesaz.geministrator.resources.settings_every_device_connects_outbound_to_the
import com.hereliesaz.geministrator.resources.settings_export_diagnostic_bundle
import com.hereliesaz.geministrator.resources.settings_export_workflow_data_to_json
import com.hereliesaz.geministrator.resources.settings_exported_json_chars
import com.hereliesaz.geministrator.resources.settings_external_power_required
import com.hereliesaz.geministrator.resources.settings_get_api_key
import com.hereliesaz.geministrator.resources.settings_hereliesaz_aive_github_issue_tracker_exception
import com.hereliesaz.geministrator.resources.settings_import
import com.hereliesaz.geministrator.resources.settings_import_data_from_json
import com.hereliesaz.geministrator.resources.settings_install_failed
import com.hereliesaz.geministrator.resources.settings_install_local_planner
import com.hereliesaz.geministrator.resources.settings_install_released_specialists
import com.hereliesaz.geministrator.resources.settings_installing
import com.hereliesaz.geministrator.resources.settings_irreversible
import com.hereliesaz.geministrator.resources.settings_load
import com.hereliesaz.geministrator.resources.settings_load_project
import com.hereliesaz.geministrator.resources.settings_loaded
import com.hereliesaz.geministrator.resources.settings_local_model_s
import com.hereliesaz.geministrator.resources.settings_local_orchestration
import com.hereliesaz.geministrator.resources.settings_local_orchestration_2
import com.hereliesaz.geministrator.resources.settings_local_planner
import com.hereliesaz.geministrator.resources.settings_local_planner_2
import com.hereliesaz.geministrator.resources.settings_local_planner_installed_remove
import com.hereliesaz.geministrator.resources.settings_local_specialists_installed_remove
import com.hereliesaz.geministrator.resources.settings_logical_cpus
import com.hereliesaz.geministrator.resources.settings_maximum_simultaneous_remote_jobs
import com.hereliesaz.geministrator.resources.settings_metered_allowed
import com.hereliesaz.geministrator.resources.settings_metered_blocked
import com.hereliesaz.geministrator.resources.settings_mib
import com.hereliesaz.geministrator.resources.settings_no_credential_configured
import com.hereliesaz.geministrator.resources.settings_no_ive_project_files_detected_yet
import com.hereliesaz.geministrator.resources.settings_node
import com.hereliesaz.geministrator.resources.settings_not_configured
import com.hereliesaz.geministrator.resources.settings_not_connected
import com.hereliesaz.geministrator.resources.settings_observe_only
import com.hereliesaz.geministrator.resources.settings_ok
import com.hereliesaz.geministrator.resources.settings_on_by_default_while_the_aive
import com.hereliesaz.geministrator.resources.settings_online
import com.hereliesaz.geministrator.resources.settings_online_2
import com.hereliesaz.geministrator.resources.settings_online_compute_nodes
import com.hereliesaz.geministrator.resources.settings_paste_exported_json
import com.hereliesaz.geministrator.resources.settings_permanently_removes_all_projects_runs_events
import com.hereliesaz.geministrator.resources.settings_plans_workflows_on_this_computer_instead
import com.hereliesaz.geministrator.resources.settings_pool_configured
import com.hereliesaz.geministrator.resources.settings_portable_project_state_remains_separate_from
import com.hereliesaz.geministrator.resources.settings_privacy
import com.hereliesaz.geministrator.resources.settings_project_file
import com.hereliesaz.geministrator.resources.settings_project_files
import com.hereliesaz.geministrator.resources.settings_project_files_use_the_ive_extension
import com.hereliesaz.geministrator.resources.settings_project_load_failed
import com.hereliesaz.geministrator.resources.settings_project_save_failed
import com.hereliesaz.geministrator.resources.settings_projects
import com.hereliesaz.geministrator.resources.settings_provider
import com.hereliesaz.geministrator.resources.settings_ready
import com.hereliesaz.geministrator.resources.settings_reconfigure
import com.hereliesaz.geministrator.resources.settings_refresh_detected_project_files
import com.hereliesaz.geministrator.resources.settings_refresh_health
import com.hereliesaz.geministrator.resources.settings_relay_pool_device_identity_and_token
import com.hereliesaz.geministrator.resources.settings_relay_token
import com.hereliesaz.geministrator.resources.settings_relay_token_leave_blank_to_keep
import com.hereliesaz.geministrator.resources.settings_relay_url_wss
import com.hereliesaz.geministrator.resources.settings_retry_install
import com.hereliesaz.geministrator.resources.settings_retry_specialist_install
import com.hereliesaz.geministrator.resources.settings_run_diagnostic_bundle
import com.hereliesaz.geministrator.resources.settings_runs_released_orchestration_utility_specialists_on
import com.hereliesaz.geministrator.resources.settings_save_connect
import com.hereliesaz.geministrator.resources.settings_save_current_project
import com.hereliesaz.geministrator.resources.settings_save_project_as
import com.hereliesaz.geministrator.resources.settings_saved
import com.hereliesaz.geministrator.resources.settings_settings
import com.hereliesaz.geministrator.resources.settings_setup
import com.hereliesaz.geministrator.resources.settings_sharing_compute_off
import com.hereliesaz.geministrator.resources.settings_sharing_compute_on
import com.hereliesaz.geministrator.resources.settings_stable_device_id
import com.hereliesaz.geministrator.resources.settings_this_device_name
import com.hereliesaz.geministrator.resources.settings_unreleased_roles_keep_the_deterministic_implementation
import com.hereliesaz.geministrator.resources.settings_whenever_the_local_planner_is_not
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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
        ?: (runtimeState as? ApplicationRuntimeState.NoProject)?.roles
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
    crashReportingSetting: CrashReportingSetting? = null,
    localPlannerSetting: LocalPlannerSetting? = null,
    localOrchestrationSpecialistSetting: LocalOrchestrationSpecialistSetting? = null,
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
        Text(stringResource(Res.string.settings_settings), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        ProviderSectionLabel(stringResource(Res.string.settings_ai_providers))
        Text(
            stringResource(Res.string.settings_connect_as_many_providers_as_you),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        ProviderCatalog.entries.forEach { entry ->
            val connected = entry.id in connectedProviderIds
            val health = healthResults?.get(entry.id)
            AzphaltRecord(
                seed = "provider-catalog-${entry.id}",
                eyebrow = stringResource(Res.string.settings_provider),
                title = entry.displayName,
                body = buildString {
                    append(entry.description)
                    append("\n")
                    append(if (connected) health ?: stringResource(Res.string.settings_credential_configured) else stringResource(Res.string.settings_no_credential_configured))
                },
                endCap = when {
                    !connected -> stringResource(Res.string.settings_not_configured)
                    health != null -> health.substringBefore(" ·")
                    else -> stringResource(Res.string.common_connected)
                },
                well = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            if (connected) stringResource(Res.string.settings_reconfigure) else stringResource(Res.string.common_connect),
                            "configure-provider-${entry.id}",
                            onClick = { onConfigureProvider(entry.id) },
                        )
                        if (connected) {
                            AzphaltPill(
                                stringResource(Res.string.settings_disconnect),
                                "disconnect-provider-${entry.id}",
                                onClick = { onDisconnectProvider(entry.id) },
                            )
                        }
                        AzphaltPill(
                            stringResource(Res.string.settings_get_api_key),
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
            if (healthResults == null) stringResource(Res.string.settings_check_provider_health) else stringResource(Res.string.settings_refresh_health),
            "health-check",
            onClick = {
                healthChecking = true
                healthResults = null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        crashReportingSetting?.let { setting ->
            ProviderSectionLabel(stringResource(Res.string.settings_crash_reports))
            Text(
                stringResource(Res.string.settings_on_by_default_while_the_aive) +
                    stringResource(Res.string.settings_hereliesaz_aive_github_issue_tracker_exception) +
                    stringResource(Res.string.settings_device_model_no_workflow_content_or),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            AzphaltPill(
                if (setting.enabled) stringResource(Res.string.settings_automatic_crash_reports_on) else stringResource(Res.string.settings_automatic_crash_reports_off),
                "crash-reporting-toggle",
                selected = setting.enabled,
                onClick = { setting.onEnabledChange(!setting.enabled) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        localPlannerSetting?.let { setting ->
            ProviderSectionLabel(stringResource(Res.string.settings_local_planner))
            Text(
                stringResource(Res.string.settings_plans_workflows_on_this_computer_instead) +
                    stringResource(Res.string.settings_download_and_a_capable_machine, setting.downloadSize) +
                    stringResource(Res.string.settings_whenever_the_local_planner_is_not),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            when (val status = setting.status) {
                LocalPlannerStatus.NotInstalled -> AzphaltPill(
                    stringResource(Res.string.settings_install_local_planner, setting.downloadSize),
                    "local-planner-install",
                    onClick = setting.onInstall,
                    modifier = Modifier.fillMaxWidth(),
                )
                is LocalPlannerStatus.Installing -> AzphaltRecord(
                    seed = "local-planner-installing",
                    eyebrow = stringResource(Res.string.settings_local_planner_2),
                    title = stringResource(Res.string.settings_installing),
                    body = status.progress,
                    endCap = "…",
                )
                LocalPlannerStatus.Installed -> AzphaltPill(
                    stringResource(Res.string.settings_local_planner_installed_remove),
                    "local-planner-remove",
                    selected = true,
                    onClick = setting.onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
                is LocalPlannerStatus.Failed -> {
                    AzphaltRecord(
                        seed = "local-planner-failed",
                        eyebrow = stringResource(Res.string.settings_local_planner_2),
                        title = stringResource(Res.string.settings_install_failed),
                        body = status.message,
                        endCap = stringResource(Res.string.common_retry),
                    )
                    AzphaltPill(
                        stringResource(Res.string.settings_retry_install, setting.downloadSize),
                        "local-planner-retry",
                        onClick = setting.onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        localOrchestrationSpecialistSetting?.let { setting ->
            ProviderSectionLabel(stringResource(Res.string.settings_local_orchestration))
            Text(
                stringResource(Res.string.settings_runs_released_orchestration_utility_specialists_on) +
                    stringResource(Res.string.settings_of_roles_currently_have, setting.releasedRoles, setting.totalRoles) +
                    stringResource(Res.string.settings_unreleased_roles_keep_the_deterministic_implementation),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            when (val status = setting.status) {
                LocalOrchestrationSpecialistStatus.NotInstalled -> AzphaltPill(
                    stringResource(Res.string.settings_install_released_specialists),
                    "local-orchestration-install",
                    onClick = setting.onInstall,
                    modifier = Modifier.fillMaxWidth(),
                )
                is LocalOrchestrationSpecialistStatus.Installing -> AzphaltRecord(
                    seed = "local-orchestration-installing",
                    eyebrow = stringResource(Res.string.settings_local_orchestration_2),
                    title = stringResource(Res.string.settings_installing),
                    body = status.progress,
                    endCap = "…",
                )
                LocalOrchestrationSpecialistStatus.Installed -> AzphaltPill(
                    stringResource(Res.string.settings_local_specialists_installed_remove),
                    "local-orchestration-remove",
                    selected = true,
                    onClick = setting.onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
                is LocalOrchestrationSpecialistStatus.Failed -> {
                    AzphaltRecord(
                        seed = "local-orchestration-failed",
                        eyebrow = stringResource(Res.string.settings_local_orchestration_2),
                        title = stringResource(Res.string.settings_install_failed),
                        body = status.message,
                        endCap = stringResource(Res.string.common_retry),
                    )
                    AzphaltPill(
                        stringResource(Res.string.settings_retry_specialist_install),
                        "local-orchestration-retry",
                        onClick = setting.onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        ProviderSectionLabel(stringResource(Res.string.settings_compute_pool))
        Text(
            stringResource(Res.string.settings_connect_this_device_to_the_same) +
                stringResource(Res.string.settings_every_device_connects_outbound_to_the),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        AzphaltRecord(
            seed = "distributed-compute-status",
            eyebrow = stringResource(Res.string.settings_distributed_compute),
            title = if (distributedComputeState.connected) stringResource(Res.string.common_connected) else stringResource(Res.string.settings_not_connected),
            body = buildString {
                append(if (distributedComputeState.ready) stringResource(Res.string.settings_pool_configured) else stringResource(Res.string.settings_relay_pool_device_identity_and_token))
                if (distributedComputeState.onlineNodes.isNotEmpty()) {
                    append("\n")
                    append(distributedComputeState.onlineNodes.size)
                    append(stringResource(Res.string.settings_node))
                    if (distributedComputeState.onlineNodes.size != 1) append("s")
                    append(stringResource(Res.string.settings_online))
                }
                distributedComputeState.lastError?.let {
                    append("\n")
                    append(it)
                }
            },
            endCap = when {
                distributedComputeState.connected -> stringResource(Res.string.settings_online_2)
                distributedComputeState.ready -> stringResource(Res.string.settings_ready)
                else -> stringResource(Res.string.settings_setup)
            },
        )
        OutlinedTextField(
            value = relayUrlDraft,
            onValueChange = { relayUrlDraft = it },
            label = { Text(stringResource(Res.string.settings_relay_url_wss)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = poolIdDraft,
            onValueChange = { poolIdDraft = it },
            label = { Text(stringResource(Res.string.settings_compute_pool_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nodeNameDraft,
            onValueChange = { nodeNameDraft = it },
            label = { Text(stringResource(Res.string.settings_this_device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nodeIdDraft,
            onValueChange = { nodeIdDraft = it },
            label = { Text(stringResource(Res.string.settings_stable_device_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = {
                Text(
                    if (distributedComputeState.tokenConfigured) {
                        stringResource(Res.string.settings_relay_token_leave_blank_to_keep)
                    } else {
                        stringResource(Res.string.settings_relay_token)
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxParallelDraft,
            onValueChange = { value -> maxParallelDraft = value.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(Res.string.settings_maximum_simultaneous_remote_jobs)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                if (sharingEnabledDraft) stringResource(Res.string.settings_sharing_compute_on) else stringResource(Res.string.settings_sharing_compute_off),
                "distributed-sharing-toggle",
                selected = sharingEnabledDraft,
                onClick = { sharingEnabledDraft = !sharingEnabledDraft },
            )
            AzphaltPill(
                if (allowMeteredDraft) stringResource(Res.string.settings_metered_allowed) else stringResource(Res.string.settings_metered_blocked),
                "distributed-metered-toggle",
                selected = allowMeteredDraft,
                onClick = { allowMeteredDraft = !allowMeteredDraft },
            )
        }
        AzphaltPill(
            if (requirePowerDraft) stringResource(Res.string.settings_external_power_required) else stringResource(Res.string.settings_battery_use_allowed),
            "distributed-power-toggle",
            selected = requirePowerDraft,
            onClick = { requirePowerDraft = !requirePowerDraft },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                stringResource(Res.string.settings_save_connect),
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
                    stringResource(Res.string.settings_disconnect),
                    "distributed-disconnect",
                    onClick = onDisconnectDistributedCompute,
                )
            }
        }
        if (distributedComputeState.onlineNodes.isNotEmpty()) {
            Text(stringResource(Res.string.settings_online_compute_nodes), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            distributedComputeState.onlineNodes.sortedBy { it.displayName }.forEach { node ->
                AzphaltRecord(
                    seed = "compute-node-" + node.nodeId,
                    eyebrow = node.platform.name,
                    title = node.displayName,
                    body = buildString {
                        append(node.architecture)
                        append(" · ")
                        append(node.logicalProcessors)
                        append(stringResource(Res.string.settings_logical_cpus))
                        append(node.memoryMiB)
                        append(stringResource(Res.string.settings_mib))
                        if (node.accelerators.isNotEmpty()) {
                            append("\n")
                            append(node.accelerators.joinToString { it.name })
                        }
                        if (node.installedModelIds.isNotEmpty()) {
                            append(" · ")
                            append(node.installedModelIds.size)
                            append(stringResource(Res.string.settings_local_model_s))
                        }
                    },
                    endCap = if (node.acceptsWork) stringResource(Res.string.settings_available) else stringResource(Res.string.settings_observe_only),
                )
            }
        }

        ProviderSectionLabel(stringResource(Res.string.settings_projects))
        if (projectFileService != null) {
            Text(
                stringResource(Res.string.settings_project_files_use_the_ive_extension),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    stringResource(Res.string.settings_save_current_project),
                    "ive-save-default",
                    onClick = {
                        scope.launch {
                            runCatching {
                                val export = onExportCurrentProjectFile()
                                    ?: error("No project is currently loaded")
                                projectFileService.saveDefault(export.fileName, export.content)
                            }.onSuccess { descriptor ->
                                projectFileMessage = getString(Res.string.settings_saved, descriptor.displayName)
                                projectFileRefresh += 1
                            }.onFailure { failure ->
                                projectFileMessage = failure.message ?: getString(Res.string.settings_project_save_failed)
                            }
                        }
                    },
                )
                AzphaltPill(
                    stringResource(Res.string.settings_save_project_as),
                    "ive-save-as",
                    onClick = {
                        scope.launch {
                            runCatching {
                                val export = onExportCurrentProjectFile()
                                    ?: error("No project is currently loaded")
                                projectFileService.saveAs(export.fileName, export.content)
                            }.onSuccess { descriptor ->
                                if (descriptor != null) {
                                    projectFileMessage = getString(Res.string.settings_saved, descriptor.displayName)
                                    projectFileRefresh += 1
                                }
                            }.onFailure { failure ->
                                projectFileMessage = failure.message ?: getString(Res.string.settings_project_save_failed)
                            }
                        }
                    },
                )
            }
            if (detectedProjectFiles.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_no_ive_project_files_detected_yet),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            } else {
                detectedProjectFiles.forEach { descriptor ->
                    AzphaltRecord(
                        seed = "ive-project-${descriptor.id}",
                        eyebrow = stringResource(Res.string.settings_project_file),
                        title = descriptor.displayName,
                        body = descriptor.locationLabel,
                        endCap = stringResource(Res.string.settings_load),
                        well = {
                            AzphaltPill(
                                stringResource(Res.string.settings_load_project),
                                "ive-load-${descriptor.id}",
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val opened = projectFileService.read(descriptor)
                                                ?: error("Project file is no longer available")
                                            onImportProjectFile(opened.content)
                                                ?: error("Project could not be loaded")
                                        }.onSuccess { projectName ->
                                            projectFileMessage = getString(Res.string.settings_loaded, projectName)
                                            projectFileRefresh += 1
                                        }.onFailure { failure ->
                                            projectFileMessage = failure.message ?: getString(Res.string.settings_project_load_failed)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
            AzphaltPill(
                stringResource(Res.string.settings_choose_ive_file_from_another_location),
                "ive-open-picker",
                onClick = {
                    scope.launch {
                        runCatching {
                            val opened = projectFileService.chooseAndRead() ?: return@launch
                            onImportProjectFile(opened.content)
                                ?: error("Project could not be loaded")
                        }.onSuccess { projectName ->
                            projectFileMessage = getString(Res.string.settings_loaded, projectName)
                            projectFileRefresh += 1
                        }.onFailure { failure ->
                            projectFileMessage = failure.message ?: getString(Res.string.settings_project_load_failed)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                stringResource(Res.string.settings_refresh_detected_project_files),
                "ive-refresh",
                onClick = { projectFileRefresh += 1 },
                modifier = Modifier.fillMaxWidth(),
            )
            projectFileMessage?.let { message ->
                AzphaltRecord(
                    seed = "ive-project-message",
                    eyebrow = stringResource(Res.string.settings_project_files),
                    title = message,
                    body = stringResource(Res.string.settings_portable_project_state_remains_separate_from),
                    endCap = stringResource(Res.string.settings_ok),
                    onClick = { projectFileMessage = null },
                )
            }
        }

        ProviderSectionLabel(stringResource(Res.string.settings_data))
        if (exportTriggered) {
            LaunchedEffect(Unit) {
                exportedJson = onExportJson()
                exportTriggered = false
            }
        }
        if (exportedJson == null) {
            AzphaltPill(
                stringResource(Res.string.settings_export_workflow_data_to_json),
                "export-trigger",
                onClick = { exportTriggered = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = exportedJson!!,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(Res.string.settings_exported_json_chars, exportedJson!!.length)) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            AzphaltPill(stringResource(Res.string.common_dismiss), "export-dismiss", onClick = { exportedJson = null })
        }

        if (!importMode) {
            AzphaltPill(
                stringResource(Res.string.settings_import_data_from_json),
                "import-mode-enter",
                onClick = { importMode = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = importDraft,
                onValueChange = { importDraft = it },
                label = { Text(stringResource(Res.string.settings_paste_exported_json)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(stringResource(Res.string.settings_import), "import-confirm", onClick = {
                    if (importDraft.isNotBlank()) {
                        onImportJson(importDraft)
                        importDraft = ""
                        importMode = false
                    }
                })
                AzphaltPill(stringResource(Res.string.common_cancel), "import-cancel", onClick = {
                    importDraft = ""
                    importMode = false
                })
            }
        }

        ProviderSectionLabel(stringResource(Res.string.settings_diagnostics))
        if (diagnosticTriggered) {
            LaunchedEffect(Unit) {
                diagnosticBundle = onExportDiagnosticBundle()
                diagnosticTriggered = false
            }
        }
        if (diagnosticBundle == null) {
            AzphaltPill(
                stringResource(Res.string.settings_export_diagnostic_bundle),
                "diagnostic-trigger",
                onClick = { diagnosticTriggered = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AzphaltRecord(
                seed = "diagnostic-result",
                eyebrow = stringResource(Res.string.settings_diagnostic),
                title = stringResource(Res.string.settings_run_diagnostic_bundle),
                body = diagnosticBundle!!.take(300).let { if (diagnosticBundle!!.length > 300) "$it…" else it },
                endCap = stringResource(Res.string.settings_chars, diagnosticBundle!!.length),
                onClick = { diagnosticBundle = null },
            )
        }

        ProviderSectionLabel(stringResource(Res.string.settings_privacy))
        if (!clearConfirm) {
            AzphaltPill(
                stringResource(Res.string.settings_delete_all_workflow_data),
                "clear-data-enter",
                onClick = { clearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AzphaltRecord(
                seed = "clear-confirm",
                eyebrow = stringResource(Res.string.settings_destructive),
                title = stringResource(Res.string.settings_delete_all_workflow_data_2),
                body = stringResource(Res.string.settings_permanently_removes_all_projects_runs_events),
                endCap = stringResource(Res.string.settings_irreversible),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(stringResource(Res.string.settings_delete_everything), "clear-data-confirm", onClick = {
                    onClearWorkflowData()
                    clearConfirm = false
                })
                AzphaltPill(stringResource(Res.string.common_cancel), "clear-data-cancel", onClick = { clearConfirm = false })
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
