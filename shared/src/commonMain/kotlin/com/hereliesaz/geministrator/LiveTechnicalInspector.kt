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
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CHECKPOINT_COMMIT_METADATA_KEY
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.workflow.TaskRerunRequest
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.awaitsNestedHumanApproval
import com.hereliesaz.geministrator.workflow.CONTEXT_TAINT_BLOCKING_CODE
import com.hereliesaz.geministrator.workflow.contextInputs
import com.hereliesaz.geministrator.workflow.isHeldAtContextTaintGate
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.madeFromUntrustedInput
import com.hereliesaz.geministrator.domain.provenance
import com.hereliesaz.geministrator.resources.inspector_external_inputs
import com.hereliesaz.geministrator.resources.inspector_hint_external_inputs
import com.hereliesaz.geministrator.resources.inspector_input_objective
import com.hereliesaz.geministrator.resources.inspector_inputs
import com.hereliesaz.geministrator.resources.provenance_agent_artifact
import com.hereliesaz.geministrator.resources.provenance_external_content
import com.hereliesaz.geministrator.resources.provenance_keeper_written
import com.hereliesaz.geministrator.resources.provenance_made_from_external
import com.hereliesaz.geministrator.resources.provenance_memory_recall
import com.hereliesaz.geministrator.resources.provenance_repository_content
import com.hereliesaz.geministrator.resources.provenance_unknown
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.roleTokensSpent
import com.hereliesaz.geministrator.resources.inspector_budget_spent
import com.hereliesaz.geministrator.resources.inspector_raise_budget
import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.TaskRun
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextOverflow
import com.hereliesaz.geministrator.workflow.SessionActivityFeeds
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import com.hereliesaz.geministrator.workflow.activityWindow
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.inspector_activity
import com.hereliesaz.geministrator.resources.inspector_activity_earlier
import com.hereliesaz.geministrator.resources.inspector_activity_show_fewer
import com.hereliesaz.geministrator.resources.inspector_activity_step
import com.hereliesaz.geministrator.resources.inspector_activity_thinking
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
import com.hereliesaz.geministrator.resources.inspector_checkpoint
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
import com.hereliesaz.geministrator.resources.inspector_plan_place
import com.hereliesaz.geministrator.resources.inspector_open_trace
import com.hereliesaz.geministrator.resources.inspector_fed_by
import com.hereliesaz.geministrator.resources.inspector_feeds
import com.hereliesaz.geministrator.resources.inspector_not_taken_yet
import com.hereliesaz.geministrator.resources.inspector_took_its_output
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
    /** Answers the creature's question to the keeper: task id, question key, answer; null on success. */
    onAnswerKeeperQuestion: suspend (String, String, String) -> String? = { _, _, _ -> "Messaging is unavailable" },
    modifier: Modifier = Modifier,
    tab: CreatureTab = CreatureTab.Needs,
    onTabChange: (CreatureTab) -> Unit = {},
    onSaveRoleCollection: (List<RoleDefinition>) -> Unit = {},
    connectedProviderIds: Set<String> = emptySet(),
    roleSurfaceFilePicker: RoleSurfaceFilePicker? = null,
    activity: SessionActivityFeeds = SessionActivityFeeds.Shared,
    /** Runs a finished run again from this creature; returns why it could not, or null. */
    onRerunFromTask: suspend (WorkflowRunId, TaskRerunRequest) -> String? = { _, _ -> "Running again is unavailable" },
    /** Closes the panel onto the hive's Timeline with this creature selected; null where there is none. */
    onOpenTrace: (() -> Unit)? = null,
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
        // One line under its name: its state, its provider, and its place in the plan ("3 of 5").
        val place = planPlace(liveWorkflow.definition.tasks, taskId)
        Text(
            creatureHeaderLine(
                state = taskRun.status.label(),
                provider = taskRun.assignedProviderId?.value?.let(::providerDisplayName),
                place = place?.let { stringResource(Res.string.inspector_plan_place, it.position, it.of) },
            ),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(task.objective, style = AzphaltType.body, color = azphaltOnSurface())
        CreatureTabs(tab, onTabChange, pendingProposals = pendingRoleInstructionProposals(role?.id))
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
                // A question its agent asked you (docs/Agent-UI.md): a wait like any other.
                taskRun.keeperQuestion?.takeIf { taskRun.awaitsKeeperAnswer() }?.let { question ->
                    AgentQuestionPanel(
                        taskId = selectedTaskId,
                        question = question,
                        onAnswer = { questionKey, answer -> onAnswerKeeperQuestion(selectedTaskId, questionKey, answer) },
                    )
                }
                if (taskRun.status == TaskRunStatus.AwaitingApproval) {
                    val providerPlan = taskRun.assignedProviderId != null
                    if (taskRun.isHeldAtContextTaintGate()) {
                        // The gate's reason: which of its inputs came from outside the keeper's control.
                        val external = liveWorkflow.definition.contextInputs(liveWorkflow.run, task)
                            .filter(ArtifactRef::isUntrustedContext)
                        LiveInspectorLine(
                            stringResource(Res.string.inspector_external_inputs),
                            stringResource(
                                Res.string.inspector_hint_external_inputs,
                                external.joinToString { "${it.label} (${it.kind.name})" },
                            ),
                            live = true,
                        )
                    }
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
                if (taskRun.isHeldForRoleBudget()) {
                    // The token budget circuit breaker: its role spent its budget in this run.
                    val spent = taskRun.assignedRoleId?.let { liveWorkflow.run.roleTokensSpent(it) } ?: 0L
                    Text(
                        stringResource(Res.string.inspector_budget_spent, spent.toString()),
                        style = AzphaltType.body,
                        color = azphaltOnSurface(),
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.inspector_raise_budget),
                        seed = "raise-budget-$selectedTaskId",
                        onClick = { onTabChange(CreatureTab.Mind) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!creatureNeedsYou(taskRun) && creatureNeeds(taskId, liveWorkflow.definition, liveWorkflow.run, liveWorkflow.roles) == null) {
                    Text(stringResource(Res.string.creature_needs_nothing), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            CreatureTab.Work -> {
                // What it is doing, live: its own thinking and steps, apart from what you tell it.
                CreatureActivity(taskRun, activity)
                // Its whole trace: the hive's Timeline, this creature selected on it.
                onOpenTrace?.let { openTrace ->
                    AzphaltPill(
                        label = stringResource(Res.string.inspector_open_trace),
                        seed = "open-trace-$selectedTaskId",
                        onClick = openTrace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                    val text = if (it.code == CONTEXT_TAINT_BLOCKING_CODE) it.message else "${it.code} · ${it.message}"
                    LiveInspectorLine(stringResource(Res.string.inspector_blocked), text, live = true)
                }
                // Its Git checkpoint: the commit its own changes became (Local Git, Desktop).
                checkpointSummary(taskRun.artifacts)?.let {
                    LiveInspectorLine(stringResource(Res.string.inspector_checkpoint), it)
                }
                // What it reads, each with where it came from (docs/THREAT_MODEL.md, "Context provenance").
                val inputs = liveWorkflow.definition.contextInputs(liveWorkflow.run, task)
                LiveInspectorLine(stringResource(Res.string.inspector_inputs), buildString {
                    append(stringResource(Res.string.inspector_input_objective))
                    append(" · ")
                    append(stringResource(Res.string.provenance_keeper_written))
                    inputs.forEach { input ->
                        append("\n${input.label} (${input.kind.name}) · ")
                        append(input.provenanceLabel())
                    }
                })
                // Its Connections (docs/Swarm-OS.md): who feeds it, and whom it feeds and whether they took it.
                val connections = creatureConnections(taskId, liveWorkflow.definition, liveWorkflow.run, liveWorkflow.roles)
                if (connections.fedBy.isNotEmpty()) {
                    LiveInspectorLine(
                        stringResource(Res.string.inspector_fed_by),
                        connections.fedBy.joinToString("\n") { it.name },
                    )
                }
                if (connections.feeds.isNotEmpty()) {
                    val took = stringResource(Res.string.inspector_took_its_output)
                    val notYet = stringResource(Res.string.inspector_not_taken_yet)
                    LiveInspectorLine(
                        stringResource(Res.string.inspector_feeds),
                        connections.feeds.joinToString("\n") { "${it.name} · ${if (it.tookOutput) took else notYet}" },
                    )
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
                // Time travel (docs/Keepers-Room.md): a finished run runs again from this creature.
                if (liveWorkflow.run.status.isFinished()) {
                    val runId = liveWorkflow.run.id
                    val hasDependents = liveWorkflow.definition.tasks.any { other ->
                        taskId in other.dependsOn || other.condition.conditionTargetOrNull() == taskId
                    }
                    val output = taskRun.artifacts
                        .mapNotNull { it.textContent }
                        .takeIf { it.isNotEmpty() && hasDependents && taskRun.status == TaskRunStatus.Completed }
                        ?.takeIf { task.executor !is TaskExecutor.HumanApproval }
                        ?.joinToString("\n\n")
                    RerunFromTaskPanel(
                        runId = runId,
                        taskId = taskId,
                        objective = task.objective,
                        output = output,
                        onRerun = { request -> onRerunFromTask(runId, request) },
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
                    // Proposed edits of its instructions, each applied only when the keeper approves.
                    RoleInstructionProposalPanel(
                        roleId = mind.id,
                        roles = liveWorkflow.roles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID },
                        onSaveRoleCollection = onSaveRoleCollection,
                    )
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

/** Lines of a creature's activity shown before the older ones collapse. */
private const val ACTIVITY_VISIBLE_LINES = 6

/**
 * A creature's live activity (its thinking and tool/progress steps, newest last) for its current
 * provider session, from the in-memory [SessionActivityFeeds]. Older lines collapse behind one pill.
 * Steering messages are not shown here: they have their own field below.
 */
@Composable
private fun CreatureActivity(taskRun: TaskRun, activity: SessionActivityFeeds) {
    val providerRunId = taskRun.providerRunId ?: return
    val feeds by activity.feeds.collectAsState()
    val lines = feeds[SessionActivityKey(taskRun.id, providerRunId)].orEmpty()
    if (lines.isEmpty()) return
    var expanded by remember(taskRun.id, providerRunId) { mutableStateOf(false) }
    val window = lines.activityWindow(ACTIVITY_VISIBLE_LINES, expanded)
    // Not a live region: reasoning streams in pieces, and announcing each would drown the panel.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.inspector_activity), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        if (window.collapsed > 0) {
            AzphaltPill(
                label = pluralStringResource(Res.plurals.inspector_activity_earlier, window.collapsed, window.collapsed),
                seed = "activity-earlier-${taskRun.id.value}",
                onClick = { expanded = true },
            )
        } else if (expanded && lines.size > ACTIVITY_VISIBLE_LINES) {
            AzphaltPill(
                label = stringResource(Res.string.inspector_activity_show_fewer),
                seed = "activity-fewer-${taskRun.id.value}",
                onClick = { expanded = false },
            )
        }
        window.shown.forEach { line ->
            key(line.id) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            when (line.kind) {
                                SessionActivityKind.Thinking -> Res.string.inspector_activity_thinking
                                SessionActivityKind.Progress -> Res.string.inspector_activity_step
                            },
                        ),
                        style = AzphaltType.eyebrow,
                        color = azphaltOnSurface(),
                    )
                    Text(
                        line.text,
                        style = AzphaltType.body,
                        color = azphaltOnSurface(),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The commit a task run checkpointed its changes as, shortened, with the branch holding it: from its
 * latest artifact carrying [CHECKPOINT_COMMIT_METADATA_KEY]. Null when the task made no checkpoint.
 */
internal fun checkpointSummary(artifacts: List<ArtifactRef>): String? {
    val artifact = artifacts.lastOrNull { !it.metadata[CHECKPOINT_COMMIT_METADATA_KEY].isNullOrBlank() } ?: return null
    val commit = artifact.metadata.getValue(CHECKPOINT_COMMIT_METADATA_KEY).trim().take(12)
    val branch = artifact.metadata["branch"]?.trim()?.takeIf(String::isNotEmpty)
    return if (branch == null) commit else "$commit · $branch"
}

@Composable
private fun ArtifactRef.provenanceLabel(): String {
    val source = stringResource(
        when (provenance) {
            ContextProvenance.KeeperWritten -> Res.string.provenance_keeper_written
            ContextProvenance.AgentArtifact -> Res.string.provenance_agent_artifact
            ContextProvenance.RepositoryContent -> Res.string.provenance_repository_content
            ContextProvenance.ExternalContent -> Res.string.provenance_external_content
            ContextProvenance.MemoryRecall -> Res.string.provenance_memory_recall
            ContextProvenance.Unknown -> Res.string.provenance_unknown
        },
    )
    return if (madeFromUntrustedInput) stringResource(Res.string.provenance_made_from_external, source) else source
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

/** Whether this creature is waiting on the keeper: a plan or task to approve, an escalation, or a spent budget. */
internal fun creatureNeedsYou(taskRun: TaskRun): Boolean =
    taskRun.status == TaskRunStatus.AwaitingApproval ||
        taskRun.status == TaskRunStatus.Escalated ||
        taskRun.awaitsNestedHumanApproval() ||
        taskRun.isHeldForRoleBudget() ||
        taskRun.awaitsKeeperAnswer()

/**
 * After "Open its trace" sets a creature's panel aside, tapping that creature again reopens its
 * panel instead of deselecting it.
 */
internal fun reopensSetAsidePanel(tappedTaskId: String, setAsideTaskId: String?): Boolean =
    tappedTaskId == setAsideTaskId

/** A set-aside panel is forgotten once another creature, or none, is selected. */
internal fun setAsideAfterSelection(selectedTaskId: String?, setAsideTaskId: String?): String? =
    setAsideTaskId?.takeIf { it == selectedTaskId }

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
private fun CreatureTabs(tab: CreatureTab, onTabChange: (CreatureTab) -> Unit, pendingProposals: Int = 0) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CreatureTab.entries.forEach { entry ->
            AzphaltPill(
                label = entry.label(),
                seed = "creature-tab-${entry.name}",
                selected = entry == tab,
                // Mind counts the proposed instruction edits waiting on the keeper.
                endCap = if (entry == CreatureTab.Mind && pendingProposals > 0) pendingProposals.toString() else null,
                role = Role.Tab,
                onClick = { onTabChange(entry) },
            )
        }
    }
}

private fun TaskCondition.conditionTargetOrNull(): TaskDefinitionId? = when (this) {
    is TaskCondition.Always -> null
    is TaskCondition.OnAnyOutcome -> ofTask
    is TaskCondition.OnFailure -> ofTask
}
