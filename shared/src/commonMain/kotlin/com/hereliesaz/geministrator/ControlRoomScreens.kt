package com.hereliesaz.geministrator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.policy.PolicyEffect
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskRerunRequested
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.runs_event_executor
import com.hereliesaz.geministrator.resources.runs_event_system
import com.hereliesaz.geministrator.resources.runs_artifact_stored
import com.hereliesaz.geministrator.resources.common_approve
import com.hereliesaz.geministrator.resources.common_inbox
import com.hereliesaz.geministrator.resources.common_reject
import com.hereliesaz.geministrator.resources.common_retry
import com.hereliesaz.geministrator.resources.runs_active
import com.hereliesaz.geministrator.resources.runs_agent_assigned
import com.hereliesaz.geministrator.resources.runs_approval_required
import com.hereliesaz.geministrator.resources.runs_approve_to_begin_execution
import com.hereliesaz.geministrator.resources.runs_approved
import com.hereliesaz.geministrator.resources.runs_approved_by_delegation
import com.hereliesaz.geministrator.resources.runs_artifact_created
import com.hereliesaz.geministrator.resources.runs_attempt_of
import com.hereliesaz.geministrator.resources.runs_authority
import com.hereliesaz.geministrator.resources.runs_cancelled
import com.hereliesaz.geministrator.resources.runs_created
import com.hereliesaz.geministrator.resources.runs_done
import com.hereliesaz.geministrator.resources.runs_evidence
import com.hereliesaz.geministrator.resources.runs_executor_assigned
import com.hereliesaz.geministrator.resources.runs_expanded_oauth_scope_challenged
import com.hereliesaz.geministrator.resources.runs_failed
import com.hereliesaz.geministrator.resources.runs_failure_escalation
import com.hereliesaz.geministrator.resources.runs_failure_escalation_2
import com.hereliesaz.geministrator.resources.runs_foo_authentication
import com.hereliesaz.geministrator.resources.runs_human_decision_required
import com.hereliesaz.geministrator.resources.runs_human_decision_required_2
import com.hereliesaz.geministrator.resources.runs_infra_release
import com.hereliesaz.geministrator.resources.runs_inspector
import com.hereliesaz.geministrator.resources.runs_integration_approval_after_independent_review
import com.hereliesaz.geministrator.resources.runs_loading
import com.hereliesaz.geministrator.resources.runs_marketplace_security
import com.hereliesaz.geministrator.resources.runs_needs_you
import com.hereliesaz.geministrator.resources.runs_no_pending_decisions
import com.hereliesaz.geministrator.resources.runs_no_projects_yet
import com.hereliesaz.geministrator.resources.runs_no_runs
import com.hereliesaz.geministrator.resources.runs_plan_approval
import com.hereliesaz.geministrator.resources.runs_policy_allowed
import com.hereliesaz.geministrator.resources.runs_policy_denied
import com.hereliesaz.geministrator.resources.runs_policy_requires_approval
import com.hereliesaz.geministrator.resources.runs_reject_reassigns_to
import com.hereliesaz.geministrator.resources.runs_reject_stops_the_workflow
import com.hereliesaz.geministrator.resources.runs_rejected
import com.hereliesaz.geministrator.resources.runs_release_failed_after_3_attempts
import com.hereliesaz.geministrator.resources.runs_retry_scheduled_attempt
import com.hereliesaz.geministrator.resources.runs_run
import com.hereliesaz.geministrator.resources.runs_running
import com.hereliesaz.geministrator.resources.runs_security_risk
import com.hereliesaz.geministrator.resources.runs_stop
import com.hereliesaz.geministrator.resources.runs_task_cancelled
import com.hereliesaz.geministrator.resources.runs_external_cancel_failed
import com.hereliesaz.geministrator.resources.runs_task_rerun
import com.hereliesaz.geministrator.resources.runs_task_completed
import com.hereliesaz.geministrator.resources.runs_task_escalated
import com.hereliesaz.geministrator.resources.runs_task_failed
import com.hereliesaz.geministrator.resources.runs_task_ready
import com.hereliesaz.geministrator.resources.runs_task_started_attempt
import com.hereliesaz.geministrator.resources.runs_timeline
import com.hereliesaz.geministrator.resources.runs_upcoming
import com.hereliesaz.geministrator.resources.runs_usage_recorded
import com.hereliesaz.geministrator.resources.runs_verification_failed
import com.hereliesaz.geministrator.resources.runs_waiting
import com.hereliesaz.geministrator.resources.runs_workflow_cancelled
import com.hereliesaz.geministrator.resources.runs_workflow_completed
import com.hereliesaz.geministrator.resources.runs_workflow_created
import com.hereliesaz.geministrator.resources.runs_workflow_failed
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.swarm_add_role
import com.hereliesaz.geministrator.resources.swarm_title
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_stop_title
import com.hereliesaz.geministrator.resources.confirm_stop_body
import com.hereliesaz.geministrator.resources.confirm_stop_action
import com.hereliesaz.geministrator.resources.confirm_reject_plan_title
import com.hereliesaz.geministrator.resources.confirm_reject_plan_body
import com.hereliesaz.geministrator.resources.confirm_reject_plan_action
import com.hereliesaz.geministrator.resources.inbox_action_answer
import com.hereliesaz.geministrator.resources.inbox_budget_where
import com.hereliesaz.geministrator.resources.inbox_question_where
import com.hereliesaz.geministrator.resources.inspector_hint_approve_nested
import com.hereliesaz.geministrator.resources.inspector_raise_budget
import org.jetbrains.compose.resources.stringResource

