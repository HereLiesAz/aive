package com.hereliesaz.geministrator.policy

import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.WorkflowEventSink

/**
 * The timeline event for [decision], or null when there is nothing to audit: an Allow, or a
 * request with no workflow run in its context (add-on calls, definition validation).
 */
fun PolicyRequest.auditEvent(decision: PolicyDecision, nowEpochMillis: Long): PolicyDecisionRecorded? {
    if (decision.effect == PolicyEffect.Allow) return null
    val runId = context.workflowRunId ?: return null
    return PolicyDecisionRecorded(
        workflowRunId = runId,
        taskDefinitionId = context.taskDefinitionId,
        effect = decision.effect,
        code = decision.code,
        action = action.name,
        subjectType = subject.type,
        subjectId = subject.id,
        resourceType = resource.type,
        resourceId = resource.id,
        reason = decision.reason,
        occurredAtEpochMillis = nowEpochMillis,
    )
}

/** Appends the audit event for a non-trivial [decision] about [request]; Allow records nothing. */
suspend fun WorkflowEventSink.recordPolicyDecision(
    request: PolicyRequest,
    decision: PolicyDecision,
    nowEpochMillis: Long,
) {
    request.auditEvent(decision, nowEpochMillis)?.let { append(it) }
}

/** Decides [request] and records the outcome on this sink when it is a deny or a gate. */
suspend fun PolicyDecisionPoint.decideAndRecord(
    request: PolicyRequest,
    sink: WorkflowEventSink,
    nowEpochMillis: Long,
): PolicyDecision = decide(request).also { sink.recordPolicyDecision(request, it, nowEpochMillis) }
