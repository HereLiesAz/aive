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

@Composable
internal fun CustomCompanyProviderScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    connectedProviderIds: Set<String> = emptySet(),
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    onResetRoleCollection: () -> Unit = {},
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
        Text("SWARM", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            "These are Aive's semantic orchestration roles. Each role can run through an AI provider, GitHub Actions, local JavaScript, or a JavaScript/Python GitHub runner while keeping the same role identity and task contract.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = if (showRoleForm && editingRoleId == null) "Cancel add" else "Add role",
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
                label = "Save swarm",
                seed = "company-save-collection",
                endCap = if (dirty) "Unsaved" else "Saved",
                onClick = { onSaveRoleCollection(draftRoles) },
            )
        }

        if (!resetConfirm) {
            AzphaltPill(
                label = "Reset to Aive defaults",
                seed = "company-reset-enter",
                onClick = { resetConfirm = true },
            )
        } else {
            AzphaltRecord(
                seed = "company-reset-confirmation",
                eyebrow = "Reset",
                title = "Restore the default swarm?",
                body = "This restores Aive's default role definitions and default order. Custom roles are removed from the active roster but kept internally for historical workflow compatibility.",
                endCap = "Confirm",
                well = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            label = "Reset",
                            seed = "company-reset-confirm",
                            onClick = {
                                resetConfirm = false
                                clearEditor()
                                onResetRoleCollection()
                            },
                        )
                        AzphaltPill(
                            label = "Cancel",
                            seed = "company-reset-cancel",
                            onClick = { resetConfirm = false },
                        )
                    }
                },
            )
        }

        if (showRoleForm) {
            Text(
                if (editingRoleId == null) "ADD ORCHESTRATION ROLE" else "EDIT ORCHESTRATION ROLE",
                style = AzphaltType.eyebrow,
                color = Azphalt.currentGround.onPage,
            )
            OutlinedTextField(
                value = roleNameDraft,
                onValueChange = { roleNameDraft = it },
                label = { Text("Role name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val reservedRoleId = roleIdDraft.trim() == ROLE_COLLECTION_MARKER_ID
            OutlinedTextField(
                value = roleIdDraft,
                onValueChange = { roleIdDraft = it },
                label = { Text("Role ID (slug)") },
                singleLine = true,
                isError = reservedRoleId,
                supportingText = if (reservedRoleId) {
                    { Text("This ID is reserved by The Aive.") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleDescDraft,
                onValueChange = { roleDescDraft = it },
                label = { Text("Description") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = roleInstructionsDraft,
                onValueChange = { roleInstructionsDraft = it },
                label = { Text("Standing instructions") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            CompanySectionLabel("Execution source")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = "Agent / provider",
                    seed = "role-execution-agent",
                    selected = roleExecutionSourceDraft is RoleExecutionSource.Agent,
                    onClick = { roleExecutionSourceDraft = RoleExecutionSource.Agent },
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = "GitHub Actions",
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
                    label = "JavaScript",
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
                    label = "Python",
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
                        "The role runs as an AI agent. Choose a provider or leave it on Auto.",
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
                        "The workflow receives the role/task payload as JSON in the configured context input.",
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }
                is RoleExecutionSource.Script -> {
                    if (source.language == ScriptLanguage.JavaScript) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = "Local sandbox",
                                seed = "role-script-local",
                                selected = source.runner is ScriptRunner.LocalSandbox,
                                onClick = { roleExecutionSourceDraft = source.copy(runner = ScriptRunner.LocalSandbox) },
                            )
                            AzphaltPill(
                                label = "GitHub runner",
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
                            "Python runs through a GitHub Actions runner, using the same Aive task envelope as other execution sources.",
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                    }

                    OutlinedTextField(
                        value = source.source,
                        onValueChange = { roleExecutionSourceDraft = source.copy(source = it) },
                        label = { Text(if (source.language == ScriptLanguage.JavaScript) "JavaScript" else "Python") },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    when (val runner = source.runner) {
                        ScriptRunner.LocalSandbox -> {
                            Text(
                                "Local JavaScript receives a frozen 'aive' object. Return { status, message, output, artifacts }.",
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
                                "The runner receives aive_context, aive_script, and aive_language by default.",
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                        }
                    }
                }
            }

            if (!roleExecutionSourceDraft.isConfiguredExecutionSource()) {
                Text(
                    "Complete the execution-source fields before saving this role.",
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            CompanySectionLabel("Attached surfaces")
            Text(
                "Attach data and visual logic without changing how the role executes. Scripts and GitHub Actions receive resolved surfaces in aive.surfaces.",
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = "Add spreadsheet",
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
                    label = "Add SQL database",
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
                    label = "Add flowchart",
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
                )
            }
            if (!roleSurfacesDraft.isConfiguredRoleSurfaces()) {
                Text(
                    "Surface aliases must be unique and every attached surface needs a usable source/query/flowchart.",
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
            }

            CompanySectionLabel("Required capabilities")
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

            CompanySectionLabel("Authority")
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
                    label = if (editingRoleId == null) "Add to swarm" else "Apply changes",
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
                    label = "Cancel",
                    seed = "company-role-cancel",
                    onClick = ::clearEditor,
                )
            }
        }

        CompanySectionLabel("Role arrangement")
        if (draftRoles.isEmpty()) {
            Text(
                "No orchestration roles are active. Add roles before saving if you want to launch new workflows.",
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
        }
        draftRoles.forEachIndexed { index, role ->
            val assigned = role.preferredProviderId?.value
            val providerLabel = assigned?.let { ProviderCatalog.entry(it)?.displayName ?: it } ?: "Auto"
            AzphaltRecord(
                seed = "custom-company-role-${role.id.value}",
                eyebrow = role.companyDepartment(),
                title = role.name,
                body = buildString {
                    append(role.description)
                    append("\nExecution: ")
                    append(role.executionSource.companyExecutionLabel(providerLabel))
                    if (role.authorities.isNotEmpty()) {
                        append("\nAuthority: ")
                        append(role.authorities.joinToString { it.name.humanizeEnumName() })
                    }
                    if (role.surfaces.isNotEmpty()) {
                        append("\nSurfaces: ")
                        append(role.surfaces.joinToString { it.alias })
                    }
                    if (role.capabilitiesRequired.isNotEmpty()) {
                        append("\nRequires: ")
                        append(role.capabilitiesRequired.joinToString { it.name.humanizeEnumName() })
                    }
                },
                endCap = if (role.id in activeRoleIds) "Working" else "Available",
                well = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = "Edit",
                                seed = "company-edit-${role.id.value}",
                                onClick = { editRole(role) },
                            )
                            AzphaltPill(
                                label = "Remove",
                                seed = "company-remove-${role.id.value}",
                                onClick = {
                                    draftRoles = draftRoles.filterNot { it.id == role.id }
                                    if (editingRoleId == role.id.value) clearEditor()
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = "Move up",
                                seed = "company-up-${role.id.value}",
                                selected = false,
                                onClick = {
                                    if (index > 0) draftRoles = draftRoles.move(index, index - 1)
                                },
                            )
                            AzphaltPill(
                                label = "Move down",
                                seed = "company-down-${role.id.value}",
                                selected = false,
                                onClick = {
                                    if (index < draftRoles.lastIndex) draftRoles = draftRoles.move(index, index + 1)
                                },
                            )
                        }
                        if (role.executionSource is RoleExecutionSource.Agent) {
                            Text("PROVIDER ROUTING", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
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
                                label = "Execution source",
                                value = role.executionSource.companyExecutionLabel(providerLabel),
                            )
                        }
                    }
                },
            )
        }

        if (liveWorkflow != null) {
            val definition = liveWorkflow.definition
            CompanySectionLabel("Workflow policies")
            AzphaltRecord(
                seed = "company-policy-integration",
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
                seed = "company-policy-concurrency",
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
                seed = "company-policy-tests",
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
                seed = "company-policy-cache",
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
private fun CompanyProviderChoiceRow(
    selectedProviderId: String?,
    connectedProviderIds: Set<String>,
    onSelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = "Auto",
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
) {
    AzphaltRecord(
        seed = "role-surface-$index-${surface.alias}",
        eyebrow = when (surface) {
            is RoleSurface.Spreadsheet -> "Spreadsheet"
            is RoleSurface.Sql -> "SQL database"
            is RoleSurface.Flowchart -> "Flowchart"
        },
        title = surface.alias.ifBlank { "Unnamed surface" },
        body = when (surface) {
            is RoleSurface.Spreadsheet ->
                "Tabular rows available to scripts as aive.surfaces['${surface.alias}']."
            is RoleSurface.Sql ->
                "SQLite query result available to scripts as aive.surfaces['${surface.alias}']."
            is RoleSurface.Flowchart ->
                "Mermaid flowchart parsed natively and exposed as nodes and edges."
        },
        endCap = when (surface) {
            is RoleSurface.Spreadsheet -> if (surface.writable) "Read / write" else "Read only"
            is RoleSurface.Sql -> if (surface.writable) "Read / write" else "Read only"
            is RoleSurface.Flowchart -> "Mermaid"
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
                    label = { Text("Surface alias") },
                    placeholder = { Text("customers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (surface) {
                    is RoleSurface.Spreadsheet -> CompanySpreadsheetSurfaceFields(surface, onChange)
                    is RoleSurface.Sql -> CompanySqlSurfaceFields(surface, onChange)
                    is RoleSurface.Flowchart -> CompanyFlowchartSurfaceFields(surface, onChange)
                }

                AzphaltPill(
                    label = "Remove surface",
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
) {
    CompanySectionLabel("Spreadsheet source")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AzphaltPill(
            label = "App spreadsheet",
            seed = "sheet-source-app-${surface.alias}",
            selected = surface.source is SpreadsheetSource.AppFile,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.AppFile("role-data.csv"))) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = "Inline CSV / TSV",
            seed = "sheet-source-inline-${surface.alias}",
            selected = surface.source is SpreadsheetSource.Inline,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.Inline("column_a,column_b\n"))) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = "HTTPS spreadsheet",
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
            label = "Document URI",
            seed = "sheet-source-uri-${surface.alias}",
            selected = surface.source is SpreadsheetSource.DocumentUri,
            onClick = { onChange(surface.copy(source = SpreadsheetSource.DocumentUri("content://"))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (val source = surface.source) {
        is SpreadsheetSource.AppFile -> OutlinedTextField(
            value = source.name,
            onValueChange = { onChange(surface.copy(source = source.copy(name = it))) },
            label = { Text("App spreadsheet file") },
            placeholder = { Text("customers.csv") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.Inline -> OutlinedTextField(
            value = source.text,
            onValueChange = { onChange(surface.copy(source = source.copy(text = it))) },
            label = { Text("Spreadsheet data") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.Https -> OutlinedTextField(
            value = source.url,
            onValueChange = { onChange(surface.copy(source = source.copy(url = it), writable = false)) },
            label = { Text("HTTPS CSV / TSV URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SpreadsheetSource.DocumentUri -> OutlinedTextField(
            value = source.uri,
            onValueChange = { onChange(surface.copy(source = source.copy(uri = it))) },
            label = { Text("Document content URI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
        label = "First row is headers",
        seed = "sheet-headers-${surface.alias}",
        selected = surface.firstRowHeaders,
        onClick = { onChange(surface.copy(firstRowHeaders = !surface.firstRowHeaders)) },
        modifier = Modifier.fillMaxWidth(),
    )
    val writableSource = surface.source is SpreadsheetSource.AppFile ||
        surface.source is SpreadsheetSource.DocumentUri
    AzphaltPill(
        label = if (writableSource) "Allow script writes" else "Read-only source",
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
) {
    CompanySectionLabel("SQL source")
    AzphaltPill(
        label = "App SQLite database",
        seed = "sql-source-app-${surface.alias}",
        selected = surface.source is SqlDatabaseSource.AppDatabase,
        onClick = { onChange(surface.copy(source = SqlDatabaseSource.AppDatabase("role-data.db"))) },
        modifier = Modifier.fillMaxWidth(),
    )
    AzphaltPill(
        label = "SQLite document URI",
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

    when (val source = surface.source) {
        is SqlDatabaseSource.AppDatabase -> OutlinedTextField(
            value = source.name,
            onValueChange = { onChange(surface.copy(source = source.copy(name = it))) },
            label = { Text("SQLite database name") },
            placeholder = { Text("customers.db") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        is SqlDatabaseSource.DocumentUri -> OutlinedTextField(
            value = source.uri,
            onValueChange = { onChange(surface.copy(source = source.copy(uri = it), writable = false)) },
            label = { Text("SQLite document content URI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = surface.query,
        onValueChange = { onChange(surface.copy(query = it)) },
        label = { Text("Read query") },
        placeholder = { Text("SELECT * FROM customers ORDER BY name") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    val writableSource = surface.source is SqlDatabaseSource.AppDatabase
    AzphaltPill(
        label = if (writableSource) "Allow script SQL mutations" else "Read-only database source",
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
        label = { Text("Mermaid flowchart") },
        placeholder = { Text("flowchart TD\n    A[Start] --> B[Done]") },
        minLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    val parsed = runCatching { MermaidFlowchartParser.parse(surface.source) }
    parsed.onSuccess { graph ->
        AzphaltNote(
            seed = "flowchart-parse-${surface.alias}",
            label = "Native preview",
            value = buildString {
                append("${graph.nodes.size} nodes · ${graph.edges.size} edges · ${graph.direction}")
                if (graph.edges.isNotEmpty()) {
                    append("\n")
                    append(
                        graph.edges.take(6).joinToString("\n") { edge ->
                            "${edge.from} → ${edge.to}${edge.label?.let { " · $it" } ?: ""}"
                        },
                    )
                }
                if (graph.warnings.isNotEmpty()) {
                    append("\nWarnings: ")
                    append(graph.warnings.joinToString("; "))
                }
            },
        )
    }.onFailure { failure ->
        Text(
            failure.message ?: "Flowchart could not be parsed.",
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
        label = { Text("Maximum rows exposed") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    AzphaltNote(
        seed = seed,
        label = "Envelope limit",
        value = "1–10,000 rows; larger sources are marked truncated.",
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
        label = { Text("Workflow file or ID") },
        placeholder = { Text("aive-role.yml") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ref,
        onValueChange = { onChange(workflow, it, contextInput) },
        label = { Text("Ref / branch (optional)") },
        placeholder = { Text("Uses project default branch") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = contextInput,
        onValueChange = { onChange(workflow, ref, it) },
        label = { Text("Aive context input") },
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
        label = { Text("Script input") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = runner.languageInput,
        onValueChange = { onChange(runner.copy(languageInput = it.ifBlank { "aive_language" })) },
        label = { Text("Language input") },
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

private fun RoleExecutionSource.companyExecutionLabel(providerLabel: String): String = when (this) {
    RoleExecutionSource.Agent -> "Agent / $providerLabel"
    is RoleExecutionSource.GitHubAction ->
        "GitHub Actions / $workflow${ref?.let { " @ $it" } ?: ""}"
    is RoleExecutionSource.Script -> when (language) {
        ScriptLanguage.JavaScript -> when (val selectedRunner = runner) {
            ScriptRunner.LocalSandbox -> "JavaScript / local sandbox"
            is ScriptRunner.GitHubActions -> "JavaScript / GitHub Actions / ${selectedRunner.workflow}"
        }
        ScriptLanguage.Python -> when (val selectedRunner = runner) {
            ScriptRunner.LocalSandbox -> "Python / unsupported local runner"
            is ScriptRunner.GitHubActions -> "Python / GitHub Actions / ${selectedRunner.workflow}"
        }
    }
}

@Composable
private fun CompanySectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

private fun RoleDefinition.companyDepartment(): String = when (id.value) {
    "orchestrator" -> "Executive"
    "product-manager", "researcher", "ux-designer" -> "Product"
    "architect", "epa-representative", "implementation-engineer" -> "Engineering"
    "crash-test-dummy", "qa-engineer", "adversarial-reviewer", "code-reviewer", "recovery-engineer" -> "Assurance"
    "release-engineer" -> "Delivery"
    else -> "Custom"
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
