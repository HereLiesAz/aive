package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.FlowchartLanguage
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.SpreadsheetFormat
import com.hereliesaz.geministrator.domain.SpreadsheetSource
import com.hereliesaz.geministrator.domain.SqlDatabaseSource
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.workflow.MermaidFlowchartParser
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.common_remove
import com.hereliesaz.geministrator.resources.swarm_1_10_000_rows_larger_sources
import com.hereliesaz.geministrator.resources.swarm_max
import com.hereliesaz.geministrator.resources.swarm_nodes_edges
import com.hereliesaz.geministrator.resources.swarm_tasks_max
import com.hereliesaz.geministrator.resources.swarm_add_flowchart
import com.hereliesaz.geministrator.resources.swarm_add_orchestration_role
import com.hereliesaz.geministrator.resources.swarm_add_role
import com.hereliesaz.geministrator.resources.swarm_add_spreadsheet
import com.hereliesaz.geministrator.resources.swarm_add_sql_database
import com.hereliesaz.geministrator.resources.swarm_add_to_swarm
import com.hereliesaz.geministrator.resources.swarm_agent
import com.hereliesaz.geministrator.resources.swarm_agent_provider
import com.hereliesaz.geministrator.resources.swarm_aive_context_input
import com.hereliesaz.geministrator.resources.swarm_allow_script_sql_mutations
import com.hereliesaz.geministrator.resources.swarm_allow_script_writes
import com.hereliesaz.geministrator.resources.swarm_app_spreadsheet
import com.hereliesaz.geministrator.resources.swarm_app_spreadsheet_file
import com.hereliesaz.geministrator.resources.swarm_app_sqlite_database
import com.hereliesaz.geministrator.resources.swarm_apply_changes
import com.hereliesaz.geministrator.resources.swarm_assurance
import com.hereliesaz.geministrator.resources.swarm_attach_data_and_visual_logic_without
import com.hereliesaz.geministrator.resources.swarm_attached_surfaces
import com.hereliesaz.geministrator.resources.swarm_authority
import com.hereliesaz.geministrator.resources.swarm_authority_2
import com.hereliesaz.geministrator.resources.swarm_auto
import com.hereliesaz.geministrator.resources.swarm_auto_merge_after_verification_passes
import com.hereliesaz.geministrator.resources.swarm_available
import com.hereliesaz.geministrator.resources.swarm_cache_reads_preferred_where_supported
import com.hereliesaz.geministrator.resources.swarm_cancel_add
import com.hereliesaz.geministrator.resources.swarm_changes_delivered_via_pull_request
import com.hereliesaz.geministrator.resources.swarm_changes_integrated_manually
import com.hereliesaz.geministrator.resources.swarm_choose_spreadsheet
import com.hereliesaz.geministrator.resources.swarm_choose_sqlite_database
import com.hereliesaz.geministrator.resources.swarm_complete_the_execution_source_fields_before
import com.hereliesaz.geministrator.resources.swarm_concurrency
import com.hereliesaz.geministrator.resources.swarm_concurrency_policy
import com.hereliesaz.geministrator.resources.swarm_confirm
import com.hereliesaz.geministrator.resources.swarm_custom
import com.hereliesaz.geministrator.resources.swarm_delivery
import com.hereliesaz.geministrator.resources.swarm_description
import com.hereliesaz.geministrator.resources.swarm_document_content_uri
import com.hereliesaz.geministrator.resources.swarm_document_uri
import com.hereliesaz.geministrator.resources.swarm_edit
import com.hereliesaz.geministrator.resources.swarm_edit_orchestration_role
import com.hereliesaz.geministrator.resources.swarm_engineering
import com.hereliesaz.geministrator.resources.swarm_envelope_limit
import com.hereliesaz.geministrator.resources.swarm_execution
import com.hereliesaz.geministrator.resources.swarm_execution_source
import com.hereliesaz.geministrator.resources.swarm_executive
import com.hereliesaz.geministrator.resources.swarm_first_row_is_headers
import com.hereliesaz.geministrator.resources.swarm_flowchart
import com.hereliesaz.geministrator.resources.swarm_flowchart_could_not_be_parsed
import com.hereliesaz.geministrator.resources.swarm_github_actions
import com.hereliesaz.geministrator.resources.swarm_github_runner
import com.hereliesaz.geministrator.resources.swarm_google_sheets_spreadsheet_id
import com.hereliesaz.geministrator.resources.swarm_https_csv_tsv_url
import com.hereliesaz.geministrator.resources.swarm_https_spreadsheet
import com.hereliesaz.geministrator.resources.swarm_inline_csv_tsv
import com.hereliesaz.geministrator.resources.swarm_integration
import com.hereliesaz.geministrator.resources.swarm_integration_policy
import com.hereliesaz.geministrator.resources.swarm_javascript
import com.hereliesaz.geministrator.resources.swarm_javascript_github_actions
import com.hereliesaz.geministrator.resources.swarm_javascript_local_sandbox
import com.hereliesaz.geministrator.resources.swarm_language_input
import com.hereliesaz.geministrator.resources.swarm_local_javascript_receives_a_frozen_aive
import com.hereliesaz.geministrator.resources.swarm_local_sandbox
import com.hereliesaz.geministrator.resources.swarm_maximum_rows_exposed
import com.hereliesaz.geministrator.resources.swarm_mermaid
import com.hereliesaz.geministrator.resources.swarm_mermaid_flowchart
import com.hereliesaz.geministrator.resources.swarm_mermaid_flowchart_parsed_natively_and_exposed
import com.hereliesaz.geministrator.resources.swarm_move_down
import com.hereliesaz.geministrator.resources.swarm_move_up
import com.hereliesaz.geministrator.resources.swarm_native_preview
import com.hereliesaz.geministrator.resources.swarm_no_orchestration_roles_are_active_add
import com.hereliesaz.geministrator.resources.swarm_no_test_design_injection
import com.hereliesaz.geministrator.resources.swarm_per_provider_limits
import com.hereliesaz.geministrator.resources.swarm_post_code_regression_tests_injected_after
import com.hereliesaz.geministrator.resources.swarm_pre_code_verification_and_post_code
import com.hereliesaz.geministrator.resources.swarm_pre_code_verification_injected_before_implementation
import com.hereliesaz.geministrator.resources.swarm_product
import com.hereliesaz.geministrator.resources.swarm_prompt_caching_disabled
import com.hereliesaz.geministrator.resources.swarm_prompt_reuse
import com.hereliesaz.geministrator.resources.swarm_prompt_reuse_policy
import com.hereliesaz.geministrator.resources.swarm_provider_decides_cache_behavior
import com.hereliesaz.geministrator.resources.swarm_provider_routing
import com.hereliesaz.geministrator.resources.swarm_public_exportable_google_sheets_are_fetched
import com.hereliesaz.geministrator.resources.swarm_public_google_sheet
import com.hereliesaz.geministrator.resources.swarm_python
import com.hereliesaz.geministrator.resources.swarm_python_github_actions
import com.hereliesaz.geministrator.resources.swarm_python_runs_through_a_github_actions
import com.hereliesaz.geministrator.resources.swarm_python_unsupported_local_runner
import com.hereliesaz.geministrator.resources.swarm_read_only
import com.hereliesaz.geministrator.resources.swarm_read_only_database_source
import com.hereliesaz.geministrator.resources.swarm_read_only_source
import com.hereliesaz.geministrator.resources.swarm_read_query
import com.hereliesaz.geministrator.resources.swarm_read_write
import com.hereliesaz.geministrator.resources.swarm_ref_branch_optional
import com.hereliesaz.geministrator.resources.swarm_remove_surface
import com.hereliesaz.geministrator.resources.swarm_required_capabilities
import com.hereliesaz.geministrator.resources.swarm_requires
import com.hereliesaz.geministrator.resources.swarm_reset
import com.hereliesaz.geministrator.resources.swarm_reset_to_aive_defaults
import com.hereliesaz.geministrator.resources.swarm_restore_the_default_swarm
import com.hereliesaz.geministrator.resources.swarm_role_arrangement
import com.hereliesaz.geministrator.resources.swarm_role_id_slug
import com.hereliesaz.geministrator.resources.swarm_role_name
import com.hereliesaz.geministrator.resources.swarm_save_swarm
import com.hereliesaz.geministrator.resources.swarm_saved
import com.hereliesaz.geministrator.resources.swarm_script_input
import com.hereliesaz.geministrator.resources.swarm_sheet_gid
import com.hereliesaz.geministrator.resources.swarm_spreadsheet
import com.hereliesaz.geministrator.resources.swarm_spreadsheet_data
import com.hereliesaz.geministrator.resources.swarm_spreadsheet_source
import com.hereliesaz.geministrator.resources.swarm_sql_database
import com.hereliesaz.geministrator.resources.swarm_sql_source
import com.hereliesaz.geministrator.resources.swarm_sqlite_database_name
import com.hereliesaz.geministrator.resources.swarm_sqlite_document_content_uri
import com.hereliesaz.geministrator.resources.swarm_sqlite_document_uri
import com.hereliesaz.geministrator.resources.swarm_sqlite_query_result_available_to_scripts
import com.hereliesaz.geministrator.resources.swarm_standing_instructions
import com.hereliesaz.geministrator.resources.swarm_surface_alias
import com.hereliesaz.geministrator.resources.swarm_surface_aliases_must_be_unique_and
import com.hereliesaz.geministrator.resources.swarm_surfaces
import com.hereliesaz.geministrator.resources.swarm_swarm
import com.hereliesaz.geministrator.resources.swarm_tabular_rows_available_to_scripts_as
import com.hereliesaz.geministrator.resources.swarm_test_design
import com.hereliesaz.geministrator.resources.swarm_test_design_policy
import com.hereliesaz.geministrator.resources.swarm_the_role_runs_as_an_ai
import com.hereliesaz.geministrator.resources.swarm_the_runner_receives_aive_context_aive
import com.hereliesaz.geministrator.resources.swarm_the_workflow_receives_the_role_task
import com.hereliesaz.geministrator.resources.swarm_these_are_aive_s_semantic_orchestration
import com.hereliesaz.geministrator.resources.swarm_this_id_is_reserved_by_the
import com.hereliesaz.geministrator.resources.swarm_this_restores_aive_s_default_role
import com.hereliesaz.geministrator.resources.swarm_unnamed_surface
import com.hereliesaz.geministrator.resources.swarm_unsaved
import com.hereliesaz.geministrator.resources.swarm_uses_project_default_branch
import com.hereliesaz.geministrator.resources.swarm_warnings
import com.hereliesaz.geministrator.resources.swarm_workflow_file_or_id
import com.hereliesaz.geministrator.resources.swarm_workflow_policies
import com.hereliesaz.geministrator.resources.swarm_working
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CustomCompanyProviderScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    connectedProviderIds: Set<String> = emptySet(),
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    onResetRoleCollection: () -> Unit = {},
    roleSurfaceFilePicker: RoleSurfaceFilePicker? = null,
    modifier: Modifier = Modifier,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val runtimeRoles = liveWorkflow?.roles
        ?: (runtimeState as? ApplicationRuntimeState.NoRun)?.roles
        ?: (runtimeState as? ApplicationRuntimeState.NoProject)?.roles
        ?: emptyList()
    val visibleRoles = runtimeRoles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID }
    var draftRoles by rememberDurableJsonState(
        key = COMPANY_DRAFT_ROLES_KEY,
        serializer = ListSerializer(RoleDefinition.serializer()),
        initialValue = visibleRoles,
    )
    var editingRoleIdValue by rememberDurableStringState(COMPANY_EDITING_ROLE_KEY)
    val editingRoleId = editingRoleIdValue.takeIf(String::isNotBlank)
    var showRoleForm by rememberDurableBooleanState(COMPANY_SHOW_ROLE_FORM_KEY)
    var roleIdDraft by rememberDurableStringState(COMPANY_ROLE_ID_KEY)
    var roleNameDraft by rememberDurableStringState(COMPANY_ROLE_NAME_KEY)
    var roleDescDraft by rememberDurableStringState(COMPANY_ROLE_DESCRIPTION_KEY)
    var roleInstructionsDraft by rememberDurableStringState(COMPANY_ROLE_INSTRUCTIONS_KEY)
    var roleProviderDraftValue by rememberDurableStringState(COMPANY_ROLE_PROVIDER_KEY)
    val roleProviderDraft = roleProviderDraftValue.takeIf(String::isNotBlank)
    var roleExecutionSourceDraft by rememberDurableJsonState(
        key = COMPANY_ROLE_EXECUTION_SOURCE_KEY,
        serializer = RoleExecutionSource.serializer(),
        initialValue = RoleExecutionSource.Agent,
    )
    var roleSurfacesDraft by rememberDurableJsonState(
        key = COMPANY_ROLE_SURFACES_KEY,
        serializer = ListSerializer(RoleSurface.serializer()),
        initialValue = emptyList(),
    )
    var roleCapabilitiesDraft by rememberDurableJsonState(
        key = COMPANY_ROLE_CAPABILITIES_KEY,
        serializer = SetSerializer(AgentCapability.serializer()),
        initialValue = emptySet(),
    )
    var roleAuthoritiesDraft by rememberDurableJsonState(
        key = COMPANY_ROLE_AUTHORITIES_KEY,
        serializer = SetSerializer(RoleAuthority.serializer()),
        initialValue = emptySet(),
    )
    var resetConfirm by remember { mutableStateOf(false) }
    val roleSurfacePickerScope = rememberCoroutineScope()

    LaunchedEffect(visibleRoles, draftRoles, showRoleForm) {
        if (!showRoleForm && draftRoles == visibleRoles) {
            DurableUiState.store.remove(COMPANY_DRAFT_ROLES_KEY)
            DurableUiState.store.remove(COMPANY_EDITING_ROLE_KEY)
            DurableUiState.store.remove(COMPANY_SHOW_ROLE_FORM_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_ID_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_NAME_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_DESCRIPTION_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_INSTRUCTIONS_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_PROVIDER_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_EXECUTION_SOURCE_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_SURFACES_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_CAPABILITIES_KEY)
            DurableUiState.store.remove(COMPANY_ROLE_AUTHORITIES_KEY)
        }
    }

    fun clearEditor() {
        editingRoleIdValue = ""
        showRoleForm = false
        roleIdDraft = ""
        roleNameDraft = ""
        roleDescDraft = ""
        roleInstructionsDraft = ""
        roleProviderDraftValue = ""
        roleExecutionSourceDraft = RoleExecutionSource.Agent
        roleSurfacesDraft = emptyList()
        roleCapabilitiesDraft = emptySet()
        roleAuthoritiesDraft = emptySet()
    }

    fun editRole(role: RoleDefinition) {
        editingRoleIdValue = role.id.value
        showRoleForm = true
        roleIdDraft = role.id.value
        roleNameDraft = role.name
        roleDescDraft = role.description
        roleInstructionsDraft = role.instructions
        roleProviderDraftValue = role.preferredProviderId?.value.orEmpty()
        roleExecutionSourceDraft = role.executionSource
        roleSurfacesDraft = role.surfaces
        roleCapabilitiesDraft = role.capabilitiesRequired
        roleAuthoritiesDraft = role.authorities
    }

    val dirty = draftRoles != visibleRoles
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

    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.swarm_swarm), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            stringResource(Res.string.swarm_these_are_aive_s_semantic_orchestration),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = if (showRoleForm && editingRoleId == null) stringResource(Res.string.swarm_cancel_add) else stringResource(Res.string.swarm_add_role),
                seed = "company-add-role",
                onClick = {
                    if (showRoleForm && editingRoleId == null) {
                        clearEditor()
                    } else {
                        clearEditor()
                        showRoleForm = true
                    }
                },
            )
            AzphaltPill(
                label = stringResource(Res.string.swarm_save_swarm),
                seed = "company-save-collection",
                endCap = if (dirty) stringResource(Res.string.swarm_unsaved) else stringResource(Res.string.swarm_saved),
                onClick = { onSaveRoleCollection(draftRoles) },
            )
        }

        if (!resetConfirm) {
            AzphaltPill(
                label = stringResource(Res.string.swarm_reset_to_aive_defaults),
                seed = "company-reset-enter",
                onClick = { resetConfirm = true },
            )
        } else {
            AzphaltRecord(
                seed = "company-reset-confirmation",
                eyebrow = stringResource(Res.string.swarm_reset),
                title = stringResource(Res.string.swarm_restore_the_default_swarm),
                body = stringResource(Res.string.swarm_this_restores_aive_s_default_role),
                endCap = stringResource(Res.string.swarm_confirm),
                well = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            label = stringResource(Res.string.swarm_reset),
                            seed = "company-reset-confirm",
                            onClick = {
                                resetConfirm = false
                                clearEditor()
                                onResetRoleCollection()
                            },
                        )
                        AzphaltPill(
                            label = stringResource(Res.string.common_cancel),
                            seed = "company-reset-cancel",
                            onClick = { resetConfirm = false },
                        )
                    }
                },
            )
        }

        if (showRoleForm) {
            Text(
                if (editingRoleId == null) stringResource(Res.string.swarm_add_orchestration_role) else stringResource(Res.string.swarm_edit_orchestration_role),
                style = AzphaltType.eyebrow,
                color = Azphalt.currentGround.onPage,
            )
            OutlinedTextField(
                value = roleNameDraft,
                onValueChange = { roleNameDraft = it },
                label = { Text(stringResource(Res.string.swarm_role_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val reservedRoleId = roleIdDraft.trim() == ROLE_COLLECTION_MARKER_ID
            OutlinedTextField(
                value = roleIdDraft,
                onValueChange = { roleIdDraft = it },
                label = { Text(stringResource(Res.string.swarm_role_id_slug)) },
                singleLine = true,
                isError = reservedRoleId,
                supportingText = if (reservedRoleId) {
                    { Text(stringResource(Res.string.swarm_this_id_is_reserved_by_the)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleDescDraft,
                onValueChange = { roleDescDraft = it },
                label = { Text(stringResource(Res.string.swarm_description)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleInstructionsDraft,
                onValueChange = { roleInstructionsDraft = it },
                label = { Text(stringResource(Res.string.swarm_standing_instructions)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            CompanySectionLabel(stringResource(Res.string.swarm_execution_source))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = stringResource(Res.string.swarm_agent_provider),
                    seed = "role-execution-agent",
                    selected = roleExecutionSourceDraft is RoleExecutionSource.Agent,
                    onClick = { roleExecutionSourceDraft = RoleExecutionSource.Agent },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(Res.string.swarm_github_actions),
                    seed = "role-execution-github",
                    selected = roleExecutionSourceDraft is RoleExecutionSource.GitHubAction,
                    onClick = {
                        val current = roleExecutionSourceDraft as? RoleExecutionSource.GitHubAction
                        roleExecutionSourceDraft = RoleExecutionSource.GitHubAction(
                            workflow = current?.workflow.orEmpty(),
                            ref = current?.ref,
                            contextInput = current?.contextInput ?: "aive_context",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(Res.string.swarm_javascript),
                    seed = "role-execution-javascript",
                    selected = (roleExecutionSourceDraft as? RoleExecutionSource.Script)?.language == ScriptLanguage.JavaScript,
                    onClick = {
                        val current = roleExecutionSourceDraft as? RoleExecutionSource.Script
                        roleExecutionSourceDraft = RoleExecutionSource.Script(
                            language = ScriptLanguage.JavaScript,
                            source = current?.source?.takeIf(String::isNotBlank)
                                ?: "return { status: 'completed', output: JSON.stringify(aive) };",
                            runner = if (current?.language == ScriptLanguage.JavaScript) current.runner else ScriptRunner.LocalSandbox,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(Res.string.swarm_python),
                    seed = "role-execution-python",
                    selected = (roleExecutionSourceDraft as? RoleExecutionSource.Script)?.language == ScriptLanguage.Python,
                    onClick = {
                        val current = roleExecutionSourceDraft as? RoleExecutionSource.Script
                        roleExecutionSourceDraft = RoleExecutionSource.Script(
                            language = ScriptLanguage.Python,
                            source = current?.source?.takeIf(String::isNotBlank)
                                ?: "result = {'status': 'completed', 'output': str(aive)}",
                            runner = current?.runner as? ScriptRunner.GitHubActions
                                ?: ScriptRunner.GitHubActions(workflow = ""),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (val source = roleExecutionSourceDraft) {
                RoleExecutionSource.Agent -> {
                    Text(
                        stringResource(Res.string.swarm_the_role_runs_as_an_ai),
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                    CompanyProviderChoiceRow(
                        selectedProviderId = roleProviderDraft,
                        connectedProviderIds = connectedProviderIds,
                        onSelected = { roleProviderDraftValue = it.orEmpty() },
                    )
                }
                is RoleExecutionSource.GitHubAction -> {
                    CompanyGitHubActionFields(
                        workflow = source.workflow,
                        ref = source.ref.orEmpty(),
                        contextInput = source.contextInput,
                        onChange = { workflow, ref, contextInput ->
                            roleExecutionSourceDraft = source.copy(
                                workflow = workflow,
                                ref = ref.trim().takeIf(String::isNotEmpty),
                                contextInput = contextInput.ifBlank { "aive_context" },
                            )
                        },
                    )
                    Text(
                        stringResource(Res.string.swarm_the_workflow_receives_the_role_task),
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }
                is RoleExecutionSource.Script -> {
                    if (source.language == ScriptLanguage.JavaScript) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = stringResource(Res.string.swarm_local_sandbox),
                                seed = "role-script-local",
                                selected = source.runner is ScriptRunner.LocalSandbox,
                                onClick = { roleExecutionSourceDraft = source.copy(runner = ScriptRunner.LocalSandbox) },
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.swarm_github_runner),
                                seed = "role-script-github",
                                selected = source.runner is ScriptRunner.GitHubActions,
                                onClick = {
                                    roleExecutionSourceDraft = source.copy(
                                        runner = source.runner as? ScriptRunner.GitHubActions
                                            ?: ScriptRunner.GitHubActions(workflow = ""),
                                    )
                                },
                            )
                        }
                    } else {
                        Text(
                            stringResource(Res.string.swarm_python_runs_through_a_github_actions),
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                    }

                    OutlinedTextField(
                        value = source.source,
                        onValueChange = { roleExecutionSourceDraft = source.copy(source = it) },
                        label = { Text(if (source.language == ScriptLanguage.JavaScript) stringResource(Res.string.swarm_javascript) else stringResource(Res.string.swarm_python)) },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    when (val runner = source.runner) {
                        ScriptRunner.LocalSandbox -> {
                            Text(
                                stringResource(Res.string.swarm_local_javascript_receives_a_frozen_aive),
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                        }
                        is ScriptRunner.GitHubActions -> {
                            CompanyGitHubScriptRunnerFields(
                                runner = runner,
                                onChange = { updated -> roleExecutionSourceDraft = source.copy(runner = updated) },
                            )
                            Text(
                                stringResource(Res.string.swarm_the_runner_receives_aive_context_aive),
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                        }
                    }
                }
            }

            if (!roleExecutionSourceDraft.isConfiguredExecutionSource()) {
                Text(
                    stringResource(Res.string.swarm_complete_the_execution_source_fields_before),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            CompanySectionLabel(stringResource(Res.string.swarm_attached_surfaces))
            Text(
                stringResource(Res.string.swarm_attach_data_and_visual_logic_without),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = stringResource(Res.string.swarm_add_spreadsheet),
                    seed = "role-surface-add-spreadsheet",
                    onClick = {
                        roleSurfacesDraft = roleSurfacesDraft + RoleSurface.Spreadsheet(
                            alias = nextSurfaceAlias(roleSurfacesDraft, "sheet"),
                            source = SpreadsheetSource.AppFile("role-data.csv"),
                            format = SpreadsheetFormat.Csv,
                            firstRowHeaders = true,
                            writable = true,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(Res.string.swarm_add_sql_database),
                    seed = "role-surface-add-sql",
                    onClick = {
                        roleSurfacesDraft = roleSurfacesDraft + RoleSurface.Sql(
                            alias = nextSurfaceAlias(roleSurfacesDraft, "db"),
                            source = SqlDatabaseSource.AppDatabase("role-data.db"),
                            query = "SELECT name, type FROM sqlite_master ORDER BY name",
                            writable = true,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(Res.string.swarm_add_flowchart),
                    seed = "role-surface-add-flowchart",
                    onClick = {
                        roleSurfacesDraft = roleSurfacesDraft + RoleSurface.Flowchart(
                            alias = nextSurfaceAlias(roleSurfacesDraft, "flow"),
                            language = FlowchartLanguage.Mermaid,
                            source = "flowchart TD\n    A[Start] --> B[Done]",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            roleSurfacesDraft.forEachIndexed { surfaceIndex, surface ->
                CompanyRoleSurfaceEditor(
                    surface = surface,
                    index = surfaceIndex,
                    onChange = { updated ->
                        roleSurfacesDraft = roleSurfacesDraft.toMutableList().also {
                            it[surfaceIndex] = updated
                        }
                    },
                    onRemove = {
                        roleSurfacesDraft = roleSurfacesDraft.filterIndexed { index, _ ->
                            index != surfaceIndex
                        }
                    },
                    onPickSpreadsheet = roleSurfaceFilePicker?.let { picker ->
                        {
                            roleSurfacePickerScope.launch {
                                picker.chooseSpreadsheet()?.let { uri ->
                                    val current = roleSurfacesDraft.getOrNull(surfaceIndex) as? RoleSurface.Spreadsheet
                                    if (current != null) {
                                        roleSurfacesDraft = roleSurfacesDraft.toMutableList().also {
                                            it[surfaceIndex] = current.copy(
                                                source = SpreadsheetSource.DocumentUri(uri),
                                                format = SpreadsheetFormat.Auto,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onPickSqlite = roleSurfaceFilePicker?.let { picker ->
                        {
                            roleSurfacePickerScope.launch {
                                picker.chooseSqliteDatabase()?.let { uri ->
                                    val current = roleSurfacesDraft.getOrNull(surfaceIndex) as? RoleSurface.Sql
                                    if (current != null) {
                                        roleSurfacesDraft = roleSurfacesDraft.toMutableList().also {
                                            it[surfaceIndex] = current.copy(
                                                source = SqlDatabaseSource.DocumentUri(uri),
                                                writable = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
            if (!roleSurfacesDraft.isConfiguredRoleSurfaces()) {
                Text(
                    stringResource(Res.string.swarm_surface_aliases_must_be_unique_and),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            CompanySectionLabel(stringResource(Res.string.swarm_required_capabilities))
            AgentCapability.entries.forEach { capability ->
                AzphaltPill(
                    label = capability.name.humanizeEnumName(),
                    seed = "role-capability-${capability.name}",
                    selected = capability in roleCapabilitiesDraft,
                    onClick = {
                        roleCapabilitiesDraft = roleCapabilitiesDraft.toggle(capability)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CompanySectionLabel(stringResource(Res.string.swarm_authority))
            RoleAuthority.entries.forEach { authority ->
                AzphaltPill(
                    label = authority.name.humanizeEnumName(),
                    seed = "role-authority-${authority.name}",
                    selected = authority in roleAuthoritiesDraft,
                    onClick = {
                        roleAuthoritiesDraft = roleAuthoritiesDraft.toggle(authority)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = if (editingRoleId == null) stringResource(Res.string.swarm_add_to_swarm) else stringResource(Res.string.swarm_apply_changes),
                    seed = "company-role-apply",
                    onClick = {
                        val cleanName = roleNameDraft.trim()
                        val cleanId = roleIdDraft.trim().ifBlank {
                            cleanName.lowercase()
                                .replace(Regex("[^a-z0-9]+"), "-")
                                .trim('-')
                        }
                        if (
                            cleanId.isNotBlank() &&
                            cleanName.isNotBlank() &&
                            cleanId != ROLE_COLLECTION_MARKER_ID &&
                            roleExecutionSourceDraft.isConfiguredExecutionSource() &&
                            roleSurfacesDraft.isConfiguredRoleSurfaces()
                        ) {
                            val role = RoleDefinition(
                                id = RoleDefinitionId(cleanId),
                                name = cleanName,
                                description = roleDescDraft.trim(),
                                instructions = roleInstructionsDraft.trim(),
                                enabled = true,
                                preferredProviderId = if (roleExecutionSourceDraft is RoleExecutionSource.Agent) {
                                    roleProviderDraft?.let(::AgentProviderId)
                                } else {
                                    null
                                },
                                executionSource = roleExecutionSourceDraft,
                                surfaces = roleSurfacesDraft,
                                capabilitiesRequired = roleCapabilitiesDraft,
                                authorities = roleAuthoritiesDraft,
                            )
                            val editingIndex = editingRoleId?.let { oldId ->
                                draftRoles.indexOfFirst { it.id.value == oldId }
                            } ?: -1
                            val conflictingIndex = draftRoles.indexOfFirst {
                                it.id == role.id && it.id.value != editingRoleId
                            }
                            if (conflictingIndex < 0) {
                                draftRoles = if (editingIndex >= 0) {
                                    draftRoles.toMutableList().also { it[editingIndex] = role }
                                } else {
                                    draftRoles + role
                                }
                                clearEditor()
                            }
                        }
                    },
                )
                AzphaltPill(
                    label = stringResource(Res.string.common_cancel),
                    seed = "company-role-cancel",
                    onClick = ::clearEditor,
                )
            }
        }

        CompanySectionLabel(stringResource(Res.string.swarm_role_arrangement))
        if (draftRoles.isEmpty()) {
            Text(
                stringResource(Res.string.swarm_no_orchestration_roles_are_active_add),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
        }
        draftRoles.forEachIndexed { index, role ->
            val assigned = role.preferredProviderId?.value
            val providerLabel = assigned?.let { ProviderCatalog.entry(it)?.displayName ?: it } ?: stringResource(Res.string.swarm_auto)
            AzphaltRecord(
                seed = "custom-company-role-${role.id.value}",
                eyebrow = role.companyDepartment(),
                title = role.name,
                body = buildString {
                    append(role.description)
                    append(stringResource(Res.string.swarm_execution))
                    append(role.executionSource.companyExecutionLabel(providerLabel))
                    if (role.authorities.isNotEmpty()) {
                        append(stringResource(Res.string.swarm_authority_2))
                        append(role.authorities.joinToString { it.name.humanizeEnumName() })
                    }
                    if (role.surfaces.isNotEmpty()) {
                        append(stringResource(Res.string.swarm_surfaces))
                        append(role.surfaces.joinToString { it.alias })
                    }
                    if (role.capabilitiesRequired.isNotEmpty()) {
                        append(stringResource(Res.string.swarm_requires))
                        append(role.capabilitiesRequired.joinToString { it.name.humanizeEnumName() })
                    }
                },
                endCap = if (role.id in activeRoleIds) stringResource(Res.string.swarm_working) else stringResource(Res.string.swarm_available),
                well = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = stringResource(Res.string.swarm_edit),
                                seed = "company-edit-${role.id.value}",
                                onClick = { editRole(role) },
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.common_remove),
                                seed = "company-remove-${role.id.value}",
                                onClick = {
                                    draftRoles = draftRoles.filterNot { it.id == role.id }
                                    if (editingRoleId == role.id.value) clearEditor()
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = stringResource(Res.string.swarm_move_up),
                                seed = "company-up-${role.id.value}",
                                selected = false,
                                onClick = {
                                    if (index > 0) draftRoles = draftRoles.move(index, index - 1)
                                },
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.swarm_move_down),
                                seed = "company-down-${role.id.value}",
                                selected = false,
                                onClick = {
                                    if (index < draftRoles.lastIndex) draftRoles = draftRoles.move(index, index + 1)
                                },
                            )
                        }
                        if (role.executionSource is RoleExecutionSource.Agent) {
                            Text(stringResource(Res.string.swarm_provider_routing), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                            CompanyProviderChoiceRow(
                                selectedProviderId = assigned,
                                connectedProviderIds = connectedProviderIds,
                                onSelected = { selected ->
                                    draftRoles = draftRoles.toMutableList().also { roles ->
                                        roles[index] = role.copy(
                                            preferredProviderId = selected?.let(::AgentProviderId),
                                        )
                                    }
                                },
                            )
                        } else {
                            AzphaltNote(
                                seed = "company-execution-${role.id.value}",
                                label = stringResource(Res.string.swarm_execution_source),
                                value = role.executionSource.companyExecutionLabel(providerLabel),
                            )
                        }
                    }
                },
            )
        }

        if (liveWorkflow != null) {
            val definition = liveWorkflow.definition
            CompanySectionLabel(stringResource(Res.string.swarm_workflow_policies))
            AzphaltRecord(
                seed = "company-policy-integration",
                eyebrow = stringResource(Res.string.swarm_integration),
                title = stringResource(Res.string.swarm_integration_policy),
                body = when (definition.integrationPolicy) {
                    IntegrationPolicy.Manual -> stringResource(Res.string.swarm_changes_integrated_manually)
                    IntegrationPolicy.PullRequest -> stringResource(Res.string.swarm_changes_delivered_via_pull_request)
                    IntegrationPolicy.AutoMergeAfterVerification -> stringResource(Res.string.swarm_auto_merge_after_verification_passes)
                },
                endCap = definition.integrationPolicy.name,
            )
            AzphaltRecord(
                seed = "company-policy-concurrency",
                eyebrow = stringResource(Res.string.swarm_concurrency),
                title = stringResource(Res.string.swarm_concurrency_policy),
                body = buildString {
                    append(stringResource(Res.string.swarm_tasks_max, definition.concurrencyPolicy.maxConcurrentTasks))
                    if (definition.concurrencyPolicy.perProviderLimits.isNotEmpty()) {
                        append(stringResource(Res.string.swarm_per_provider_limits))
                        append(definition.concurrencyPolicy.perProviderLimits.entries.joinToString { "${it.key.value}=${it.value}" })
                    }
                },
                endCap = stringResource(Res.string.swarm_max, definition.concurrencyPolicy.maxConcurrentTasks),
            )
            AzphaltRecord(
                seed = "company-policy-tests",
                eyebrow = stringResource(Res.string.swarm_test_design),
                title = stringResource(Res.string.swarm_test_design_policy),
                body = when (definition.testDesignPolicy) {
                    TestDesignPolicy.None -> stringResource(Res.string.swarm_no_test_design_injection)
                    TestDesignPolicy.BeforeImplementation -> stringResource(Res.string.swarm_pre_code_verification_injected_before_implementation)
                    TestDesignPolicy.AfterImplementation -> stringResource(Res.string.swarm_post_code_regression_tests_injected_after)
                    TestDesignPolicy.BeforeAndAfterImplementation -> stringResource(Res.string.swarm_pre_code_verification_and_post_code)
                },
                endCap = definition.testDesignPolicy.name,
            )
            AzphaltRecord(
                seed = "company-policy-cache",
                eyebrow = stringResource(Res.string.swarm_prompt_reuse),
                title = stringResource(Res.string.swarm_prompt_reuse_policy),
                body = when (definition.promptReusePolicy) {
                    PromptReusePolicy.ProviderDefault -> stringResource(Res.string.swarm_provider_decides_cache_behavior)
                    PromptReusePolicy.PreferCache -> stringResource(Res.string.swarm_cache_reads_preferred_where_supported)
                    PromptReusePolicy.DisableCache -> stringResource(Res.string.swarm_prompt_caching_disabled)
                },
                endCap = definition.promptReusePolicy.name,
            )
        }
    }
}

@Composable
private fun CompanyProviderChoiceRow(
    selectedProviderId: String?,
    connectedProviderIds: Set<String>,
    onSelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.swarm_auto),
            seed = "company-provider-auto-${selectedProviderId.orEmpty()}",
            selected = selectedProviderId == null,
            onClick = { onSelected(null) },
        )
        ProviderCatalog.entries
            .filter { it.id in connectedProviderIds }
            .forEach { entry ->
                AzphaltPill(
                    label = entry.displayName,
                    seed = "company-provider-${entry.id}-${selectedProviderId.orEmpty()}",
                    selected = selectedProviderId == entry.id,
                    onClick = { onSelected(entry.id) },
                )
            }
    }
}

@Composable
private fun CompanyRoleSurfaceEditor(
    surface: RoleSurface,
    index: Int,
    onChange: (RoleSurface) -> Unit,
    onRemove: () -> Unit,
    onPickSpreadsheet: (() -> Unit)?,
    onPickSqlite: (() -> Unit)?,
) {
    AzphaltRecord(
        seed = "role-surface-$index-${surface.alias}",
        eyebrow = when (surface) {
            is RoleSurface.Spreadsheet -> stringResource(Res.string.swarm_spreadsheet)
            is RoleSurface.Sql -> stringResource(Res.string.swarm_sql_database)
            is RoleSurface.Flowchart -> stringResource(Res.string.swarm_flowchart)
        },
        title = surface.alias.ifBlank { stringResource(Res.string.swarm_unnamed_surface) },
        body = when (surface) {
            is RoleSurface.Spreadsheet ->
                stringResource(Res.string.swarm_tabular_rows_available_to_scripts_as, surface.alias)
            is RoleSurface.Sql ->
                stringResource(Res.string.swarm_sqlite_query_result_available_to_scripts, surface.alias)
            is RoleSurface.Flowchart ->
                stringResource(Res.string.swarm_mermaid_flowchart_parsed_natively_and_exposed)
        },
        endCap = when (surface) {
            is RoleSurface.Spreadsheet -> if (surface.writable) stringResource(Res.string.swarm_read_write) else stringResource(Res.string.swarm_read_only)
            is RoleSurface.Sql -> if (surface.writable) stringResource(Res.string.swarm_read_write) else stringResource(Res.string.swarm_read_only)
            is RoleSurface.Flowchart -> stringResource(Res.string.swarm_mermaid)
        },
        selected = true,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = surface.alias,
                    onValueChange = { value ->
                        when (surface) {
                            is RoleSurface.Spreadsheet -> onChange(surface.copy(alias = value))
                            is RoleSurface.Sql -> onChange(surface.copy(alias = value))
                            is RoleSurface.Flowchart -> onChange(surface.copy(alias = value))
                        }
                    },
                    label = { Text(stringResource(Res.string.swarm_surface_alias)) },
                    placeholder = { Text("customers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (surface) {
                    is RoleSurface.Spreadsheet -> CompanySpreadsheetSurfaceFields(
                        surface = surface,
                        onChange = onChange,
                        onPickDocument = onPickSpreadsheet,
                    )
                    is RoleSurface.Sql -> CompanySqlSurfaceFields(
                        surface = surface,
                        onChange = onChange,
                        onPickDocument = onPickSqlite,
                    )
                    is RoleSurface.Flowchart -> CompanyFlowchartSurfaceFields(surface, onChange)
                }

                AzphaltPill(
                    label = stringResource(Res.string.swarm_remove_surface),
                    seed = "role-surface-remove-$index",
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun CompanySpreadsheetSurfaceFields(
    surface: RoleSurface.Spreadsheet,
    onChange: (RoleSurface) -> Unit,
    onPickDocument: (() -> Unit)?,
) {
    CompanySectionLabel(stringResource(Res.string.swarm_spreadsheet_source))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.swarm_app_spreadsheet),
            seed = "sheet-source-app-${surface.alias}",
            selected = surface.source is SpreadsheetSource.AppFile,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.AppFile("role-data.csv"))) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.swarm_inline_csv_tsv),
            seed = "sheet-source-inline-${surface.alias}",
            selected = surface.source is SpreadsheetSource.Inline,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.Inline("column_a,column_b\n"))) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.swarm_https_spreadsheet),
            seed = "sheet-source-https-${surface.alias}",
            selected = surface.source is SpreadsheetSource.Https,
            onClick = {
                onChange(
                    surface.copy(
                        source = SpreadsheetSource.Https("https://"),
                        writable = false,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.swarm_public_google_sheet),
            seed = "sheet-source-google-${surface.alias}",
            selected = surface.source is SpreadsheetSource.GoogleSheet,
            onClick = {
                onChange(
                    surface.copy(
                        source = SpreadsheetSource.GoogleSheet(spreadsheetId = ""),
                        format = SpreadsheetFormat.Csv,
                        writable = false,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.swarm_document_uri),
            seed = "sheet-source-uri-${surface.alias}",
            selected = surface.source is SpreadsheetSource.DocumentUri,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.DocumentUri("content://"))) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (onPickDocument != null) {
            AzphaltPill(
                label = stringResource(Res.string.swarm_choose_spreadsheet),
                seed = "sheet-source-picker-${surface.alias}",
                onClick = onPickDocument,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    when (val source = surface.source) {
        is SpreadsheetSource.AppFile -> OutlinedTextField(
            value = source.name,
            onValueChange = { onChange(surface.copy(source = source.copy(name = it))) },
            label = { Text(stringResource(Res.string.swarm_app_spreadsheet_file)) },
            placeholder = { Text("customers.csv") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.Inline -> OutlinedTextField(
            value = source.text,
            onValueChange = { onChange(surface.copy(source = source.copy(text = it))) },
            label = { Text(stringResource(Res.string.swarm_spreadsheet_data)) },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.Https -> OutlinedTextField(
            value = source.url,
            onValueChange = { onChange(surface.copy(source = source.copy(url = it), writable = false)) },
            label = { Text(stringResource(Res.string.swarm_https_csv_tsv_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.DocumentUri -> OutlinedTextField(
            value = source.uri,
            onValueChange = { onChange(surface.copy(source = source.copy(uri = it))) },
            label = { Text(stringResource(Res.string.swarm_document_content_uri)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.GoogleSheet -> {
            OutlinedTextField(
                value = source.spreadsheetId,
                onValueChange = {
                    onChange(
                        surface.copy(
                            source = source.copy(spreadsheetId = it),
                            format = SpreadsheetFormat.Csv,
                            writable = false,
                        ),
                    )
                },
                label = { Text(stringResource(Res.string.swarm_google_sheets_spreadsheet_id)) },
                placeholder = { Text("1AbCdEf...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = source.gid,
                onValueChange = {
                    onChange(
                        surface.copy(
                            source = source.copy(gid = it),
                            format = SpreadsheetFormat.Csv,
                            writable = false,
                        ),
                    )
                },
                label = { Text(stringResource(Res.string.swarm_sheet_gid)) },
                placeholder = { Text("0") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(Res.string.swarm_public_exportable_google_sheets_are_fetched),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpreadsheetFormat.entries.forEach { format ->
            AzphaltPill(
                label = format.name,
                seed = "sheet-format-${surface.alias}-${format.name}",
                selected = surface.format == format,
                onClick = { onChange(surface.copy(format = format)) },
            )
        }
    }
    AzphaltPill(
        label = stringResource(Res.string.swarm_first_row_is_headers),
        seed = "sheet-headers-${surface.alias}",
        selected = surface.firstRowHeaders,
        onClick = { onChange(surface.copy(firstRowHeaders = !surface.firstRowHeaders)) },
        modifier = Modifier.fillMaxWidth(),
    )
    val writableSource = surface.source is SpreadsheetSource.AppFile ||
        surface.source is SpreadsheetSource.DocumentUri
    AzphaltPill(
        label = if (writableSource) stringResource(Res.string.swarm_allow_script_writes) else stringResource(Res.string.swarm_read_only_source),
        seed = "sheet-writable-${surface.alias}",
        selected = surface.writable && writableSource,
        onClick = {
            if (writableSource) onChange(surface.copy(writable = !surface.writable))
        },
        modifier = Modifier.fillMaxWidth(),
    )
    CompanyMaxRowsField(
        value = surface.maxRows,
        seed = "sheet-max-rows-${surface.alias}",
        onChange = { onChange(surface.copy(maxRows = it)) },
    )
}

@Composable
private fun CompanySqlSurfaceFields(
    surface: RoleSurface.Sql,
    onChange: (RoleSurface) -> Unit,
    onPickDocument: (() -> Unit)?,
) {
    CompanySectionLabel(stringResource(Res.string.swarm_sql_source))
    AzphaltPill(
        label = stringResource(Res.string.swarm_app_sqlite_database),
        seed = "sql-source-app-${surface.alias}",
        selected = surface.source is SqlDatabaseSource.AppDatabase,
        onClick = { onChange(surface.copy(source = SqlDatabaseSource.AppDatabase("role-data.db"))) },
        modifier = Modifier.fillMaxWidth(),
    )
    AzphaltPill(
        label = stringResource(Res.string.swarm_sqlite_document_uri),
        seed = "sql-source-uri-${surface.alias}",
        selected = surface.source is SqlDatabaseSource.DocumentUri,
        onClick = {
            onChange(
                surface.copy(
                    source = SqlDatabaseSource.DocumentUri("content://"),
                    writable = false,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (onPickDocument != null) {
        AzphaltPill(
            label = stringResource(Res.string.swarm_choose_sqlite_database),
            seed = "sql-source-picker-${surface.alias}",
            onClick = onPickDocument,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (val source = surface.source) {
        is SqlDatabaseSource.AppDatabase -> OutlinedTextField(
            value = source.name,
            onValueChange = { onChange(surface.copy(source = source.copy(name = it))) },
            label = { Text(stringResource(Res.string.swarm_sqlite_database_name)) },
            placeholder = { Text("customers.db") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SqlDatabaseSource.DocumentUri -> OutlinedTextField(
            value = source.uri,
            onValueChange = { onChange(surface.copy(source = source.copy(uri = it), writable = false)) },
            label = { Text(stringResource(Res.string.swarm_sqlite_document_content_uri)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = surface.query,
        onValueChange = { onChange(surface.copy(query = it)) },
        label = { Text(stringResource(Res.string.swarm_read_query)) },
        placeholder = { Text("SELECT * FROM customers ORDER BY name") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    val writableSource = surface.source is SqlDatabaseSource.AppDatabase
    AzphaltPill(
        label = if (writableSource) stringResource(Res.string.swarm_allow_script_sql_mutations) else stringResource(Res.string.swarm_read_only_database_source),
        seed = "sql-writable-${surface.alias}",
        selected = surface.writable && writableSource,
        onClick = {
            if (writableSource) onChange(surface.copy(writable = !surface.writable))
        },
        modifier = Modifier.fillMaxWidth(),
    )
    CompanyMaxRowsField(
        value = surface.maxRows,
        seed = "sql-max-rows-${surface.alias}",
        onChange = { onChange(surface.copy(maxRows = it)) },
    )
}

@Composable
private fun CompanyFlowchartSurfaceFields(
    surface: RoleSurface.Flowchart,
    onChange: (RoleSurface) -> Unit,
) {
    OutlinedTextField(
        value = surface.source,
        onValueChange = { onChange(surface.copy(source = it)) },
        label = { Text(stringResource(Res.string.swarm_mermaid_flowchart)) },
        placeholder = { Text("flowchart TD\n    A[Start] --> B[Done]") },
        minLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    val parsed = runCatching { MermaidFlowchartParser.parse(surface.source) }
    parsed.onSuccess { graph ->
        MermaidFlowchartPreview(
            graph = graph,
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltNote(
            seed = "flowchart-parse-${surface.alias}",
            label = stringResource(Res.string.swarm_native_preview),
            value = buildString {
                append(stringResource(Res.string.swarm_nodes_edges, graph.nodes.size, graph.edges.size, graph.direction))
                if (graph.warnings.isNotEmpty()) {
                    append(stringResource(Res.string.swarm_warnings))
                    append(graph.warnings.joinToString("; "))
                }
            },
        )
    }.onFailure { failure ->
        Text(
            failure.message ?: stringResource(Res.string.swarm_flowchart_could_not_be_parsed),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
    }
}

@Composable
private fun CompanyMaxRowsField(
    value: Int,
    seed: String,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw ->
            raw.toIntOrNull()
                ?.coerceIn(1, 10_000)
                ?.let(onChange)
        },
        label = { Text(stringResource(Res.string.swarm_maximum_rows_exposed)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    AzphaltNote(
        seed = seed,
        label = stringResource(Res.string.swarm_envelope_limit),
        value = stringResource(Res.string.swarm_1_10_000_rows_larger_sources),
    )
}

@Composable
private fun CompanyGitHubActionFields(
    workflow: String,
    ref: String,
    contextInput: String,
    onChange: (workflow: String, ref: String, contextInput: String) -> Unit,
) {
    OutlinedTextField(
        value = workflow,
        onValueChange = { onChange(it, ref, contextInput) },
        label = { Text(stringResource(Res.string.swarm_workflow_file_or_id)) },
        placeholder = { Text("aive-role.yml") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ref,
        onValueChange = { onChange(workflow, it, contextInput) },
        label = { Text(stringResource(Res.string.swarm_ref_branch_optional)) },
        placeholder = { Text(stringResource(Res.string.swarm_uses_project_default_branch)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = contextInput,
        onValueChange = { onChange(workflow, ref, it) },
        label = { Text(stringResource(Res.string.swarm_aive_context_input)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CompanyGitHubScriptRunnerFields(
    runner: ScriptRunner.GitHubActions,
    onChange: (ScriptRunner.GitHubActions) -> Unit,
) {
    CompanyGitHubActionFields(
        workflow = runner.workflow,
        ref = runner.ref.orEmpty(),
        contextInput = runner.contextInput,
        onChange = { workflow, ref, contextInput ->
            onChange(
                runner.copy(
                    workflow = workflow,
                    ref = ref.trim().takeIf(String::isNotEmpty),
                    contextInput = contextInput.ifBlank { "aive_context" },
                ),
            )
        },
    )
    OutlinedTextField(
        value = runner.scriptInput,
        onValueChange = { onChange(runner.copy(scriptInput = it.ifBlank { "aive_script" })) },
        label = { Text(stringResource(Res.string.swarm_script_input)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = runner.languageInput,
        onValueChange = { onChange(runner.copy(languageInput = it.ifBlank { "aive_language" })) },
        label = { Text(stringResource(Res.string.swarm_language_input)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun List<RoleSurface>.isConfiguredRoleSurfaces(): Boolean {
    val normalizedAliases = map { it.alias.trim() }
    if (normalizedAliases.any(String::isBlank)) return false
    if (normalizedAliases.distinct().size != size) return false
    return all { surface ->
        when (surface) {
            is RoleSurface.Spreadsheet -> {
                surface.maxRows in 1..10_000 && when (val source = surface.source) {
                    is SpreadsheetSource.AppFile -> source.name.isNotBlank()
                    is SpreadsheetSource.Inline -> true
                    is SpreadsheetSource.DocumentUri -> source.uri.isNotBlank()
                    is SpreadsheetSource.Https -> source.url.startsWith("https://", ignoreCase = true)
                    is SpreadsheetSource.GoogleSheet -> source.spreadsheetId.isNotBlank()
                }
            }
            is RoleSurface.Sql -> {
                surface.maxRows in 1..10_000 &&
                    surface.query.isNotBlank() &&
                    when (val source = surface.source) {
                        is SqlDatabaseSource.AppDatabase -> source.name.isNotBlank()
                        is SqlDatabaseSource.DocumentUri -> source.uri.isNotBlank()
                    }
            }
            is RoleSurface.Flowchart ->
                surface.language == FlowchartLanguage.Mermaid &&
                    runCatching { MermaidFlowchartParser.parse(surface.source) }.isSuccess
        }
    }
}

private fun nextSurfaceAlias(
    surfaces: List<RoleSurface>,
    prefix: String,
): String {
    val used = surfaces.mapTo(mutableSetOf()) { it.alias }
    var index = 1
    while ("$prefix$index" in used) index += 1
    return "$prefix$index"
}

private fun RoleExecutionSource.isConfiguredExecutionSource(): Boolean = when (this) {
    RoleExecutionSource.Agent -> true
    is RoleExecutionSource.GitHubAction -> workflow.isNotBlank() && contextInput.isNotBlank()
    is RoleExecutionSource.Script -> when (val selectedRunner = runner) {
        ScriptRunner.LocalSandbox ->
            language == ScriptLanguage.JavaScript && source.isNotBlank()
        is ScriptRunner.GitHubActions ->
            source.isNotBlank() &&
                selectedRunner.workflow.isNotBlank() &&
                selectedRunner.contextInput.isNotBlank() &&
                selectedRunner.scriptInput.isNotBlank() &&
                selectedRunner.languageInput.isNotBlank()
    }
}

@Composable
private fun RoleExecutionSource.companyExecutionLabel(providerLabel: String): String = when (this) {
    RoleExecutionSource.Agent -> stringResource(Res.string.swarm_agent, providerLabel)
    is RoleExecutionSource.GitHubAction ->
        "GitHub Actions / $workflow${ref?.let { " @ $it" } ?: ""}"
    is RoleExecutionSource.Script -> when (language) {
        ScriptLanguage.JavaScript -> when (val selectedRunner = runner) {
            ScriptRunner.LocalSandbox -> stringResource(Res.string.swarm_javascript_local_sandbox)
            is ScriptRunner.GitHubActions -> stringResource(Res.string.swarm_javascript_github_actions, selectedRunner.workflow)
        }
        ScriptLanguage.Python -> when (val selectedRunner = runner) {
            ScriptRunner.LocalSandbox -> stringResource(Res.string.swarm_python_unsupported_local_runner)
            is ScriptRunner.GitHubActions -> stringResource(Res.string.swarm_python_github_actions, selectedRunner.workflow)
        }
    }
}

@Composable
private fun CompanySectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

@Composable
private fun RoleDefinition.companyDepartment(): String = when (id.value) {
    "orchestrator" -> stringResource(Res.string.swarm_executive)
    "product-manager", "researcher", "ux-designer" -> stringResource(Res.string.swarm_product)
    "architect", "epa-representative", "implementation-engineer" -> stringResource(Res.string.swarm_engineering)
    "crash-test-dummy", "qa-engineer", "adversarial-reviewer", "code-reviewer", "recovery-engineer" -> stringResource(Res.string.swarm_assurance)
    "release-engineer" -> stringResource(Res.string.swarm_delivery)
    else -> stringResource(Res.string.swarm_custom)
}

private const val COMPANY_DRAFT_ROLES_KEY = "company.draft-roles"
private const val COMPANY_EDITING_ROLE_KEY = "company.editing-role"
private const val COMPANY_SHOW_ROLE_FORM_KEY = "company.show-role-form"
private const val COMPANY_ROLE_ID_KEY = "company.role.id"
private const val COMPANY_ROLE_NAME_KEY = "company.role.name"
private const val COMPANY_ROLE_DESCRIPTION_KEY = "company.role.description"
private const val COMPANY_ROLE_INSTRUCTIONS_KEY = "company.role.instructions"
private const val COMPANY_ROLE_PROVIDER_KEY = "company.role.provider"
private const val COMPANY_ROLE_EXECUTION_SOURCE_KEY = "company.role.execution-source"
private const val COMPANY_ROLE_SURFACES_KEY = "company.role.surfaces"
private const val COMPANY_ROLE_CAPABILITIES_KEY = "company.role.capabilities"
private const val COMPANY_ROLE_AUTHORITIES_KEY = "company.role.authorities"

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().also { values ->
        val value = values.removeAt(from)
        values.add(to, value)
    }
}

private fun String.humanizeEnumName(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
