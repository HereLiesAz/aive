package com.hereliesaz.geministrator.log

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
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
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import com.hereliesaz.geministrator.workflow.SessionActivityLine

/*
 * The Log tab's four sources as dated lines (docs/Keepers-Room.md). Pure: the screen only lists them.
 * Log text is the record's own words, not translated; each source scrubs keys before it keeps or shows a line.
 */

/**
 * Every event of the run in time order, with the whole of its reason: the Events view cuts
 * reasons to 80 characters, the log does not. Lines are scrubbed of keys on the way out too.
 */
fun runEventLines(
    events: List<WorkflowEvent>,
    taskName: (TaskDefinitionId) -> String,
    scrub: (String) -> String = com.hereliesaz.geministrator.control.ControlRedaction::scrub,
    roleOf: (TaskDefinitionId) -> String? = { null },
): List<LogLine> = events.sortedBy { it.occurredAtEpochMillis }.map { event ->
    val task = event.taskOrNull()
    LogLine(
        atEpochMillis = event.occurredAtEpochMillis,
        scope = task?.let(taskName) ?: "workflow",
        text = scrub(event.logText()),
        role = task?.let(roleOf),
        taskId = task?.value,
    )
}

private fun WorkflowEvent.taskOrNull(): TaskDefinitionId? = when (this) {
    is TaskBecameReady -> taskDefinitionId
    is ExecutorAssigned -> taskDefinitionId
    is AgentAssigned -> taskDefinitionId
    is TaskStarted -> taskDefinitionId
    is ApprovalRequired -> taskDefinitionId
    is ApprovalDecisionReceived -> taskDefinitionId
    is ArtifactCreated -> taskDefinitionId
    is VerificationFailed -> taskDefinitionId
    is RetryScheduled -> taskDefinitionId
    is TaskEscalated -> taskDefinitionId
    is TaskCompleted -> taskDefinitionId
    is TaskFailed -> taskDefinitionId
    is HumanDecisionRequired -> taskDefinitionId
    is TaskCancelled -> taskDefinitionId
    is ExternalRunCancelFailed -> taskDefinitionId
    is TaskRerunRequested -> taskDefinitionId
    is ProviderUsageRecorded -> taskDefinitionId
    is PolicyDecisionRecorded -> taskDefinitionId
    is WorkflowCreated, is WorkflowCompleted, is WorkflowFailed, is WorkflowCancelled -> null
}

internal fun WorkflowEvent.logText(): String = when (this) {
    is WorkflowCreated -> "Workflow created" + (objective?.let { ": $it" } ?: "")
    is TaskBecameReady -> "Ready"
    is ExecutorAssigned -> "Executor assigned: ${executor::class.simpleName ?: "executor"}"
    is AgentAssigned -> "Agent assigned: role ${roleId.value}"
    is TaskStarted -> "Started, attempt $attempt"
    is ApprovalRequired -> "Approval required: $reason"
    is ApprovalDecisionReceived -> when {
        !approved -> "Rejected by ${decidedByRoleId?.value ?: "system"}"
        byDelegation -> "Approved by delegation (${decidedByRoleId?.value ?: "system"})"
        else -> "Approved by ${decidedByRoleId?.value ?: "system"}"
    }
    is ArtifactCreated -> "Artifact created: ${artifact.kind.name} ${artifact.id.value}"
    is VerificationFailed -> "Verification failed: $reason"
    is RetryScheduled -> "Retry scheduled, attempt $nextAttempt: $reason"
    is TaskEscalated -> "Escalated: $reason" + (reassignedRoleId?.let { " (reassigned to ${it.value})" } ?: "")
    is TaskCompleted -> "Completed"
    is TaskFailed -> "Failed: $reason"
    is HumanDecisionRequired -> "Needs a person: $reason"
    is WorkflowCompleted -> "Workflow completed"
    is WorkflowFailed -> "Workflow failed: $reason"
    is WorkflowCancelled -> "Workflow cancelled"
    is TaskCancelled -> "Cancelled"
    is ExternalRunCancelFailed -> "Could not cancel external run $externalRunId: $reason"
    is TaskRerunRequested -> "Rerun requested from ${fromWorkflowRunId.value}" +
        (editedObjective?.let { ", objective edited: $it" } ?: "")
    is ProviderUsageRecorded -> buildString {
        append("Usage: ${providerId.value}")
        inputTokens?.let { append(", in $it") }
        outputTokens?.let { append(", out $it") }
        costUsd?.let { append(", \$$it") }
        latencyMillis?.let { append(", ${it}ms") }
    }
    is PolicyDecisionRecorded -> "Policy ${effect.name} ($code): $action on $resourceType" +
        (resourceId?.let { " $it" } ?: "") + " by $subjectType" + (subjectId?.let { " $it" } ?: "") + ": $reason"
}

/**
 * Everything the run's agents did that [scopeOf] claims (it returns null for another run's sessions),
 * in time order: each session's thinking and steps, as far back as the feeds keep them
 * ([com.hereliesaz.geministrator.workflow.SessionActivityFeeds]: in memory, this launch only).
 */
fun agentActivityLines(
    feeds: Map<SessionActivityKey, List<SessionActivityLine>>,
    scopeOf: (SessionActivityKey) -> String?,
    roleOf: (SessionActivityKey) -> String? = { null },
): List<LogLine> = feeds.flatMap { (key, lines) ->
    val scope = scopeOf(key) ?: return@flatMap emptyList()
    val role = roleOf(key)
    lines.map { line ->
        val mark = if (line.kind == SessionActivityKind.Thinking) "thinking" else "step"
        Triple(line, key, LogLine(line.atEpochMillis, scope, "[$mark] ${line.text}", role))
    }
}.sortedWith(compareBy({ it.first.atEpochMillis }, { it.second.providerRunId.value }, { it.first.id }))
    .map { it.third }

/** The task run ids of a run, for deciding whose session a feed belongs to. */
fun sessionScope(
    taskRunToName: Map<TaskRunId, String>,
): (SessionActivityKey) -> String? = { key -> taskRunToName[key.taskRunId] }

/**
 * Everything that happened in the run, in one list: its events and what its agents thought and did,
 * interleaved by time (events first when they fall in the same millisecond). Nothing is dropped or
 * reworded; each line keeps the role it was about.
 */
fun allActivityLines(events: List<LogLine>, agents: List<LogLine>): List<LogLine> =
    (events.asSequence().map { 0 to it } + agents.asSequence().map { 1 to it })
        .sortedWith(compareBy({ it.second.atEpochMillis }, { it.first }))
        .map { it.second }
        .toList()

/** The roles that appear in [lines], in the order they first act. */
fun rolesIn(lines: List<LogLine>): List<String> = lines.mapNotNull { it.role }.distinct()

/** The lines about [role]; all of them when it is null. */
fun linesForRole(lines: List<LogLine>, role: String?): List<LogLine> =
    if (role == null) lines else lines.filter { it.role == role }

/** The role of each task run of a run, by session, for [agentActivityLines]. */
fun sessionRole(
    taskRunToRole: Map<TaskRunId, String>,
): (SessionActivityKey) -> String? = { key -> taskRunToRole[key.taskRunId] }

