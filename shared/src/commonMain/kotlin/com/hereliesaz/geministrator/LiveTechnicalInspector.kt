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
import kotlinx.coroutines.launch

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
        TechnicalInspector(selectedTaskId = selectedTaskId, modifier = modifier)
        return
    }

    val taskId = TaskDefinitionId(selectedTaskId)
    val task = liveWorkflow.definition.tasks.firstOrNull { it.id == taskId }
    val taskRun = liveWorkflow.run.taskRuns[taskId]
    if (task == null || taskRun == null) {
        TechnicalInspector(selectedTaskId = selectedTaskId, modifier = modifier)
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
        Text("INSPECTOR", style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(identity.uppercase(), style = AzphaltType.section, color = Azphalt.White)
        Text(task.objective, style = AzphaltType.body, color = Azphalt.White)
        LiveInspectorLine("STATE", taskRun.status.name)
        LiveInspectorLine("EXECUTOR", executor.displayName())
        LiveInspectorLine("EXECUTOR REF", executor.reference())
        LiveInspectorLine("RESPONSIBLE ROLE", taskRun.assignedRoleId?.value ?: task.roleId?.value ?: "—")
        LiveInspectorLine("PROVIDER", taskRun.assignedProviderId?.value ?: "—")
        LiveInspectorLine("ATTEMPT", taskRun.attempt.toString())
        LiveInspectorLine("PROVIDER RUN", taskRun.providerRunId?.value ?: "—")
        LiveInspectorLine("EXTERNAL RUN", taskRun.externalRunId ?: "—")
        taskRun.progress?.let { progress ->
            LiveInspectorLine("PROGRESS", "${(progress * 100f).toInt()}%")
        }
        taskRun.progressMessage?.takeIf(String::isNotBlank)?.let {
            val label = if (
                taskRun.status == TaskRunStatus.AwaitingApproval &&
                taskRun.assignedProviderId != null
            ) {
                "PLAN"
            } else {
                "NOW"
            }
            LiveInspectorLine(label, it)
        }
        taskRun.blockingReason?.let {
            LiveInspectorLine("BLOCKED", "${it.code} · ${it.message}")
        }
        LiveInspectorLine("ARTIFACTS", taskRun.artifacts.size.toString())
        taskRun.artifacts.forEachIndexed { index, artifact ->
            val reference = artifact.uri
                ?: artifact.textContent?.takeIf(String::isNotBlank)?.let { "inline evidence" }
                ?: "stored evidence"
            LiveInspectorLine(
                "ARTIFACT ${index + 1} · ${artifact.kind.name}",
                "${artifact.label} · $reference",
            )
        }
        if (taskRun.status == TaskRunStatus.AwaitingApproval) {
            val providerPlan = taskRun.assignedProviderId != null
            AzphaltPill(
                label = if (providerPlan) "Approve plan" else "Approve task",
                seed = "approve-$selectedTaskId",
                endCap = "Proceed",
                onClick = { onApproveTask(selectedTaskId) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (providerPlan) {
                AzphaltPill(
                    label = "Reject plan",
                    seed = "reject-plan-$selectedTaskId",
                    endCap = "Reject",
                    onClick = { onRejectPlan(selectedTaskId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (taskRun.status == TaskRunStatus.Escalated) {
            AzphaltPill(
                label = "Retry task",
                seed = "escalation-approve-$selectedTaskId",
                endCap = "Approve",
                onClick = { onResolveEscalation(selectedTaskId, true) },
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                label = "Stop workflow",
                seed = "escalation-reject-$selectedTaskId",
                endCap = "Reject",
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
                label = { Text("Message agent") },
                minLines = 2,
                enabled = !messageSending,
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                label = if (messageSending) "Sending…" else "Send message",
                seed = "message-$selectedTaskId",
                endCap = "Send",
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
                                messageStatus = "Sent"
                            } else {
                                messageStatus = failure
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            messageStatus?.let { LiveInspectorLine("MESSAGE", it) }
        }
        val roleForPayload = role
            ?: task.roleId?.let { rid -> liveWorkflow.roles.firstOrNull { it.id == rid } }
        val isAgentTask = executor is TaskExecutor.RoleAgent || task.roleId != null
        if (isAgentTask && roleForPayload != null) {
            val redaction = liveWorkflow.definition.payloadRedactionPolicy
            val allContextArtifacts = task.dependsOn
                .flatMap { depId -> liveWorkflow.run.taskRuns[depId]?.artifacts.orEmpty() }
            val sentArtifacts = allContextArtifacts.filter { it.kind !in redaction.excludedArtifactKinds }
            LiveInspectorLine("PAYLOAD CONTEXT", buildString {
                append("Role: ${roleForPayload.name}")
                append("\nObjective: ${if (redaction.redactObjective) "[redacted by policy]" else task.objective.take(120) + if (task.objective.length > 120) "…" else ""}")
                if (task.acceptanceCriteria.isNotEmpty()) {
                    append("\nAcceptance criteria: ${task.acceptanceCriteria.size} item${if (task.acceptanceCriteria.size != 1) "s" else ""}")
                }
                if (!redaction.redactRoleInstructions && roleForPayload.instructions.isNotBlank()) {
                    append("\nRole instructions: ${roleForPayload.instructions.take(80)}${if (roleForPayload.instructions.length > 80) "…" else ""}")
                } else if (redaction.redactRoleInstructions) {
                    append("\nRole instructions: [redacted by policy]")
                }
                if (allContextArtifacts.isNotEmpty()) {
                    append("\nContext artifacts: ${sentArtifacts.size} sent")
                    if (sentArtifacts.size < allContextArtifacts.size) {
                        append(", ${allContextArtifacts.size - sentArtifacts.size} excluded by redaction policy")
                    }
                }
            })
            LiveInspectorLine("PROVIDER TARGET", taskRun.assignedProviderId?.value ?: task.executor?.let { ex ->
                if (ex is TaskExecutor.RoleAgent) "any capable provider" else "—"
            } ?: "any capable provider")
        }
    }
}

private fun TaskExecutor.reference(): String = when (this) {
    is TaskExecutor.RoleAgent -> roleId.value
    is TaskExecutor.GitHubAction -> listOfNotNull(workflow, ref).joinToString(" @ ")
    is TaskExecutor.Script -> "${language.name} · " + when (runner) {
        is com.hereliesaz.geministrator.domain.ScriptRunner.LocalSandbox -> "local sandbox"
        is com.hereliesaz.geministrator.domain.ScriptRunner.GitHubActions -> "GitHub Actions"
    }
    is TaskExecutor.TestRunner -> command ?: "default runner"
    is TaskExecutor.Deployment -> environment
    is TaskExecutor.RepositoryOperation -> operation
    is TaskExecutor.HumanApproval -> label
    is TaskExecutor.ExternalService -> listOfNotNull(service, operation).joinToString(" · ")
    is TaskExecutor.NestedWorkflow -> workflowDefinitionId.value
    is TaskExecutor.Distributed -> label ?: ("Remote · " + delegate.displayName())
}

@Composable
private fun LiveInspectorLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(value, style = AzphaltType.body, color = Azphalt.White)
    }
}
