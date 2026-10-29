package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.awaitsNestedHumanApproval
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_approve
import com.hereliesaz.geministrator.resources.common_reject
import com.hereliesaz.geministrator.resources.inspector_excluded_by_redaction_policy
import com.hereliesaz.geministrator.resources.inspector_any_capable_provider
import com.hereliesaz.geministrator.resources.inspector_approve_nested_gate
import com.hereliesaz.geministrator.resources.inspector_approve_plan
import com.hereliesaz.geministrator.resources.inspector_approve_task
import com.hereliesaz.geministrator.resources.inspector_artifact
import com.hereliesaz.geministrator.resources.inspector_artifacts
import com.hereliesaz.geministrator.resources.inspector_attempt
import com.hereliesaz.geministrator.resources.inspector_blocked
import com.hereliesaz.geministrator.resources.inspector_context_artifacts_sent
import com.hereliesaz.geministrator.resources.inspector_default_runner
import com.hereliesaz.geministrator.resources.inspector_executor
import com.hereliesaz.geministrator.resources.inspector_executor_ref
import com.hereliesaz.geministrator.resources.inspector_external_run
import com.hereliesaz.geministrator.resources.inspector_github_actions
import com.hereliesaz.geministrator.resources.inspector_inline_evidence
import com.hereliesaz.geministrator.resources.inspector_local_sandbox
import com.hereliesaz.geministrator.resources.inspector_message
import com.hereliesaz.geministrator.resources.inspector_message_agent
import com.hereliesaz.geministrator.resources.inspector_no_run_is_loaded
import com.hereliesaz.geministrator.resources.inspector_now
import com.hereliesaz.geministrator.resources.inspector_payload_context
import com.hereliesaz.geministrator.resources.inspector_plan
import com.hereliesaz.geministrator.resources.inspector_proceed
import com.hereliesaz.geministrator.resources.inspector_progress
import com.hereliesaz.geministrator.resources.inspector_provider
import com.hereliesaz.geministrator.resources.inspector_provider_run
import com.hereliesaz.geministrator.resources.inspector_provider_target
import com.hereliesaz.geministrator.resources.inspector_reject_plan
import com.hereliesaz.geministrator.resources.inspector_remote
import com.hereliesaz.geministrator.resources.inspector_responsible_role
import com.hereliesaz.geministrator.resources.inspector_retry_task
import com.hereliesaz.geministrator.resources.inspector_role
import com.hereliesaz.geministrator.resources.inspector_role_instructions_redacted_by_policy
import com.hereliesaz.geministrator.resources.inspector_send
import com.hereliesaz.geministrator.resources.inspector_send_message
import com.hereliesaz.geministrator.resources.inspector_sending
import com.hereliesaz.geministrator.resources.inspector_sent
import com.hereliesaz.geministrator.resources.inspector_state
import com.hereliesaz.geministrator.resources.inspector_stop_workflow
import com.hereliesaz.geministrator.resources.inspector_stored_evidence
import com.hereliesaz.geministrator.resources.inspector_task_is_not_part_of
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_stop_title
import com.hereliesaz.geministrator.resources.confirm_stop_body
import com.hereliesaz.geministrator.resources.confirm_stop_action
import com.hereliesaz.geministrator.resources.confirm_reject_plan_title
import com.hereliesaz.geministrator.resources.confirm_reject_plan_body
import com.hereliesaz.geministrator.resources.confirm_reject_plan_action
import com.hereliesaz.geministrator.resources.inspector_hint_approve_plan
import com.hereliesaz.geministrator.resources.inspector_hint_approve_task
import com.hereliesaz.geministrator.resources.inspector_hint_approve_nested
import org.jetbrains.compose.resources.getString
import com.hereliesaz.geministrator.resources.mind_budget
import com.hereliesaz.geministrator.resources.mind_instructions
import com.hereliesaz.geministrator.resources.mind_no_budget
import com.hereliesaz.geministrator.resources.mind_tools
import com.hereliesaz.geministrator.resources.creature_needs_nothing
import com.hereliesaz.geministrator.resources.creature_tab_mind
import com.hereliesaz.geministrator.resources.creature_tab_work
import com.hereliesaz.geministrator.resources.creature_tab_needs
import org.jetbrains.compose.resources.stringResource
import com.hereliesaz.geministrator.resources.inspector_acceptance_criteria_count
import org.jetbrains.compose.resources.pluralStringResource

