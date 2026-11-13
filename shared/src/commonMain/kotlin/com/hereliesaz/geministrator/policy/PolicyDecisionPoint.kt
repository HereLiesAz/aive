package com.hereliesaz.geministrator.policy

import com.hereliesaz.geministrator.addons.HostPermission
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.READ_ONLY_REPOSITORY_OPERATIONS
import kotlinx.serialization.Serializable

/*
 * The in-process policy decision point (docs/architecture/POLICY.md).
 *
 * Requests are shaped like AuthZEN evaluation requests (subject, action, resource, context) so the
 * scattered authority checks (gate deciders, approval policies, add-on host permissions, THREAT_MODEL
 * invariant 4) are answered in one place. The PDP only decides; the caller gathers the facts it
 * needs (an approval ancestor, a granted gate) and enforces the answer.
 */

@Serializable
enum class PolicyEffect {
    Allow,
    Deny,
    RequireApproval,
}

/** Who is asking. AuthZEN `subject`: [type] and [id], with the rest as properties. */
sealed interface PolicySubject {
    val type: String
    val id: String?

    /** A person deciding in the application (no role identity). */
    data object Human : PolicySubject {
        override val type: String = "human"
        override val id: String? = null
    }

    /** The Aive runtime acting on its own behalf (readiness, scheduling). */
    data object System : PolicySubject {
        override val type: String = "system"
        override val id: String? = null
    }

    /** A configured role (agent definition) with the authorities and capabilities it holds. */
    data class Role(
        val roleId: RoleDefinitionId,
        val name: String = roleId.value,
        val authorities: Set<RoleAuthority> = emptySet(),
        val capabilities: Set<AgentCapability> = emptySet(),
    ) : PolicySubject {
        override val type: String = "role"
        override val id: String = roleId.value
    }

    /** A provider-backed agent session working for [roleId]. */
    data class Agent(
        val providerId: AgentProviderId,
        val roleId: RoleDefinitionId? = null,
    ) : PolicySubject {
        override val type: String = "agent"
        override val id: String = providerId.value
    }

    /** An installed add-on with the host permissions the user granted it. */
    data class Addon(
        val addonId: String,
        val grantedPermissions: Set<HostPermission>,
    ) : PolicySubject {
        override val type: String = "addon"
        override val id: String = addonId
    }

    /** Another pool device that submitted a distributed lease. */
    data class RemotePeer(val nodeId: String? = null) : PolicySubject {
        override val type: String = "remote_peer"
        override val id: String? = nodeId
    }

    companion object {
        fun of(role: RoleDefinition): Role = Role(
            roleId = role.id,
            name = role.name,
            authorities = role.authorities,
            capabilities = role.capabilitiesRequired,
        )

        /** The decider of an approval gate: a role when one is named, otherwise the person. */
        fun decider(roleId: RoleDefinitionId?): PolicySubject = roleId?.let { Role(it) } ?: Human
    }
}

/** What the subject wants to do. AuthZEN `action`: [name]. */
sealed interface PolicyAction {
    val name: String

    /** Approve or reject an approval gate of [kind]. */
    data class DecideApprovalGate(val kind: ApprovalGateKind) : PolicyAction {
        override val name: String = "approval_gate.decide"
    }

    /**
     * Let a task proceed past its plan. [providerRequested] is true when the provider already
     * stopped for approval (the task is AwaitingApproval), even under [ApprovalPolicy.None].
     */
    data class ProceedPastPlan(
        val approvalPolicy: ApprovalPolicy,
        val providerRequested: Boolean = false,
    ) : PolicyAction {
        override val name: String = "plan.proceed"
    }

    /** Run a `RepositoryOperation` task (THREAT_MODEL invariant 4). */
    data class RunRepositoryOperation(val operation: String) : PolicyAction {
        override val name: String = "repository.operation"

        val isMutation: Boolean get() = operation.trim() !in READ_ONLY_REPOSITORY_OPERATIONS
    }

    /** Use one host capability of the add-on API. */
    data class UseHostCapability(val permission: HostPermission) : PolicyAction {
        override val name: String = "addon.host_capability"
    }

    /** Serve in a stage that needs [authority] (for example a verifier needs Verify). */
    data class ExerciseAuthority(val authority: RoleAuthority) : PolicyAction {
        override val name: String = "role.exercise_authority"
    }

    /** Serve in a reasoning-only stage: no implementation authority, no repository-write capability. */
    data object ReasonOnly : PolicyAction {
        override val name: String = "role.reason_only"
    }

