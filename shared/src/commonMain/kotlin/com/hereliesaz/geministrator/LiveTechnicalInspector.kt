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
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.awaitsNestedHumanApproval
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
import com.hereliesaz.geministrator.resources.inspector_inspector
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
import org.jetbrains.compose.resources.getString
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
    val role = taskRun.assignedRoleId?.let { roleId -> liveWorkflow.roles.firstOrNull { it.id == roleId } }
    val executor = taskRun.executor ?: task.effectiveExecutor()
    val identity = role?.name ?: executor.displayName()
    Column(
        modifier = modifier
            .background(Azphalt.Ink)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(Res.string.inspector_inspector), style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(identity.uppercase(), style = AzphaltType.section, color = Azphalt.White)
        Text(task.objective, style = AzphaltType.body, color = Azphalt.White)
        LiveInspectorLine(stringResource(Res.string.inspector_state), taskRun.status.name)
        LiveInspectorLine(stringResource(Res.string.inspector_executor), executor.displayName())
        LiveInspectorLine(stringResource(Res.string.inspector_executor_ref), executor.reference())
        LiveInspectorLine(stringResource(Res.string.inspector_responsible_role), taskRun.assignedRoleId?.value ?: task.roleId?.value ?: "—")
        LiveInspectorLine(stringResource(Res.string.inspector_provider), taskRun.assignedProviderId?.value ?: "—")
        LiveInspectorLine(stringResource(Res.string.inspector_attempt), taskRun.attempt.toString())
        LiveInspectorLine(stringResource(Res.string.inspector_provider_run), taskRun.providerRunId?.value ?: "—")
        LiveInspectorLine(stringResource(Res.string.inspector_external_run), taskRun.externalRunId ?: "—")
        taskRun.progress?.let { progress ->
            LiveInspectorLine(stringResource(Res.string.inspector_progress), "${(progress * 100f).toInt()}%")
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
            LiveInspectorLine(label, it)
        }
        taskRun.blockingReason?.let {
            LiveInspectorLine(stringResource(Res.string.inspector_blocked), "${it.code} · ${it.message}")
        }
        LiveInspectorLine(stringResource(Res.string.inspector_artifacts), taskRun.artifacts.size.toString())
        taskRun.artifacts.forEachIndexed { index, artifact ->
            val reference = artifact.uri
                ?: artifact.textContent?.takeIf(String::isNotBlank)?.let { stringResource(Res.string.inspector_inline_evidence) }
                ?: stringResource(Res.string.inspector_stored_evidence)
            LiveInspectorLine(
                stringResource(Res.string.inspector_artifact, index + 1, artifact.kind.name),
                "${artifact.label} · $reference",
            )
        }
        if (taskRun.status == TaskRunStatus.AwaitingApproval) {
            val providerPlan = taskRun.assignedProviderId != null
            AzphaltPill(
                label = if (providerPlan) stringResource(Res.string.inspector_approve_plan) else stringResource(Res.string.inspector_approve_task),
                seed = "approve-$selectedTaskId",
                endCap = stringResource(Res.string.inspector_proceed),
                onClick = { onApproveTask(selectedTaskId) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (providerPlan) {
                AzphaltPill(
                    label = stringResource(Res.string.inspector_reject_plan),
                    seed = "reject-plan-$selectedTaskId",
                    endCap = stringResource(Res.string.common_reject),
                    onClick = { onRejectPlan(selectedTaskId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (taskRun.awaitsNestedHumanApproval()) {
            AzphaltPill(
                label = stringResource(Res.string.inspector_approve_nested_gate),
                seed = "approve-nested-$selectedTaskId",
                endCap = stringResource(Res.string.inspector_proceed),
                onClick = { onApproveTask(selectedTaskId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (taskRun.status == TaskRunStatus.Escalated) {
            AzphaltPill(
                label = stringResource(Res.string.inspector_retry_task),
                seed = "escalation-approve-$selectedTaskId",
                endCap = stringResource(Res.string.common_approve),
                onClick = { onResolveEscalation(selectedTaskId, true) },
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                label = stringResource(Res.string.inspector_stop_workflow),
                seed = "escalation-reject-$selectedTaskId",
                endCap = stringResource(Res.string.common_reject),
                onClick = { onResolveEscalation(selectedTaskId, false) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                label = if (messageSending) stringResource(Res.string.inspector_sending) else stringResource(Res.string.inspector_send_message),
                seed = "message-$selectedTaskId",
                endCap = stringResource(Res.string.inspector_send),
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
            messageStatus?.let { LiveInspectorLine(stringResource(Res.string.inspector_message), it) }
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
private fun LiveInspectorLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(value, style = AzphaltType.body, color = Azphalt.White)
    }
}
