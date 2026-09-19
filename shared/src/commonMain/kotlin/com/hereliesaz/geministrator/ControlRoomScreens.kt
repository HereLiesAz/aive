package com.hereliesaz.geministrator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed

@Composable
internal fun TechnicalInspector(selectedTaskId: String, modifier: Modifier = Modifier) {
    val node = ActiveWorkflow.firstOrNull { it.id == selectedTaskId } ?: ActiveWorkflow.first()
    Column(
        modifier = modifier.background(Azphalt.Ink).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("INSPECTOR", style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(node.position.uppercase(), style = AzphaltType.section, color = Azphalt.White)
        Text(node.assignment, style = AzphaltType.body, color = Azphalt.White)
        InspectorLine("STATE", node.state.label)
        InspectorLine("STAFFING", node.staffing ?: "Unstaffed")
        InspectorLine("ATTEMPT", if (node.id == "implementation") "1 of 2" else "1")
        InspectorLine("PROVIDER RUN", if (node.staffing != null) "sessions/9b2e" else "—")
        InspectorLine("PROMPT REUSE", if (node.staffing != null) "Session scoped" else "—")
        InspectorLine("CACHE HIT", "Not reported")
        AzphaltPill("Message agent", "message", onClick = {}, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InspectorLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = AzphaltType.eyebrow, color = Azphalt.Yellow)
        Text(value, style = AzphaltType.body, color = Azphalt.White)
    }
}

@Composable
internal fun CompanyScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    onSaveRole: (RoleDefinition) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    var showRoleForm by remember { mutableStateOf(false) }
    var roleIdDraft by remember { mutableStateOf("") }
    var roleNameDraft by remember { mutableStateOf("") }
    var roleDescDraft by remember { mutableStateOf("") }
    var roleInstructionsDraft by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SWARM", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
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
                            )
                        )
                        showRoleForm = false
                        roleIdDraft = ""
                        roleNameDraft = ""
                        roleDescDraft = ""
                        roleInstructionsDraft = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (liveWorkflow != null) {
            val definition = liveWorkflow.definition
            SectionLabel("Workflow Policies")
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
                        append(" · Per-provider limits: ${definition.concurrencyPolicy.perProviderLimits.entries.joinToString { "${it.key.value}=${it.value}" }}")
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
            val activeRoleIds = liveWorkflow.run.taskRuns.values
                .filter { it.status in setOf(TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.AwaitingApproval, TaskRunStatus.Verifying) }
                .mapNotNull { it.assignedRoleId }
                .toSet()
            val byDept = liveWorkflow.roles.groupBy { it.department() }
            listOf("Executive", "Product", "Engineering", "Assurance", "Delivery", "Custom").forEach { dept ->
                val roles = byDept[dept] ?: return@forEach
                SectionLabel(dept)
                roles.forEach { role ->
                    AzphaltRecord(
                        seed = role.id.value,
                        eyebrow = dept,
                        title = role.name,
                        endCap = if (role.id in activeRoleIds) "Working" else "Available",
                        body = role.description,
                    )
                }
            }
        } else {
            listOf(
                "Executive" to listOf("Orchestrator"),
                "Product" to listOf("Product Manager", "Researcher", "UX Designer"),
                "Engineering" to listOf("Architect", "EPA Representative", "Implementation Engineer"),
                "Assurance" to listOf("Crash Test Dummy", "QA Engineer", "Adversarial Reviewer", "Code Reviewer", "Recovery Engineer"),
                "Delivery" to listOf("Release Engineer"),
            ).forEach { (department, roles) ->
                SectionLabel(department)
                roles.forEach { role ->
                    AzphaltRecord(
                        seed = role,
                        eyebrow = department,
                        title = role,
                        endCap = if (role == "Implementation Engineer") "Working" else "Available",
                        body = when (role) {
                            "Crash Test Dummy" -> "Author tests · cannot verify or approve"
                            "EPA Representative" -> "Select environment · cannot implement or verify"
                            "Implementation Engineer" -> "Implement · cannot certify own work"
                            else -> "Swarm role"
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun InboxScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    onApproveTask: (String) -> Unit = {},
    onRejectPlan: (String) -> Unit = {},
    onResolveEscalation: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("INBOX", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        if (liveWorkflow != null) {
            val pending = liveWorkflow.run.taskRuns.values.filter {
                it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated
            }
            if (pending.isEmpty()) {
                Text("No pending decisions.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
            } else {
                pending.forEach { taskRun ->
                    val task = liveWorkflow.definition.tasks.firstOrNull { it.id == taskRun.taskDefinitionId }
                    val taskId = taskRun.taskDefinitionId.value
                    val isPlanApproval = taskRun.status == TaskRunStatus.AwaitingApproval
                    val scope = buildString {
                        if (isPlanApproval) {
                            append("Approve to begin execution")
                            task?.approvalPolicy?.let {
                                if (it is ApprovalPolicy.RoleApproval) append(" · authority: ${it.authority.name}")
                            }
                        } else {
                            val maxAttempts = task?.retryPolicy?.maxAttempts ?: 2
                            append("Attempt ${taskRun.attempt} of $maxAttempts")
                            task?.escalationPolicy?.let { ep ->
                                when (ep) {
                                    is EscalationPolicy.FailWorkflow -> append(" · Reject stops the workflow")
                                    is EscalationPolicy.RequireHumanDecision -> append(" · Human decision required")
                                    is EscalationPolicy.Reassign -> append(" · Reject reassigns to ${ep.roleId.value}")
                                }
                            }
                        }
                    }
                    AzphaltRecord(
                        seed = taskId,
                        eyebrow = if (isPlanApproval) "Plan Approval" else "Failure Escalation",
                        title = task?.name ?: taskId,
                        body = buildString {
                            val primary = if (isPlanApproval) {
                                taskRun.progressMessage?.takeIf(String::isNotBlank) ?: task?.objective ?: ""
                            } else {
                                taskRun.blockingReason?.let { "${it.code} · ${it.message}" } ?: "Task failed"
                            }
                            append(primary)
                            if (scope.isNotBlank()) {
                                append("\n")
                                append(scope)
                            }
                        },
                        endCap = "Needs you",
                        well = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (taskRun.artifacts.isNotEmpty()) {
                                    Text("EVIDENCE", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                                    taskRun.artifacts.forEach { artifact ->
                                        val ref = artifact.uri
                                            ?: artifact.textContent?.take(80)?.let { if (artifact.textContent.length > 80) "$it…" else it }
                                            ?: "stored"
                                        AzphaltNote(
                                            seed = "inbox-artifact-${artifact.id.value}",
                                            label = "${artifact.kind.name} · ${artifact.label}",
                                            value = ref,
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isPlanApproval) {
                                        AzphaltPill("Approve", "$taskId-approve", onClick = { onApproveTask(taskId) })
                                        if (taskRun.assignedProviderId != null) {
                                            AzphaltPill("Reject", "$taskId-reject", onClick = { onRejectPlan(taskId) })
                                        }
                                    } else {
                                        AzphaltPill("Retry", "$taskId-retry", onClick = { onResolveEscalation(taskId, true) })
                                        AzphaltPill("Stop", "$taskId-stop", onClick = { onResolveEscalation(taskId, false) })
                                    }
                                }
                            }
                        },
                    )
                }
            }
        } else {
            DecisionRecord("infra-release", "Failure escalation", "Infra · Release", "Release failed after 3 attempts", true)
            DecisionRecord("market-security", "Security risk", "Marketplace · Security", "Expanded OAuth scope challenged", true)
            DecisionRecord("foo-integration", "Upcoming", "Foo · Authentication", "Integration approval after independent review", false)
        }
    }
}

@Composable
private fun DecisionRecord(seed: String, kind: String, title: String, body: String, actionable: Boolean) {
    AzphaltRecord(seed, kind, title, body, if (actionable) "Needs you" else "Later", well = if (actionable) {
        {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill("Approve", "$seed-approve", onClick = {})
                AzphaltPill("Reject", "$seed-reject", onClick = {})
            }
        }
    } else null)
}

@Composable
internal fun RunsScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    onLoadRunHistory: suspend () -> List<Pair<Project, List<WorkflowRun>>> = { emptyList() },
    onSwitchRun: (String) -> Unit = {},
    onLoadRunTimeline: suspend () -> List<WorkflowEvent> = { emptyList() },
    modifier: Modifier = Modifier,
) {
    var history by remember { mutableStateOf<List<Pair<Project, List<WorkflowRun>>>?>(null) }
    var timeline by remember { mutableStateOf<List<WorkflowEvent>?>(null) }
    LaunchedEffect(runtimeState) {
        history = onLoadRunHistory()
        timeline = onLoadRunTimeline()
    }
    val liveRunId = (runtimeState as? ApplicationRuntimeState.Live)?.presentation?.run?.id?.value
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RUNS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        val entries = history
        if (entries == null) {
            Text("Loading…", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        } else if (entries.isEmpty()) {
            Text("No projects yet.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        } else {
            entries.forEach { (project, runs) ->
                SectionLabel(project.name)
                if (runs.isEmpty()) {
                    Text("No runs.", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                } else {
                    runs.forEach { run ->
                        val isActive = run.id.value == liveRunId
                        AzphaltRecord(
                            seed = run.id.value,
                            eyebrow = run.status.name,
                            title = run.objective.take(60).let { if (run.objective.length > 60) "$it…" else it },
                            body = "Run · ${run.id.value.takeLast(8)}",
                            endCap = if (isActive) "Active" else runStatusEndCap(run.status),
                            selected = isActive,
                            onClick = { if (!isActive) onSwitchRun(run.id.value) },
                        )
                    }
                }
            }
        }
        val events = timeline
        if (events != null && events.isNotEmpty()) {
            SectionLabel("Timeline")
            events.sortedByDescending { it.occurredAtEpochMillis }.forEach { event ->
                AzphaltRecord(
                    seed = "event-${event.occurredAtEpochMillis}-${event::class.simpleName}",
                    eyebrow = workflowEventTaskId(event)?.value?.takeLast(12) ?: "workflow",
                    title = workflowEventLabel(event),
                    body = workflowEventDetail(event),
                    endCap = null,
                )
            }
        }
    }
}

private fun workflowEventTaskId(event: WorkflowEvent): TaskDefinitionId? = when (event) {
    is TaskBecameReady -> event.taskDefinitionId
    is ExecutorAssigned -> event.taskDefinitionId
    is AgentAssigned -> event.taskDefinitionId
    is TaskStarted -> event.taskDefinitionId
    is ApprovalRequired -> event.taskDefinitionId
    is ApprovalDecisionReceived -> event.taskDefinitionId
    is ArtifactCreated -> event.taskDefinitionId
    is VerificationFailed -> event.taskDefinitionId
    is RetryScheduled -> event.taskDefinitionId
    is TaskEscalated -> event.taskDefinitionId
    is TaskCompleted -> event.taskDefinitionId
    is TaskFailed -> event.taskDefinitionId
    is HumanDecisionRequired -> event.taskDefinitionId
    is TaskCancelled -> event.taskDefinitionId
    is WorkflowCreated, is WorkflowCompleted, is WorkflowFailed, is WorkflowCancelled -> null
    is ProviderUsageRecorded -> null
}

private fun workflowEventLabel(event: WorkflowEvent): String = when (event) {
    is WorkflowCreated -> "Workflow created"
    is TaskBecameReady -> "Task ready"
    is ExecutorAssigned -> "Executor assigned"
    is AgentAssigned -> "Agent assigned"
    is TaskStarted -> "Task started · attempt ${event.attempt}"
    is ApprovalRequired -> "Approval required"
    is ApprovalDecisionReceived -> if (event.approved) "Approved" else "Rejected"
    is ArtifactCreated -> "Artifact created · ${event.artifact.kind.name}"
    is VerificationFailed -> "Verification failed"
    is RetryScheduled -> "Retry scheduled · attempt ${event.nextAttempt}"
    is TaskEscalated -> "Task escalated"
    is TaskCompleted -> "Task completed"
    is TaskFailed -> "Task failed"
    is HumanDecisionRequired -> "Human decision required"
    is WorkflowCompleted -> "Workflow completed"
    is WorkflowFailed -> "Workflow failed"
    is WorkflowCancelled -> "Workflow cancelled"
    is TaskCancelled -> "Task cancelled"
    is ProviderUsageRecorded -> "Usage recorded"
}

private fun workflowEventDetail(event: WorkflowEvent): String = when (event) {
    is WorkflowCreated -> event.objective?.take(80) ?: event.workflowRunId.value.takeLast(8)
    is ApprovalRequired -> event.reason.take(80)
    is ApprovalDecisionReceived -> event.decidedByRoleId?.value ?: "system"
    is ArtifactCreated -> event.artifact.id.value.takeLast(16)
    is VerificationFailed -> event.reason.take(80)
    is RetryScheduled -> event.reason.take(80)
    is TaskEscalated -> event.reason.take(80)
    is TaskFailed -> event.reason.take(80)
    is WorkflowFailed -> event.reason.take(80)
    is HumanDecisionRequired -> event.reason.take(80)
    is ExecutorAssigned -> event.executor::class.simpleName ?: "executor"
    is AgentAssigned -> event.roleId.value
    else -> event.workflowRunId.value.takeLast(8)
}

private fun runStatusEndCap(status: WorkflowRunStatus): String = when (status) {
    WorkflowRunStatus.Completed -> "Done"
    WorkflowRunStatus.Failed -> "Failed"
    WorkflowRunStatus.Cancelled -> "Cancelled"
    WorkflowRunStatus.Running -> "Running"
    WorkflowRunStatus.AwaitingHuman -> "Waiting"
    WorkflowRunStatus.Created -> "Created"
}

@Composable
internal fun WorkflowTemplateScreen(
    runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading,
    modifier: Modifier = Modifier,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val entrance = remember { AzphaltEntrance.roll() }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WORKFLOWS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        if (liveWorkflow != null) {
            val definition = liveWorkflow.definition
            val concurrency = definition.concurrencyPolicy
            SectionLabel("Active — ${definition.name}")
            AzphaltRecord(
                seed = "workflow-policies",
                eyebrow = "Policies",
                title = "Workflow configuration",
                body = buildString {
                    append("Integration: ${definition.integrationPolicy.name}")
                    append(" · Concurrency: ${concurrency.maxConcurrentTasks} max")
                    if (concurrency.perProviderLimits.isNotEmpty()) {
                        append(" (")
                        append(concurrency.perProviderLimits.entries.joinToString(", ") { (pid, n) -> "${pid.value}: $n" })
                        append(")")
                    }
                    append(" · Tests: ${definition.testDesignPolicy.name}")
                    append(" · Cache: ${definition.promptReusePolicy.name}")
                },
                endCap = "${definition.tasks.size} tasks",
            )
            SectionLabel("Task graph")
            definition.tasks.forEachIndexed { index, task ->
                val taskRun = liveWorkflow.run.taskRuns[task.id]
                val executor = task.executor
                val executorLabel = when {
                    executor != null -> executor.displayName()
                    task.roleId != null -> task.roleId.value
                    else -> "Unassigned"
                }
                val policyDetail = buildString {
                    when (val ap = task.approvalPolicy) {
                        is ApprovalPolicy.None -> Unit
                        is ApprovalPolicy.RoleApproval -> append("Approval: ${ap.authority.name}")
                        is ApprovalPolicy.HumanApproval -> append("Approval: Human")
                    }
                    if (task.retryPolicy.maxAttempts > 1) {
                        if (isNotEmpty()) append(" · ")
                        append("Retry: ${task.retryPolicy.maxAttempts}x")
                    }
                    when (val ep = task.escalationPolicy) {
                        is EscalationPolicy.FailWorkflow -> Unit
                        is EscalationPolicy.RequireHumanDecision -> {
                            if (isNotEmpty()) append(" · ")
                            append("Escalation: Human")
                        }
                        is EscalationPolicy.Reassign -> {
                            if (isNotEmpty()) append(" · ")
                            append("Escalation: Reassign → ${ep.roleId.value}")
                        }
                    }
                    if (task.dependsOn.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("After: ${task.dependsOn.joinToString(", ") { it.value }}")
                    }
                }.ifBlank { task.objective.take(60) }
                val isSelected = selectedTaskId == task.id.value
                AzphaltRecord(
                    seed = "task-dag-${task.id.value}",
                    eyebrow = executorLabel,
                    title = task.name,
                    body = policyDetail,
                    endCap = taskRun?.status?.name ?: "Pending",
                    selected = isSelected,
                    onClick = { selectedTaskId = if (isSelected) null else task.id.value },
                    modifier = Modifier.azphaltEntrance(entrance, index, definition.tasks.size),
                    well = if (isSelected) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val executorDetail = when (val ex = task.effectiveExecutor()) {
                                    is TaskExecutor.RoleAgent -> "Role: ${ex.roleId.value}"
                                    is TaskExecutor.GitHubAction -> "Workflow: ${ex.workflow}${ex.ref?.let { " @ $it" } ?: ""}"
                                    is TaskExecutor.TestRunner -> "Command: ${ex.command ?: "default"}"
                                    is TaskExecutor.Deployment -> "Environment: ${ex.environment}"
                                    is TaskExecutor.RepositoryOperation -> "Operation: ${ex.operation}"
                                    is TaskExecutor.HumanApproval -> "Label: ${ex.label}"
                                    is TaskExecutor.ExternalService -> "${ex.service}${ex.operation?.let { " · $it" } ?: ""}"
                                    is TaskExecutor.NestedWorkflow -> "Workflow: ${ex.workflowDefinitionId.value}"
                                    is TaskExecutor.Distributed -> "Remote: " + ex.delegate.displayName()
                                }
                                AzphaltNote("executor-type-${task.id.value}", "Executor type", executor?.displayName() ?: "Role agent")
                                AzphaltNote("executor-detail-${task.id.value}", "Executor detail", executorDetail)
                                when (val pc = task.providerConstraints) {
                                    is ProviderConstraints.None -> Unit
                                    is ProviderConstraints.RequireCapabilities -> AzphaltNote("constraints-${task.id.value}", "Provider constraints", pc.capabilities.joinToString(", ") { it.name })
                                    is ProviderConstraints.RequireProvider -> AzphaltNote("constraints-${task.id.value}", "Pinned provider", pc.providerId.value)
                                }
                                if (task.acceptanceCriteria.isNotEmpty()) {
                                    AzphaltNote("criteria-${task.id.value}", "Acceptance criteria", task.acceptanceCriteria.joinToString("\n") { "· ${it.description}" })
                                }
                            }
                        }
                    } else null,
                )
            }
        } else {
            listOf(
                Triple("Standard Feature", "Product → Architecture → Tests → Implementation → QA → Review → Release", "Default"),
                Triple("Bug Fix", "Diagnosis → Contract → Fix → Regression → QA → Review", "Template"),
                Triple("Research Spike", "Product → Research → Architecture → Decision", "Template"),
            ).forEachIndexed { index, item ->
                AzphaltRecord(
                    "workflow-$index",
                    "Workflow",
                    item.first,
                    item.second,
                    item.third,
                    modifier = Modifier.azphaltEntrance(entrance, index, 3),
                )
            }
        }
    }
}

private data class ArtifactEntry(
    val id: String,
    val name: String,
    val detail: String,
    val children: List<ArtifactEntry> = emptyList(),
)

private val ArtifactTree = listOf(
    ArtifactEntry("spec", "Specification", "Approved product and architecture inputs", listOf(
        ArtifactEntry("requirements", "Requirements", "Product Manager · approved"),
        ArtifactEntry("architecture", "Architecture", "Architect · approved"),
    )),
    ArtifactEntry("pretests", "Pre-code verification", "Crash Test Dummy", listOf(
        ArtifactEntry("acceptance", "Acceptance test plan", "4 scenarios"),
        ArtifactEntry("contract", "Contract tests", "7 contracts"),
        ArtifactEntry("failure", "Failure scenarios", "5 cases"),
    )),
    ArtifactEntry("environment", "Environment", "EPA Representative", listOf(
        ArtifactEntry("runtime", "Runtime", "JDK 17 · ephemeral"),
        ArtifactEntry("network", "Network", "Restricted"),
    )),
    ArtifactEntry("implementation-artifacts", "Implementation", "Jules · active", listOf(
        ArtifactEntry("changes", "Code change", "Pending completion"),
        ArtifactEntry("command", "Command output", "3 recent commands"),
    )),
)

@Composable
internal fun ArtifactFileManagerScreen(runtimeState: ApplicationRuntimeState = ApplicationRuntimeState.Loading, modifier: Modifier = Modifier) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val displayTree: List<ArtifactEntry> = if (liveWorkflow != null) {
        liveWorkflow.definition.tasks.mapNotNull { task ->
            val taskRun = liveWorkflow.run.taskRuns[task.id] ?: return@mapNotNull null
            if (taskRun.artifacts.isEmpty()) return@mapNotNull null
            ArtifactEntry(
                id = task.id.value,
                name = task.name,
                detail = taskRun.status.name,
                children = taskRun.artifacts.map { artifact ->
                    ArtifactEntry(
                        id = artifact.id.value,
                        name = artifact.label,
                        detail = artifact.kind.name,
                    )
                },
            )
        }.takeIf { it.isNotEmpty() } ?: ArtifactTree
    } else ArtifactTree

    var openId by remember(displayTree) { mutableStateOf(displayTree.firstOrNull()?.id) }
    var previewId by remember { mutableStateOf<String?>(null) }
    val rootEntrance = remember { AzphaltEntrance.roll() }
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ARTIFACTS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill("Browse", "artifact-browse", selected = true, onClick = {})
            AzphaltPill("Search", "artifact-search", onClick = {})
            AzphaltPill("Storage", "artifact-storage", onClick = {})
        }
        if (liveWorkflow != null && displayTree === ArtifactTree) {
            Text("No artifacts produced yet.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        }
        displayTree.forEachIndexed { rootIndex, entry ->
            val open = openId == entry.id
            val siblingFraction by animateFloatAsState(
                targetValue = if (openId == null || open) 1f else 0.42f,
                label = "artifact-sibling-yield-${entry.id}",
            )
            AzphaltRecord(
                seed = entry.id,
                eyebrow = if (entry.children.isEmpty()) "Artifact" else "Collection",
                title = entry.name,
                body = entry.detail,
                endCap = if (open) "Open" else entry.children.size.takeIf { it > 0 }?.toString(),
                selected = open,
                onClick = {
                    previewId = null
                    openId = if (open) null else entry.id
                },
                modifier = Modifier
                    .fillMaxWidth(siblingFraction)
                    .azphaltEntrance(rootEntrance, rootIndex, displayTree.size),
                well = if (open && entry.children.isNotEmpty()) {
                    {
                        val childEntrance = remember(entry.id) { AzphaltEntrance.childBand() }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            entry.children.forEachIndexed { childIndex, child ->
                                val childSelected = previewId == child.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .azphaltEntrance(childEntrance, childIndex, entry.children.size)
                                        .azphaltSelectedTransform(childSelected)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (childSelected) Azphalt.Yellow else Azphalt.hue(child.id))
                                        .clickable { previewId = if (childSelected) null else child.id }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(child.name.uppercase(), style = AzphaltType.capsule, color = if (childSelected) Azphalt.Ink else Azphalt.hue(child.id).contrastingText)
                                    Text(child.detail.uppercase(), style = AzphaltType.endCap, color = if (childSelected) Azphalt.Ink else Azphalt.hue(child.id).contrastingText)
                                }
                                AzphaltChildBand(visible = childSelected) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F0F0F)).padding(12.dp),
                                    ) {
                                        Text("${child.name}\n${child.detail}\nsource: ${entry.name}\nstatus: available", style = AzphaltType.body, color = Azphalt.White)
                                    }
                                }
                            }
                        }
                    }
                } else null,
            )
        }
    }
}