    /**
     * An agent calls a tool (for example an MCP tool). [stepEffect] is true when the call is made by a step whose
     * contract declares an effect: such a call needs evidence of a person's approval (declared when validating, granted at run time) (STEP_EFFECT_WITHOUT_APPROVAL).
     */
    data class CallTool(
        val tool: String,
        val mutating: Boolean = true,
        val stepEffect: Boolean = false,
    ) : PolicyAction {
        override val name: String = "tool.call"
    }

    /** Hook: hand work to another node or agent. No caller routes here yet. */
    data class Delegate(val target: String) : PolicyAction {
        override val name: String = "delegate"
    }
}

/** What the action touches. AuthZEN `resource`: [type] and [id], with the rest as properties. */
data class PolicyResource(
    val type: String,
    val id: String? = null,
    val repository: RepositoryRef? = null,
    val path: String? = null,
    val service: String? = null,
    val tool: String? = null,
    /** For an approval gate: the only role allowed to decide it, or null when anyone may. */
    val requiredRoleId: RoleDefinitionId? = null,
) {
    companion object {
        fun gate(id: ApprovalGateId, requiredRoleId: RoleDefinitionId?): PolicyResource =
            PolicyResource(type = "approval_gate", id = id.value, requiredRoleId = requiredRoleId)

        fun task(id: TaskDefinitionId?, repository: RepositoryRef? = null): PolicyResource =
            PolicyResource(type = "task", id = id?.value, repository = repository)

        fun hostApi(permission: HostPermission): PolicyResource =
            PolicyResource(type = "host_api", id = permission.name)

        fun role(role: RoleDefinition): PolicyResource = PolicyResource(type = "role", id = role.id.value)
    }
}

/** What the caller knows about human approval of the action. */
enum class ApprovalEvidence {
    /** Nothing approves it. */
    None,

    /** The workflow DAG declares an unconditional human-approval ancestor that has not run yet. */
    Declared,

    /** A human-approval ancestor completed in this run: a person approved it. */
    Granted,
}

/** Where the request (and the facts in it) came from. */
enum class PolicyProvenance {
    /** This device's own runtime and its own user. */
    Local,

    /** A distributed lease: approval state in it could be forged by any pool-token holder. */
    RemoteLease,

    /** Hook for context taint: the action was proposed from unlabelled or untrusted content. */
    Untrusted,
}

/** AuthZEN `context`: the run, the gate state and the provenance of the request. */
data class PolicyContext(
    val workflowRunId: WorkflowRunId? = null,
    val taskDefinitionId: TaskDefinitionId? = null,
    val approval: ApprovalEvidence = ApprovalEvidence.None,
    val provenance: PolicyProvenance = PolicyProvenance.Local,
)

data class PolicyRequest(
    val subject: PolicySubject,
    val action: PolicyAction,
    val resource: PolicyResource,
    val context: PolicyContext = PolicyContext(),
)

sealed interface PolicyDecision {
    val effect: PolicyEffect
    val code: String
    val reason: String

    data class Allow(
        override val reason: String,
        override val code: String = PolicyCodes.ALLOWED,
    ) : PolicyDecision {
        override val effect: PolicyEffect = PolicyEffect.Allow
    }

    data class Deny(
        override val code: String,
        override val reason: String,
    ) : PolicyDecision {
        override val effect: PolicyEffect = PolicyEffect.Deny
    }

    /**
     * Allowed only after a gate: [requiresHuman] when a person must decide, or a role holding
     * [requiredAuthority] when the approval policy names one.
     */
    data class RequireApproval(
        override val code: String,
        override val reason: String,
        val requiresHuman: Boolean,
        val requiredAuthority: RoleAuthority? = null,
    ) : PolicyDecision {
        override val effect: PolicyEffect = PolicyEffect.RequireApproval
    }
}

val PolicyDecision.isAllowed: Boolean get() = this is PolicyDecision.Allow
val PolicyDecision.isDenied: Boolean get() = this is PolicyDecision.Deny

object PolicyCodes {
    const val ALLOWED = "ALLOWED"
    const val GATE_DECIDER_NOT_AUTHORIZED = "GATE_DECIDER_NOT_AUTHORIZED"
    const val PLAN_APPROVAL_REQUIRED = "PLAN_APPROVAL_REQUIRED"
    const val REPOSITORY_MUTATION_WITHOUT_APPROVAL = "REPOSITORY_MUTATION_WITHOUT_APPROVAL"
    const val REPOSITORY_MUTATION_AWAITING_APPROVAL = "REPOSITORY_MUTATION_AWAITING_APPROVAL"
    const val REMOTE_REPOSITORY_MUTATION = "REMOTE_REPOSITORY_MUTATION"
    const val HOST_PERMISSION_NOT_GRANTED = "HOST_PERMISSION_NOT_GRANTED"
    const val AUTHORITY_NOT_HELD = "AUTHORITY_NOT_HELD"
    const val HOLDS_IMPLEMENT_AUTHORITY = "HOLDS_IMPLEMENT_AUTHORITY"
    const val REQUIRES_REPOSITORY_WRITE = "REQUIRES_REPOSITORY_WRITE"
    const val UNTRUSTED_CONTEXT = "UNTRUSTED_CONTEXT"
    const val SUBJECT_MISMATCH = "SUBJECT_MISMATCH"
    const val STEP_EFFECT_WITHOUT_APPROVAL = "STEP_EFFECT_WITHOUT_APPROVAL"
}

