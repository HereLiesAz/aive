package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun

sealed interface FailureDecision {
    data class Retry(val nextAttempt: Int) : FailureDecision
    data object FailWorkflow : FailureDecision
    data object RequireHumanDecision : FailureDecision
    data class Reassign(val roleId: RoleDefinitionId) : FailureDecision
}

object FailurePolicyEvaluator {
    fun decide(
        taskRun: TaskRun,
        retryPolicy: RetryPolicy,
        escalationPolicy: EscalationPolicy,
        reason: RetryReason,
    ): FailureDecision {
        // A restart on another provider after that provider's own failure is not a retry of the work.
        val used = (taskRun.attempt - taskRun.failedProviders.size).coerceAtLeast(1)
        val retryAllowed = reason in retryPolicy.retryOn && used < retryPolicy.maxAttempts
        if (retryAllowed) return FailureDecision.Retry(taskRun.attempt + 1)

        return when (escalationPolicy) {
            EscalationPolicy.FailWorkflow -> FailureDecision.FailWorkflow
            EscalationPolicy.RequireHumanDecision -> FailureDecision.RequireHumanDecision
            // Reassignment is one more attempt past the retry budget, not an open-ended loop: once
            // the reassigned attempt has failed too, the workflow fails.
            is EscalationPolicy.Reassign -> if (used <= retryPolicy.maxAttempts) {
                FailureDecision.Reassign(escalationPolicy.roleId)
            } else {
                FailureDecision.FailWorkflow
            }
        }
    }
}
