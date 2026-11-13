package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.anyEffectful
import com.hereliesaz.geministrator.domain.mutatesConnectedSource
import com.hereliesaz.geministrator.policy.ApprovalEvidence
import com.hereliesaz.geministrator.policy.InProcessPolicyDecisionPoint
import com.hereliesaz.geministrator.policy.PolicyAction
import com.hereliesaz.geministrator.policy.PolicyContext
import com.hereliesaz.geministrator.policy.PolicyDecision
import com.hereliesaz.geministrator.policy.PolicyRequest
import com.hereliesaz.geministrator.policy.PolicyResource
import com.hereliesaz.geministrator.policy.PolicySubject
import com.hereliesaz.geministrator.domain.TaskDefinitionId

/*
 * The approval rule for steps that declare an effect (THREAT_MODEL invariant 4a). A step whose contract
 * declares an effect that changes something (a connected tool that writes or removes, a repository branch,
 * anything that leaves the device) is made only with an approval: the person's plan gate on the step itself
 * (no tool is offered before a person approved the plan) or an unconditional approval step before it.
 */

/** True when [task] declares an effect that changes something outside its own output. */
fun TaskDefinition.isEffectfulStep(): Boolean = contract?.effects?.anyEffectful() == true

/** True when [task] carries the person's plan gate. */
fun TaskDefinition.hasPlanGate(): Boolean = approvalPolicy == ApprovalPolicy.HumanApproval

/**
 * The approval evidence for [task]'s tool calls. [hasApprovalAncestor] is whether an approval step before it was
 * granted (at run time) or declared (when validating).
 */
fun stepApprovalEvidence(task: TaskDefinition, hasApprovalAncestor: Boolean, granted: Boolean): ApprovalEvidence = when {
    task.hasPlanGate() -> ApprovalEvidence.Granted
    hasApprovalAncestor && granted -> ApprovalEvidence.Granted
    hasApprovalAncestor -> ApprovalEvidence.Declared
    else -> ApprovalEvidence.None
}

/** The policy decision for [task] making its declared effect: allowed only with evidence of a person's approval. */
fun decideStepEffect(taskId: TaskDefinitionId, task: TaskDefinition, evidence: ApprovalEvidence): PolicyDecision =
    InProcessPolicyDecisionPoint.decide(
        PolicyRequest(
            subject = PolicySubject.System,
            action = PolicyAction.CallTool(
                tool = task.contract?.allowedTools?.firstOrNull() ?: task.name,
                mutating = task.isEffectfulStep(),
                stepEffect = true,
            ),
            resource = PolicyResource.task(taskId),
            context = PolicyContext(taskDefinitionId = taskId, approval = evidence),
        ),
    )

/** True when [task] changes a connected source (its tool may be destructive: each call asks even after consent). */
fun TaskDefinition.changesConnectedSource(): Boolean = contract?.effects?.mutatesConnectedSource() == true