/** Decides one [PolicyRequest]. Pure and synchronous; recording is the caller's job. */
fun interface PolicyDecisionPoint {
    fun decide(request: PolicyRequest): PolicyDecision
}

/** Whether [role] may take [action] on itself (a stage assignment while a workflow is prepared). */
fun PolicyDecisionPoint.decideRole(role: RoleDefinition, action: PolicyAction): PolicyDecision =
    decide(PolicyRequest(subject = PolicySubject.of(role), action = action, resource = PolicyResource.role(role)))

/**
 * The rules The Aive enforces today, in one place. Every rule reproduces the check it replaced
 * exactly (same outcome, same message), so routing a call site through it changes no behaviour.
 */
object InProcessPolicyDecisionPoint : PolicyDecisionPoint {
    override fun decide(request: PolicyRequest): PolicyDecision = when (val action = request.action) {
        is PolicyAction.DecideApprovalGate -> decideGate(request)
        is PolicyAction.ProceedPastPlan -> proceedPastPlan(action)
        is PolicyAction.RunRepositoryOperation -> repositoryOperation(action, request.context)
        is PolicyAction.UseHostCapability -> hostCapability(action, request.subject)
        is PolicyAction.ExerciseAuthority -> exerciseAuthority(action, request.subject)
        PolicyAction.ReasonOnly -> reasonOnly(request.subject)
        is PolicyAction.CallTool -> callTool(action, request.context)
        is PolicyAction.Delegate -> delegate(action, request.context)
    }

    // A gate that names a role may be decided only by that role; otherwise by anyone (a person
    // or a role). Mirrors the former `gate.requiredRoleId == null || == decidedByRoleId` check.
    private fun decideGate(request: PolicyRequest): PolicyDecision {
        val required = request.resource.requiredRoleId
        val deciderRoleId = (request.subject as? PolicySubject.Role)?.roleId
        return if (required == null || required == deciderRoleId) {
            PolicyDecision.Allow("Decider may decide gate ${request.resource.id}")
        } else {
            PolicyDecision.Deny(
                code = PolicyCodes.GATE_DECIDER_NOT_AUTHORIZED,
                reason = "Role ${deciderRoleId?.value ?: "<human>"} is not authorized for gate ${request.resource.id}",
            )
        }
    }

    private fun proceedPastPlan(action: PolicyAction.ProceedPastPlan): PolicyDecision =
        when (val policy = action.approvalPolicy) {
            ApprovalPolicy.None -> if (action.providerRequested) {
                // The provider insisted on approval (e.g. committing a workflow file to the
                // default branch); with no policy to name an approver, that is a person's call.
                PolicyDecision.RequireApproval(
                    code = PolicyCodes.PLAN_APPROVAL_REQUIRED,
                    reason = "The provider requested plan approval",
                    requiresHuman = true,
                )
            } else {
                PolicyDecision.Allow("The task has no approval policy")
            }
            ApprovalPolicy.HumanApproval -> PolicyDecision.RequireApproval(
                code = PolicyCodes.PLAN_APPROVAL_REQUIRED,
                reason = "The task's approval policy requires a person",
                requiresHuman = true,
            )
            is ApprovalPolicy.RoleApproval -> PolicyDecision.RequireApproval(
                code = PolicyCodes.PLAN_APPROVAL_REQUIRED,
                reason = "The task's approval policy requires ${policy.authority.name} authority",
                requiresHuman = false,
                requiredAuthority = policy.authority,
            )
        }