@Composable
internal fun SettingsScreen(
    connectedProviderIds: Set<String> = emptySet(),
    onCheckProviderHealth: suspend () -> Map<String, String> = { emptyMap() },
    onClearWorkflowData: () -> Unit = {},
    onExportJson: suspend () -> String? = { null },
    onImportJson: (String) -> Unit = {},
    onExportDiagnosticBundle: suspend () -> String? = { null },
    onReconfigureProvider: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var diagnosticBundle by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var healthResults by remember { mutableStateOf<Map<String, String>?>(null) }
    var healthChecking by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SETTINGS", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        SectionLabel("Providers")
        if (connectedProviderIds.isNotEmpty()) {
            connectedProviderIds.forEach { providerId ->
                val health = healthResults?.get(providerId)
                ProviderRecord(
                    name = providerId,
                    state = if (health != null) health.substringBefore(" ·") else "Connected",
                    auth = health ?: "Credential present",
                    onReconfigure = { onReconfigureProvider(providerId) },
                )
            }
            if (healthChecking) {
                LaunchedEffect(Unit) {
                    healthResults = onCheckProviderHealth()
                    healthChecking = false
                }
            }
            AzphaltPill(if (healthResults == null) "Check provider health" else "Refresh health", "health-check", onClick = { healthChecking = true; healthResults = null }, modifier = Modifier.fillMaxWidth())
        } else {
            ProviderRecord("Jules", "Not configured", "No credential")
            ProviderRecord("Codex", "Not configured", "No credential")
            ProviderRecord("Claude", "Not configured", "No credential")
        }
        SectionLabel("Data")
        var exportTriggered by remember { mutableStateOf(false) }
        if (exportTriggered) {
            LaunchedEffect(Unit) {
                exportedJson = onExportJson()
                exportTriggered = false
            }
        }
        if (exportedJson == null) {
            AzphaltPill("Export workflow data to JSON", "export-trigger", onClick = { exportTriggered = true }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(
                value = exportedJson!!,
                onValueChange = {},
                readOnly = true,
                label = { Text("Exported JSON (${exportedJson!!.length} chars)") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onShareText != null) {
                    val json = exportedJson!!
                    AzphaltPill("Share", "export-share", onClick = { onShareText(json) })
                }
                AzphaltPill("Dismiss", "export-dismiss", onClick = { exportedJson = null })
            }
        }
        if (!importMode) {
            AzphaltPill("Import data from JSON", "import-mode-enter", onClick = { importMode = true }, modifier = Modifier.fillMaxWidth())
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
        SectionLabel("Diagnostics")
        var diagnosticTriggered by remember { mutableStateOf(false) }
        if (diagnosticTriggered) {
            LaunchedEffect(Unit) {
                diagnosticBundle = onExportDiagnosticBundle()
                diagnosticTriggered = false
            }
        }
        if (diagnosticBundle == null) {
            AzphaltPill("Export diagnostic bundle", "diagnostic-trigger", onClick = { diagnosticTriggered = true }, modifier = Modifier.fillMaxWidth())
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
        SectionLabel("Privacy")
        if (!clearConfirm) {
            AzphaltPill("Delete all workflow data", "clear-data-enter", onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth())
        } else {
            AzphaltRecord(
                seed = "clear-confirm",
                eyebrow = "Destructive",
                title = "Delete all workflow data?",
                body = "Permanently removes all projects, runs, events, and artifacts from this device. Export first if you want a backup.",
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
private fun ProviderRecord(name: String, state: String, auth: String, onReconfigure: (() -> Unit)? = null) {
    var reconfigureConfirm by remember { mutableStateOf(false) }
    AzphaltRecord(
        seed = "provider-$name",
        eyebrow = "Provider",
        title = name,
        body = auth,
        endCap = state,
        well = if (onReconfigure != null) {
            {
                if (!reconfigureConfirm) {
                    AzphaltPill("Reconfigure credential", "reconfigure-$name", onClick = { reconfigureConfirm = true })
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This will clear the stored credential and return to setup.", style = AzphaltType.body, color = Azphalt.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill("Clear and reconfigure", "reconfigure-$name-confirm", onClick = { onReconfigure(); reconfigureConfirm = false })
                            AzphaltPill("Cancel", "reconfigure-$name-cancel", onClick = { reconfigureConfirm = false })
                        }
                    }
                }
            }
        } else null,
    )
}

@Composable
private fun SectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

private fun RoleDefinition.department(): String = when (id.value) {
    "orchestrator" -> "Executive"
    "product-manager", "researcher", "ux-designer" -> "Product"
    "architect", "epa-representative", "implementation-engineer" -> "Engineering"
    "crash-test-dummy", "qa-engineer", "adversarial-reviewer", "code-reviewer", "recovery-engineer" -> "Assurance"
    "release-engineer" -> "Delivery"
    else -> "Custom"
}
