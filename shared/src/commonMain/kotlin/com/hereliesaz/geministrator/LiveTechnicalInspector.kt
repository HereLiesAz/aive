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
import com.hereliesaz.geministrator.workflow.REMOTE_COMPUTE_UNAVAILABLE_CODE
import com.hereliesaz.geministrator.workflow.ROLE_BUDGET_SPENT
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import com.hereliesaz.geministrator.workflow.contextInputs
import com.hereliesaz.geministrator.workflow.objectiveAsInput
import com.hereliesaz.geministrator.workflow.taintInputs
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.job_not_saved
import com.hereliesaz.geministrator.resources.job_saved
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.ROLE_COLLECTION_MARKER_ID
import com.hereliesaz.geministrator.domain.TaskRun
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextOverflow
import com.hereliesaz.geministrator.workflow.SessionActivityFeeds
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import com.hereliesaz.geministrator.workflow.attemptsOf
import com.hereliesaz.geministrator.log.formatLogTime
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.inspector_payload_role_instructions
import com.hereliesaz.geministrator.resources.inspector_payload_redacted
import com.hereliesaz.geministrator.resources.inspector_payload_objective
import com.hereliesaz.geministrator.resources.inspector_activity
import com.hereliesaz.geministrator.resources.inspector_shared_creature
import com.hereliesaz.geministrator.resources.inspector_shared_creature_here
import com.hereliesaz.geministrator.resources.inspector_activity_attempt
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
import com.hereliesaz.geministrator.resources.inspector_rewind
import com.hereliesaz.geministrator.resources.inspector_rewind_note
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
    onRewindTask: suspend (String) -> String? = { "Rewinding is unavailable" },
    /** Closes the panel onto the hive's Timeline with this creature selected; null where there is none. */
    onOpenTrace: (() -> Unit)? = null,
    /** Docked beside the hive on wide screens (HiveWorkspace.kt): its own head, card and close. Null in a sheet. */
    onClose: (() -> Unit)? = null,
    /** Paired devices exist, so its job may be sent off this device (the Compute place is open). */
    computeAvailable: Boolean = false,
    /** Opens another task's creature from this one's panel: a shared creature does several tasks (CreatureRoam.kt). */
    onSelectTask: (String) -> Unit = {},
    /** Opens Connections, where a provider or an integration is linked or swapped. Null leaves those lines plain. */
    onOpenConnections: (() -> Unit)? = null,
    /** Opens the Compute place, where paired devices are managed. Null leaves those lines plain. */
    onOpenCompute: (() -> Unit)? = null,
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
    // Its job (mockup H4) is saved to the hive's workflow, the way Build saves it: the running run
    // stays as it was, so a change takes effect from its next run. Without the library (previews)
    // the job shows but does not change.
    val library = LocalWorkflowLibraryHost.current
    var savedJob by remember(liveWorkflow.definition.id) { mutableStateOf<WorkflowDefinition?>(null) }
    var jobStatus by remember(selectedTaskId) { mutableStateOf<String?>(null) }
    LaunchedEffect(library, liveWorkflow.definition.id) {
        val stored = library?.let { host ->
            runCatching { host.entries() }.getOrNull()?.firstOrNull { it.definition.id == liveWorkflow.definition.id }?.definition
        }
        if (stored != null) savedJob = stored
    }
    val jobDefinition = savedJob?.takeIf { saved -> saved.tasks.any { it.id == taskId } } ?: liveWorkflow.definition
    val jobTask = jobDefinition.tasks.first { it.id == taskId }
    val editJob: ((TerrariumDependencyEditResult) -> Unit)? = library?.let { host ->
        { edit ->
            when (edit) {
                is TerrariumDependencyEditResult.Applied -> scope.launch {
                    runCatching { host.saveAuthored(edit.definition) }
                        .onSuccess { saved ->
                            savedJob = saved
                            jobStatus = getString(Res.string.job_saved)
                        }
                        .onFailure { failure -> jobStatus = failure.message ?: getString(Res.string.job_not_saved) }
                }
                TerrariumDependencyEditResult.NoChange -> Unit
                is TerrariumDependencyEditResult.Rejected -> jobStatus = edit.reason
            }
        }
    }
    // Everything in the inspector sits on Ink, so its text, hints and fields take the exact
    // opposite of Ink from this surface rather than a colour of their own.
    AzphaltThemedSurface(Azphalt.Ink) {
    Column(
        modifier = modifier
            .background(Azphalt.Ink)
            .verticalScroll(rememberScrollState())
            .padding(if (onClose != null) 20.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val place = planPlace(liveWorkflow.definition.tasks, taskId)
        val providerName = taskRun.assignedProviderId?.value?.let(::providerDisplayName)
        if (onClose != null) {
            // Docked: which creature of how many and its job, then the creature itself on its card.
            DockedCreatureHead(place?.position, place?.of ?: 0, task.name.ifBlank { identity }, onClose)
            DockedCreatureCard(
                roleLabel = identity,
                hueSeed = "${role?.id?.value ?: task.roleId?.value ?: executor.displayName()}:${task.id.value}",
                status = taskRun.status,
                provider = providerName,
                rigOverride = if (executor.isAgentWork()) null else CreatureRigMapping.PROGRAMMATIC_RIG,
                onClick = if (tab != CreatureTab.Work) ({ onTabChange(CreatureTab.Work) }) else null,
            )
        } else {
        // The creature large at the top, its name and the three tabs beside it (mockup H4).
        CreaturePanelTop(
            identity = identity,
            status = taskRun.status,
            tab = tab,
            onTabChange = onTabChange,
            pendingProposals = pendingRoleInstructionProposals(role?.id),
        )
        // One line under its name: its state, its provider, and its place in the plan ("3 of 5").
        Text(
            creatureHeaderLine(
                state = taskRun.status.label(),
                provider = providerName,
                place = place?.let { stringResource(Res.string.inspector_plan_place, it.position, it.of) },
            ),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
            // A link: its state and provider are on Work, where the provider can be changed.
            textDecoration = if (tab != CreatureTab.Work) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .then(
                    if (tab != CreatureTab.Work) {
                        Modifier.clickable(role = Role.Button) { onTabChange(CreatureTab.Work) }
                    } else {
                        Modifier
                    },
                ),
        )
        }
        // Work holds its objective in its job, below.
        if (tab != CreatureTab.Work) Text(task.objective, style = AzphaltType.body, color = azphaltOnSurface())
        if (onClose != null) {
            CreatureUnderlineTabs(tab, onTabChange, pendingProposals = pendingRoleInstructionProposals(role?.id))
        }
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
                    ?.let { CreatureNeedsPanel(it, titled = false, onOpenMind = { onTabChange(CreatureTab.Mind) }) }
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
                        val external = liveWorkflow.definition.taintInputs(liveWorkflow.run, task)
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
                // A creature that does several tasks of its role says so, and leads to the others.
                SharedCreatureTasks(liveWorkflow, selectedTaskId, onSelectTask)
                // Its job: objective, acceptance criteria, approval, retries, where it runs.
                HiveJobEditor(
                    definition = jobDefinition,
                    task = jobTask,
                    onEdit = editJob,
                    titled = false,
                    remoteAvailable = computeAvailable,
                )
                // Which provider answers for it: Auto, or pinned. Saved on its role, like the Mind's.
                role?.let { agentRole ->
                    CreatureProviderRow(
                        role = agentRole,
                        task = jobTask,
                        roles = liveWorkflow.roles.filter { it.enabled && it.id.value != ROLE_COLLECTION_MARKER_ID },
                        connectedProviderIds = connectedProviderIds,
                        onSaveRoleCollection = onSaveRoleCollection,
                        onOpenConnections = onOpenConnections,
                    )
                }
                jobStatus?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
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
                // An executor that lives off this device or behind an integration links to where that is set up.
                val executorLink = when (executor) {
                    is TaskExecutor.Distributed -> onOpenCompute
                    is TaskExecutor.GitHubAction, is TaskExecutor.ExternalService -> onOpenConnections
                    else -> null
                }
                LiveInspectorLine(stringResource(Res.string.inspector_executor), executor.displayName(), onClick = executorLink)
                LiveInspectorLine(stringResource(Res.string.inspector_executor_ref), executor.reference(), onClick = executorLink)
                (taskRun.assignedRoleId?.value ?: task.roleId?.value)
                    ?.takeIf { it != executor.reference() }
                    ?.let { LiveInspectorLine(stringResource(Res.string.inspector_responsible_role), it) }
                LiveInspectorLine(
                    stringResource(Res.string.inspector_provider),
                    taskRun.assignedProviderId?.value ?: "—",
                    onClick = onOpenConnections,
                )
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
                    // Each block that has a place to be cleared links to it.
                    val blockedLink: (() -> Unit)? = when {
                        it.code == REMOTE_COMPUTE_UNAVAILABLE_CODE -> onOpenCompute
                        it.code == WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE -> onOpenConnections
                        it.code == ROLE_BUDGET_SPENT -> ({ onTabChange(CreatureTab.Mind) })
                        else -> null
                    }
                    LiveInspectorLine(stringResource(Res.string.inspector_blocked), text, live = true, onClick = blockedLink)
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
                    // A remote agent's or a model-fed schedule's objective is labelled as what it is.
                    val objectiveInput = liveWorkflow.run.objectiveAsInput()
                    append(objectiveInput?.provenanceLabel() ?: stringResource(Res.string.provenance_keeper_written))
                    inputs.forEach { input ->
                        append("\n${input.label} (${input.kind.name}) · ")
                        append(input.provenanceLabel())
                    }
                })
                // Its Connections (docs/Swarm-OS.md): who feeds it, and whom it feeds and whether they took it.
                val connections = creatureConnections(taskId, liveWorkflow.definition, liveWorkflow.run, liveWorkflow.roles)
                // Each neighbour is a link: one tap opens that creature.
                if (connections.fedBy.isNotEmpty()) {
                    Text(stringResource(Res.string.inspector_fed_by), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                    connections.fedBy.forEach { link ->
                        AzphaltPill(label = link.name, seed = "fed-by-${link.taskId.value}", onClick = { onSelectTask(link.taskId.value) })
                    }
                }
                if (connections.feeds.isNotEmpty()) {
                    val took = stringResource(Res.string.inspector_took_its_output)
                    val notYet = stringResource(Res.string.inspector_not_taken_yet)
                    Text(stringResource(Res.string.inspector_feeds), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                    connections.feeds.forEach { link ->
                        AzphaltPill(
                            label = "${link.name} · ${if (link.tookOutput) took else notYet}",
                            seed = "feeds-${link.taskId.value}",
                            onClick = { onSelectTask(link.taskId.value) },
                        )
                    }
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
                // Also while the run is live: it is stopped and continued from here, finished work kept.
                if (liveWorkflow.run.status.isFinished() || taskRun.status !in setOf(TaskRunStatus.Created, TaskRunStatus.Blocked, TaskRunStatus.Ready)) {
                    val runId = liveWorkflow.run.id
                    val hasDependents = liveWorkflow.definition.tasks.any { other ->
                        taskId in other.dependsOn || other.condition.conditionTargetOrNull() == taskId
                    }
                    val output = taskRun.artifacts
                        .mapNotNull { it.textContent }
                        .takeIf { it.isNotEmpty() && hasDependents && taskRun.status == TaskRunStatus.Completed }
                        ?.takeIf { task.executor !is TaskExecutor.HumanApproval }
                        ?.joinToString("\n\n")
                    // Rewind in place: only this creature and what follows restart; the rest keeps running.
                    if (!liveWorkflow.run.status.isFinished()) {
                        var rewindMessage by remember(taskId) { mutableStateOf<String?>(null) }
                        val rewindScope = rememberCoroutineScope()
                        // A second tap while the first is running would rewind it twice.
                        var rewinding by remember(taskId) { mutableStateOf(false) }
                        AzphaltPill(
                            label = stringResource(Res.string.inspector_rewind),
                            seed = "inspector-rewind",
                            enabled = !rewinding,
                            onClick = {
                                if (!rewinding) {
                                    rewinding = true
                                    rewindScope.launch {
                                        try {
                                            rewindMessage = onRewindTask(taskId.value)
                                        } finally {
                                            rewinding = false
                                        }
                                    }
                                }
                            },
                        )
                        Text(stringResource(Res.string.inspector_rewind_note), style = AzphaltType.body, color = azphaltOnSurface())
                        rewindMessage?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
                    }
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
                        append(stringResource(Res.string.inspector_payload_objective, if (redaction.redactObjective) stringResource(Res.string.inspector_payload_redacted) else task.objective.take(120) + if (task.objective.length > 120) "…" else ""))
                        if (task.acceptanceCriteria.isNotEmpty()) {
                            append(pluralStringResource(Res.plurals.inspector_acceptance_criteria_count, task.acceptanceCriteria.size, task.acceptanceCriteria.size))
                        }
                        if (!redaction.redactRoleInstructions && roleForPayload.instructions.isNotBlank()) {
                            append(stringResource(Res.string.inspector_payload_role_instructions, roleForPayload.instructions.take(80) + if (roleForPayload.instructions.length > 80) "…" else ""))
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
                // Docked, its mind opens as a plain list of what it is; the editors follow.
                if (onClose != null) MindDefinitionList(identity, providerName, role)
                // Programmatic roles wear the neuron and never a kept creature: they only connect.
                role?.takeIf { it.executionSource == com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent }
                    ?.let { CreaturePicker(it.id, agentName = { id -> liveWorkflow.roles.firstOrNull { r -> r.id.value == id }?.name }) }
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

/**
 * The other tasks of a creature that does several (CreatureRoam.kt): each a pill that opens that task.
 * Nothing is drawn for a creature that does one.
 */
@Composable
private fun SharedCreatureTasks(liveWorkflow: LiveWorkflowPresentation, taskId: String, onSelectTask: (String) -> Unit) {
    val group = remember(liveWorkflow.definition, liveWorkflow.run) {
        projectRoaming(liveWorkflow.definition, liveWorkflow.run).groupOf(taskId)
    } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.inspector_shared_creature), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        group.members.filter { it != taskId }.forEach { other ->
            val name = liveWorkflow.definition.tasks.firstOrNull { it.id.value == other }?.name?.ifBlank { null } ?: other
            AzphaltPill(
                label = name,
                seed = "shared-creature-$taskId-$other",
                endCap = if (other == group.rider) stringResource(Res.string.inspector_shared_creature_here) else null,
                onClick = { onSelectTask(other) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Everything a creature did, from the in-memory [SessionActivityFeeds]: its thinking and tool/progress
 * steps, dated, oldest first, across every attempt (a retry starts a new one, marked). It is the
 * Log tab's Agents source for this creature alone. Steering messages are not shown here: they
 * have their own field below.
 */
@Composable
private fun CreatureActivity(taskRun: TaskRun, activity: SessionActivityFeeds) {
    val feeds by activity.feeds.collectAsState()
    val attempts = remember(feeds, taskRun.id) { feeds.attemptsOf(taskRun.id) }
    if (attempts.isEmpty()) return
    // Not a live region: reasoning streams in pieces, and announcing each would drown the panel.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.inspector_activity), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        attempts.forEachIndexed { index, attempt ->
            if (attempts.size > 1) {
                Text(
                    stringResource(Res.string.inspector_activity_attempt, index + 1),
                    style = AzphaltType.eyebrow,
                    color = azphaltOnSurface(),
                )
            }
            attempt.lines.forEach { line ->
                key(attempt.key.providerRunId, line.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                when (line.kind) {
                                    SessionActivityKind.Thinking -> Res.string.inspector_activity_thinking
                                    SessionActivityKind.Progress -> Res.string.inspector_activity_step
                                },
                            ) + if (line.atEpochMillis > 0) "  ·  ${formatLogTime(line.atEpochMillis)}" else "",
                            style = AzphaltType.eyebrow,
                            color = azphaltOnSurface(),
                        )
                        SelectionContainer {
                            Text(line.text, style = AzphaltType.body, color = azphaltOnSurface())
                        }
                    }
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
private fun LiveInspectorLine(label: String, value: String, live: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = (if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget).clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Text(label, style = AzphaltType.eyebrow, color = azphaltOnSurface())
        // A link reads as one: underlined.
        Text(
            value,
            style = AzphaltType.body,
            color = azphaltOnSurface(),
            textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
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

/**
 * The top of a creature's panel (mockup H4): the creature itself, large, the way it looks in the
 * hive, then its name over its tabs. The creature is decoration: its name is beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreaturePanelTop(
    identity: String,
    status: TaskRunStatus,
    tab: CreatureTab,
    onTabChange: (CreatureTab) -> Unit,
    pendingProposals: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeMascotSurface(
                roleLabel = identity,
                hueSeed = identity,
                state = status.toTerrariumState(),
                motionPhase = { 0f },
                // The rig leaves a margin for the field's name pills; here the whole creature is drawn larger.
                modifier = Modifier.size(CREATURE_PANEL_SIZE).graphicsLayer {
                    scaleX = CREATURE_PANEL_SCALE
                    scaleY = CREATURE_PANEL_SCALE
                }.clearAndSetSemantics {},
            )
            Text(
                identity,
                style = AzphaltType.section.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = azphaltOnSurface(),
                modifier = Modifier.weight(1f).padding(bottom = 8.dp).semantics { heading() },
            )
        }
        CreatureTabs(tab, onTabChange, pendingProposals)
    }
}

private const val CREATURE_PANEL_SCALE = 1.35f
private val CREATURE_PANEL_SIZE = 96.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatureTabs(tab: CreatureTab, onTabChange: (CreatureTab) -> Unit, pendingProposals: Int = 0) {
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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

/** Whether this work is done by an agent (a creature) rather than a programmatic step (the neuron). */
private fun TaskExecutor.isAgentWork(): Boolean = when (this) {
    is TaskExecutor.RoleAgent -> true
    is TaskExecutor.Distributed -> delegate.isAgentWork()
    else -> false
}