    // THREAT_MODEL invariant 4. Read-only operations are always allowed. A mutation proposed by a
    // remote lease is refused outright (its approval evidence could be forged); a local mutation is
    // denied without an approval ancestor, gated while one is only declared, allowed once granted.
    private fun repositoryOperation(
        action: PolicyAction.RunRepositoryOperation,
        context: PolicyContext,
    ): PolicyDecision {
        if (!action.isMutation) {
            return PolicyDecision.Allow("Repository operation '${action.operation}' only reads")
        }
        if (context.provenance == PolicyProvenance.RemoteLease) {
            return PolicyDecision.Deny(
                code = PolicyCodes.REMOTE_REPOSITORY_MUTATION,
                reason = "repository operation '${action.operation}' changes the repository; " +
                    "remote workers run only read-only operations",
            )
        }
        return when (context.approval) {
            ApprovalEvidence.Granted -> PolicyDecision.Allow("A person approved this repository change")
            ApprovalEvidence.Declared -> PolicyDecision.RequireApproval(
                code = PolicyCodes.REPOSITORY_MUTATION_AWAITING_APPROVAL,
                reason = "Repository operation '${action.operation}' waits for its human-approval ancestor",
                requiresHuman = true,
            )
            ApprovalEvidence.None -> PolicyDecision.Deny(
                code = PolicyCodes.REPOSITORY_MUTATION_WITHOUT_APPROVAL,
                reason = "No human approval was granted for this repository change; it will not run.",
            )
        }
    }

    private fun hostCapability(
        action: PolicyAction.UseHostCapability,
        subject: PolicySubject,
    ): PolicyDecision {
        val addon = subject as? PolicySubject.Addon
            ?: return PolicyDecision.Deny(
                code = PolicyCodes.SUBJECT_MISMATCH,
                reason = "Only an add-on uses host capabilities",
            )
        return if (action.permission in addon.grantedPermissions) {
            PolicyDecision.Allow("Add-on ${addon.addonId} holds ${action.permission}")
        } else {
            PolicyDecision.Deny(
                code = PolicyCodes.HOST_PERMISSION_NOT_GRANTED,
                reason = "Add-on ${addon.addonId} lacks permission ${action.permission}",
            )
        }
    }

    private fun exerciseAuthority(
        action: PolicyAction.ExerciseAuthority,
        subject: PolicySubject,
    ): PolicyDecision {
        val role = subject as? PolicySubject.Role
        return if (role != null && action.authority in role.authorities) {
            PolicyDecision.Allow("${role.name} holds ${action.authority} authority")
        } else {
            PolicyDecision.Deny(
                code = PolicyCodes.AUTHORITY_NOT_HELD,
                reason = "${role?.name ?: subject.type} does not have ${action.authority} authority",
            )
        }
    }

    // Checked in the same order as the former paired requires: authority first, then capability.
    private fun reasonOnly(subject: PolicySubject): PolicyDecision {
        val role = subject as? PolicySubject.Role
            ?: return PolicyDecision.Deny(PolicyCodes.SUBJECT_MISMATCH, "Only a role serves a reasoning stage")
        return when {
            RoleAuthority.Implement in role.authorities -> PolicyDecision.Deny(
                code = PolicyCodes.HOLDS_IMPLEMENT_AUTHORITY,
                reason = "${role.name} may not hold implementation authority",
            )
            AgentCapability.RepositoryWrite in role.capabilities -> PolicyDecision.Deny(
                code = PolicyCodes.REQUIRES_REPOSITORY_WRITE,
                reason = "${role.name} may not require repository-write capability",
            )
            else -> PolicyDecision.Allow("${role.name} is reasoning-only")
        }
    }

    // Hook rules: they only ever add a gate, never remove one.
    private fun callTool(action: PolicyAction.CallTool, context: PolicyContext): PolicyDecision =
        if (action.stepEffect && action.mutating && context.approval == ApprovalEvidence.None) {
            // Invariant 4a: a step that declares an effect acts only once a person approved it.
            PolicyDecision.RequireApproval(
                code = PolicyCodes.STEP_EFFECT_WITHOUT_APPROVAL,
                reason = "Tool '${action.tool}' changes something and no person has approved this step",
                requiresHuman = true,
            )
        } else if (action.mutating && context.provenance != PolicyProvenance.Local) {
            PolicyDecision.RequireApproval(
                code = PolicyCodes.UNTRUSTED_CONTEXT,
                reason = "Tool '${action.tool}' would act on untrusted context",
                requiresHuman = true,
            )
        } else {
            PolicyDecision.Allow("Tool '${action.tool}' may be called")
        }

    private fun delegate(action: PolicyAction.Delegate, context: PolicyContext): PolicyDecision =
        if (context.provenance != PolicyProvenance.Local) {
            PolicyDecision.RequireApproval(
                code = PolicyCodes.UNTRUSTED_CONTEXT,
                reason = "Delegation to ${action.target} was proposed from untrusted context",
                requiresHuman = true,
            )
        } else {
            PolicyDecision.Allow("Delegation to ${action.target} may proceed")
        }
}