@Composable
internal fun TechnicalInspector(
    selectedTaskId: String,
    liveWorkflow: LiveWorkflowPresentation?,
    onApproveTask: (String) -> Unit = {},
    onRejectPlan: (String) -> Unit = {},
    onResolveEscalation: (String, Boolean) -> Unit = { _, _ -> },
    onMessageAgent: suspend (String, String) -> String? = { _, _ -> "Messaging is unavailable" },
    modifier: Modifier = Modifier,
    tab: CreatureTab = CreatureTab.Needs,
    onTabChange: (CreatureTab) -> Unit = {},
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    connectedProviderIds: Set<String> = emptySet(),
    roleSurfaceFilePicker: RoleSurfaceFilePicker? = null,
) {
    if (liveWorkflow == null) {
        EmptyTechnicalInspector(stringResource(Res.string.inspector_no_run_is_loaded), modifier)
        return
    }

    val taskId = TaskDefinitionId(selectedTaskId)
    val task = liveWorkflow.definition.tasks.firstOrNull { it.id == taskId }
    val taskRun = liveWorkflow.run.taskRuns[taskId]
    if (task == null || taskRun == null) {
        EmptyTechnicalInspector(stringResource(Res.string.inspector_task_is_not_part_of, selectedTaskId), modifier)
        return
    }

    val scope = rememberCoroutineScope()
    var messageDraft by rememberDurableStringState(
        key = "inspector.${liveWorkflow.run.id.value}.$selectedTaskId.message",
    )
    var messageStatus by remember(selectedTaskId) { mutableStateOf<String?>(null) }
    var messageSending by remember(selectedTaskId) { mutableStateOf(false) }
    // Reject plan and Stop workflow cannot be undone, so each asks first: true = stop.
    var confirmingStop by remember(selectedTaskId) { mutableStateOf<Boolean?>(null) }
    var openArtifact by remember(selectedTaskId) { mutableStateOf<String?>(null) }
    val role = taskRun.assignedRoleId?.let { roleId -> liveWorkflow.roles.firstOrNull { it.id == roleId } }
    val executor = taskRun.executor ?: task.effectiveExecutor()
    val identity = role?.name ?: executor.displayName()
    // Everything in the inspector sits on Ink, so its text, hints and fields take the exact
    // opposite of Ink from this surface rather than a colour of their own.
    AzphaltThemedSurface(Azphalt.Ink) {
    Column(
        modifier = modifier
            .background(Azphalt.Ink)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(identity.uppercase(), style = AzphaltType.section, color = azphaltOnSurface())
        Text(task.objective, style = AzphaltType.body, color = azphaltOnSurface())
        CreatureTabs(tab, onTabChange)
        confirmingStop?.let { isStop ->
            AzphaltAlertDialog(
                title = stringResource(if (isStop) Res.string.confirm_stop_title else Res.string.confirm_reject_plan_title),
                text = stringResource(if (isStop) Res.string.confirm_stop_body else Res.string.confirm_reject_plan_body),
                confirmLabel = stringResource(if (isStop) Res.string.confirm_stop_action else Res.string.confirm_reject_plan_action),
                onConfirm = {
                    confirmingStop = null
                    if (isStop) onResolveEscalation(selectedTaskId, false) else onRejectPlan(selectedTaskId)
                },
                onDismissRequest = { confirmingStop = null },
                dismissLabel = stringResource(Res.string.confirm_cancel),
            )
        }
        when (tab) {
            CreatureTab.Needs -> {
                creatureNeeds(taskId, liveWorkflow.definition, liveWorkflow.run, liveWorkflow.roles)
                    ?.let { CreatureNeedsPanel(it, titled = false) }
                if (taskRun.status == TaskRunStatus.AwaitingApproval) {
                    val providerPlan = taskRun.assignedProviderId != null
                    AzphaltPill(
                        label = if (providerPlan) stringResource(Res.string.inspector_approve_plan) else stringResource(Res.string.inspector_approve_task),
                        seed = "approve-$selectedTaskId",
                        onClick = { onApproveTask(selectedTaskId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(if (providerPlan) Res.string.inspector_hint_approve_plan else Res.string.inspector_hint_approve_task),
                        style = AzphaltType.body,
                        color = azphaltOnSurface(),
                    )
                    if (providerPlan) {
                        AzphaltPill(
                            label = stringResource(Res.string.inspector_reject_plan),
                            seed = "reject-plan-$selectedTaskId",
                            onClick = { confirmingStop = false },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (taskRun.awaitsNestedHumanApproval()) {
                    AzphaltPill(
                        label = stringResource(Res.string.inspector_approve_nested_gate),
                        seed = "approve-nested-$selectedTaskId",
                        onClick = { onApproveTask(selectedTaskId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(Res.string.inspector_hint_approve_nested), style = AzphaltType.body, color = azphaltOnSurface())
                }
                if (taskRun.status == TaskRunStatus.Escalated) {
                    AzphaltPill(
                        label = stringResource(Res.string.inspector_retry_task),
                        seed = "escalation-approve-$selectedTaskId",
                        onClick = { onResolveEscalation(selectedTaskId, true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.inspector_stop_workflow),
                        seed = "escalation-reject-$selectedTaskId",
                        onClick = { confirmingStop = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!creatureNeedsYou(taskRun) && creatureNeeds(taskId, liveWorkflow.definition, liveWorkflow.run, liveWorkflow.roles) == null) {
                    Text(stringResource(Res.string.creature_needs_nothing), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            CreatureTab.Work -> {
                // Talking to its agent is part of its work, not something it needs from you.
                if (taskRun.assignedProviderId != null && taskRun.providerRunId != null) {
                    OutlinedTextField(
                        value = messageDraft,
                        onValueChange = {
                            messageDraft = it
                            messageStatus = null
                        },
                        label = { Text(stringResource(Res.string.inspector_message_agent)) },
                        minLines = 2,
                        enabled = !messageSending,
                        colors = azphaltTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AzphaltPill(
                        label = if (messageSending) stringResource(Res.string.inspector_sending) else stringResource(Res.string.inspector_send_message),
                        seed = "message-$selectedTaskId",
                        onClick = {
                            val message = messageDraft.trim()
                            if (message.isNotEmpty() && !messageSending) {
                                messageSending = true
                                messageStatus = null
                                scope.launch {
                                    val failure = onMessageAgent(selectedTaskId, message)
                                    messageSending = false
                                    if (failure == null) {
                                        messageDraft = ""
                                        messageStatus = getString(Res.string.inspector_sent)
                                    } else {
                                        messageStatus = failure
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    messageStatus?.let { LiveInspectorLine(stringResource(Res.string.inspector_message), it, live = true) }
                }
                LiveInspectorLine(stringResource(Res.string.inspector_state), taskRun.status.label())
                LiveInspectorLine(stringResource(Res.string.inspector_executor), executor.displayName())
                LiveInspectorLine(stringResource(Res.string.inspector_executor_ref), executor.reference())
                (taskRun.assignedRoleId?.value ?: task.roleId?.value)
                    ?.takeIf { it != executor.reference() }
                    ?.let { LiveInspectorLine(stringResource(Res.string.inspector_responsible_role), it) }
                LiveInspectorLine(stringResource(Res.string.inspector_provider), taskRun.assignedProviderId?.value ?: "—")
                LiveInspectorLine(stringResource(Res.string.inspector_attempt), taskRun.attempt.toString())
                LiveInspectorLine(stringResource(Res.string.inspector_provider_run), taskRun.providerRunId?.value ?: "—")
                LiveInspectorLine(stringResource(Res.string.inspector_external_run), taskRun.externalRunId ?: "—")
                taskRun.progress?.let { progress ->
                    LiveInspectorLine(stringResource(Res.string.inspector_progress), "${(progress * 100f).toInt()}%", live = true)
                }
                taskRun.progressMessage?.takeIf(String::isNotBlank)?.let {
                    val label = if (
                        taskRun.status == TaskRunStatus.AwaitingApproval &&
                        taskRun.assignedProviderId != null
                    ) {
                        stringResource(Res.string.inspector_plan)
                    } else {
                        stringResource(Res.string.inspector_now)
                    }
                    LiveInspectorLine(label, it, live = true)
                }
                taskRun.blockingReason?.let {
                    LiveInspectorLine(stringResource(Res.string.inspector_blocked), "${it.code} · ${it.message}", live = true)
                }
                // Its artifacts: what it made, each opening on its contents (once the Artifacts place).
                LiveInspectorLine(stringResource(Res.string.inspector_artifacts), taskRun.artifacts.size.toString())
                taskRun.artifacts.forEach { artifact ->
                    val open = openArtifact == artifact.id.value
                    AzphaltRecord(
                        seed = "artifact-${artifact.id.value}",
                        eyebrow = artifact.kind.name,
                        title = artifact.label,
                        body = artifact.uri
                            ?: artifact.textContent?.takeIf(String::isNotBlank)?.let { stringResource(Res.string.inspector_inline_evidence) }
                            ?: stringResource(Res.string.inspector_stored_evidence),
                        endCap = null,
                        selected = open,
                        onClick = { openArtifact = if (open) null else artifact.id.value },
                        well = if (open) {
                            {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    artifact.uri?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
                                    artifact.textContent?.takeIf(String::isNotBlank)?.let {
                                        Text(it, style = AzphaltType.body, color = azphaltOnSurface())
                                    }
                                }
                            }
                        } else null,
                    )
                }
                val roleForPayload = role
                    ?: task.roleId?.let { rid -> liveWorkflow.roles.firstOrNull { it.id == rid } }
                val isAgentTask = executor is TaskExecutor.RoleAgent || task.roleId != null
                if (isAgentTask && roleForPayload != null) {
                    val redaction = liveWorkflow.definition.payloadRedactionPolicy
                    val allContextArtifacts = task.dependsOn
                        .flatMap { depId -> liveWorkflow.run.taskRuns[depId]?.artifacts.orEmpty() }
                    val sentArtifacts = allContextArtifacts.filter { it.kind !in redaction.excludedArtifactKinds }
                    LiveInspectorLine(stringResource(Res.string.inspector_payload_context), buildString {
                        append(stringResource(Res.string.inspector_role, roleForPayload.name))
                        append("\nObjective: ${if (redaction.redactObjective) "[redacted by policy]" else task.objective.take(120) + if (task.objective.length > 120) "…" else ""}")
                        if (task.acceptanceCriteria.isNotEmpty()) {
                            append(pluralStringResource(Res.plurals.inspector_acceptance_criteria_count, task.acceptanceCriteria.size, task.acceptanceCriteria.size))
                        }
                        if (!redaction.redactRoleInstructions && roleForPayload.instructions.isNotBlank()) {
                            append("\nRole instructions: ${roleForPayload.instructions.take(80)}${if (roleForPayload.instructions.length > 80) "…" else ""}")
                        } else if (redaction.redactRoleInstructions) {
                            append(stringResource(Res.string.inspector_role_instructions_redacted_by_policy))
                        }
                        if (allContextArtifacts.isNotEmpty()) {
                            append(stringResource(Res.string.inspector_context_artifacts_sent, sentArtifacts.size))
                            if (sentArtifacts.size < allContextArtifacts.size) {
                                append(stringResource(Res.string.inspector_excluded_by_redaction_policy, allContextArtifacts.size - sentArtifacts.size))
                            }
                        }
                    })
                    LiveInspectorLine(stringResource(Res.string.inspector_provider_target), taskRun.assignedProviderId?.value ?: task.executor?.let { ex ->
                        if (ex is TaskExecutor.RoleAgent) stringResource(Res.string.inspector_any_capable_provider) else "—"
                    } ?: stringResource(Res.string.inspector_any_capable_provider))
                }
            }
            CreatureTab.Mind -> {
                // Programmatic roles wear the neuron and never a kept creature: they only connect.
                role?.takeIf { it.executionSource == com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
                    ?.let { CreaturePicker(it.id) }
                // Inside the creature (docs/Swarm-OS.md): its config, changed here (docs/Keepers-Room.md,
                // stage 3). Its genes never change; changing its mind hatches a new config.
                role?.let { mind ->
                    CustomCompanyProviderScreen(
                        runtimeState = ApplicationRuntimeState.Live(liveWorkflow),
                        connectedProviderIds = connectedProviderIds,
                        onSaveRoleCollection = onSaveRoleCollection,
                        roleSurfaceFilePicker = roleSurfaceFilePicker,
                        focusRole = mind.id,
                    )
                } ?: LiveInspectorLine(stringResource(Res.string.inspector_executor), executor.displayName())
            }
        }
    }
    }
}

@Composable
private fun TaskExecutor.reference(): String = when (this) {
    is TaskExecutor.RoleAgent -> roleId.value
    is TaskExecutor.GitHubAction -> listOfNotNull(workflow, ref).joinToString(" @ ")
    is TaskExecutor.Script -> "${language.name} · " + when (runner) {
        is com.hereliesaz.geministrator.domain.ScriptRunner.LocalSandbox -> stringResource(Res.string.inspector_local_sandbox)
        is com.hereliesaz.geministrator.domain.ScriptRunner.GitHubActions -> stringResource(Res.string.inspector_github_actions)
    }
    is TaskExecutor.TestRunner -> command ?: stringResource(Res.string.inspector_default_runner)
    is TaskExecutor.Deployment -> environment
    is TaskExecutor.RepositoryOperation -> operation
    is TaskExecutor.HumanApproval -> label
    is TaskExecutor.ExternalService -> listOfNotNull(service, operation).joinToString(" · ")
    is TaskExecutor.NestedWorkflow -> workflowDefinitionId.value
    is TaskExecutor.Distributed -> label ?: (stringResource(Res.string.inspector_remote) + delegate.displayName())
}

@Composable
private fun LiveInspectorLine(label: String, value: String, live: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    ) {
        Text(label, style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(value, style = AzphaltType.body, color = azphaltOnSurface())
    }
}

/**
 * The creature's panel, in three (docs/Keepers-Room.md, stage 3): what it needs from you (once the
 * Inbox), its work and what it made (once Artifacts), and its mind (once the Swarm's role editor).
 */
enum class CreatureTab { Needs, Work, Mind }

/** Whether this creature is waiting on the keeper: a plan or task to approve, or an escalation. */
internal fun creatureNeedsYou(taskRun: TaskRun): Boolean =
    taskRun.status == TaskRunStatus.AwaitingApproval ||
        taskRun.status == TaskRunStatus.Escalated ||
        taskRun.awaitsNestedHumanApproval()

/** The tab a creature opens on: Needs when it is waiting on you, else its Work. */
internal fun openingTab(taskRun: TaskRun?): CreatureTab =
    if (taskRun != null && creatureNeedsYou(taskRun)) CreatureTab.Needs else CreatureTab.Work

@Composable
internal fun CreatureTab.label(): String = stringResource(
    when (this) {
        CreatureTab.Needs -> Res.string.creature_tab_needs
        CreatureTab.Work -> Res.string.creature_tab_work
        CreatureTab.Mind -> Res.string.creature_tab_mind
    },
)

@Composable
private fun CreatureTabs(tab: CreatureTab, onTabChange: (CreatureTab) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CreatureTab.entries.forEach { entry ->
            AzphaltPill(
                label = entry.label(),
                seed = "creature-tab-${entry.name}",
                selected = entry == tab,
                role = Role.Tab,
                onClick = { onTabChange(entry) },
            )
        }
    }
}