/** The inspector with nothing live to show: says so instead of showing sample data. */
@Composable
internal fun EmptyTechnicalInspector(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(Azphalt.Ink).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AzphaltSurface(Azphalt.Ink) {
            Text(stringResource(Res.string.runs_inspector), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            Text(message, style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
}

internal fun workflowEventTaskId(event: WorkflowEvent): TaskDefinitionId? = when (event) {
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
    is ExternalRunCancelFailed -> event.taskDefinitionId
    is TaskRerunRequested -> event.taskDefinitionId
    is WorkflowCreated, is WorkflowCompleted, is WorkflowFailed, is WorkflowCancelled -> null
    is ProviderUsageRecorded -> null
    is PolicyDecisionRecorded -> event.taskDefinitionId
}

@Composable
internal fun workflowEventLabel(event: WorkflowEvent): String = when (event) {
    is WorkflowCreated -> stringResource(Res.string.runs_workflow_created)
    is TaskBecameReady -> stringResource(Res.string.runs_task_ready)
    is ExecutorAssigned -> stringResource(Res.string.runs_executor_assigned)
    is AgentAssigned -> stringResource(Res.string.runs_agent_assigned)
    is TaskStarted -> stringResource(Res.string.runs_task_started_attempt, event.attempt)
    is ApprovalRequired -> stringResource(Res.string.runs_approval_required)
    is ApprovalDecisionReceived -> when {
        !event.approved -> stringResource(Res.string.runs_rejected)
        event.byDelegation -> stringResource(Res.string.runs_approved_by_delegation)
        else -> stringResource(Res.string.runs_approved)
    }
    is ArtifactCreated -> stringResource(Res.string.runs_artifact_created, event.artifact.kind.name)
    is VerificationFailed -> stringResource(Res.string.runs_verification_failed)
    is RetryScheduled -> stringResource(Res.string.runs_retry_scheduled_attempt, event.nextAttempt)
    is TaskEscalated -> stringResource(Res.string.runs_task_escalated)
    is TaskCompleted -> stringResource(Res.string.runs_task_completed)
    is TaskFailed -> stringResource(Res.string.runs_task_failed)
    is HumanDecisionRequired -> stringResource(Res.string.runs_human_decision_required_2)
    is WorkflowCompleted -> stringResource(Res.string.runs_workflow_completed)
    is WorkflowFailed -> stringResource(Res.string.runs_workflow_failed)
    is WorkflowCancelled -> stringResource(Res.string.runs_workflow_cancelled)
    is TaskCancelled -> stringResource(Res.string.runs_task_cancelled)
    is ExternalRunCancelFailed -> stringResource(Res.string.runs_external_cancel_failed)
    is TaskRerunRequested -> stringResource(Res.string.runs_task_rerun)
    is ProviderUsageRecorded -> stringResource(Res.string.runs_usage_recorded)
    is PolicyDecisionRecorded -> when (event.effect) {
        PolicyEffect.Allow -> stringResource(Res.string.runs_policy_allowed)
        PolicyEffect.Deny -> stringResource(Res.string.runs_policy_denied)
        PolicyEffect.RequireApproval -> stringResource(Res.string.runs_policy_requires_approval)
    }
}

@Composable
internal fun workflowEventDetail(event: WorkflowEvent): String = when (event) {
    is WorkflowCreated -> event.objective?.take(80) ?: event.workflowRunId.value.takeLast(8)
    is ApprovalRequired -> event.reason.take(80)
    is ApprovalDecisionReceived -> event.decidedByRoleId?.value ?: stringResource(Res.string.runs_event_system)
    is ArtifactCreated -> event.artifact.id.value.takeLast(16)
    is VerificationFailed -> event.reason.take(80)
    is RetryScheduled -> event.reason.take(80)
    is TaskEscalated -> event.reason.take(80)
    is TaskFailed -> event.reason.take(80)
    is WorkflowFailed -> event.reason.take(80)
    is HumanDecisionRequired -> event.reason.take(80)
    is ExternalRunCancelFailed -> event.reason.take(80)
    is PolicyDecisionRecorded -> event.reason.take(80)
    is ExecutorAssigned -> event.executor::class.simpleName ?: stringResource(Res.string.runs_event_executor)
    is AgentAssigned -> event.roleId.value
    is TaskRerunRequested -> event.editedObjective?.take(80) ?: event.fromWorkflowRunId.value.takeLast(8)
    else -> event.workflowRunId.value.takeLast(8)
}
