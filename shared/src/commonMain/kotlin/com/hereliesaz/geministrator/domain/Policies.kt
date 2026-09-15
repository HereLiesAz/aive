package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
sealed interface ApprovalPolicy {
    @Serializable data object None : ApprovalPolicy
    @Serializable data class RoleApproval(val authority: RoleAuthority) : ApprovalPolicy
    @Serializable data object HumanApproval : ApprovalPolicy
}

@Serializable
sealed interface VerificationPolicy {
    @Serializable data object None : VerificationPolicy
    @Serializable data class Required(
        val verifierRoleId: RoleDefinitionId,
        val criteria: List<String> = emptyList(),
    ) : VerificationPolicy
}

/**
 * Declares how model-backed reasoning for a workflow task should be materialized.
 *
 * The default remains a single governed task. Centralized MoA is expanded into explicit workflow
 * DAG nodes before execution so normal persistence, retries, approvals, artifacts, and verification
 * remain authoritative.
 */
@Serializable
sealed interface CompoundInferencePolicy {
    @Serializable data object Single : CompoundInferencePolicy

    @Serializable
    data class CentralizedMixtureOfAgents(
        val proposerRoleIds: List<RoleDefinitionId>,
        val aggregatorRoleId: RoleDefinitionId,
    ) : CompoundInferencePolicy {
        init {
            require(proposerRoleIds.size in 2..MAX_PROPOSERS) {
                "Centralized MoA requires 2..$MAX_PROPOSERS proposers"
            }
        }
    }

    companion object {
        const val MAX_PROPOSERS: Int = 8
    }
}

@Serializable
enum class TestDesignPolicy {
    None,
    BeforeImplementation,
    AfterImplementation,
    BeforeAndAfterImplementation,
}

@Serializable
enum class PromptReusePolicy {
    ProviderDefault,
    PreferCache,
    DisableCache,
}

@Serializable
data class RetryPolicy(
    val maxAttempts: Int = 2,
    val retryOn: Set<RetryReason> = setOf(
        RetryReason.ProviderFailure,
        RetryReason.PlanRejected,
        RetryReason.VerificationFailed,
    ),
    val includeFailureContext: Boolean = true,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }
}

@Serializable
enum class RetryReason {
    ProviderFailure,
    PlanRejected,
    VerificationFailed,
    TestsFailed,
    IntegrationConflict,
}

@Serializable
sealed interface EscalationPolicy {
    @Serializable data object FailWorkflow : EscalationPolicy
    @Serializable data object RequireHumanDecision : EscalationPolicy
    @Serializable data class Reassign(val roleId: RoleDefinitionId) : EscalationPolicy
}

@Serializable
sealed interface ProviderConstraints {
    @Serializable data object None : ProviderConstraints
    @Serializable data class RequireCapabilities(val capabilities: Set<AgentCapability>) : ProviderConstraints
    @Serializable data class RequireProvider(val providerId: AgentProviderId) : ProviderConstraints
}

@Serializable
data class ConcurrencyPolicy(
    val maxConcurrentTasks: Int = 4,
    val perProviderLimits: Map<AgentProviderId, Int> = emptyMap(),
) {
    init {
        require(maxConcurrentTasks >= 1) { "maxConcurrentTasks must be at least 1" }
        require(perProviderLimits.values.all { it >= 1 }) { "provider limits must be at least 1" }
    }
}

@Serializable
enum class IntegrationPolicy {
    Manual,
    PullRequest,
    AutoMergeAfterVerification,
}

/** Controls what context is included in provider payloads for agent tasks. */
@Serializable
data class PayloadRedactionPolicy(
    /** Artifact kinds from dependency task runs that must not be sent to providers. */
    val excludedArtifactKinds: Set<ArtifactKind> = emptySet(),
    /** When true, the task objective is replaced with a placeholder in provider payloads. */
    val redactObjective: Boolean = false,
    /** When true, role standing instructions are replaced with a placeholder in provider payloads. */
    val redactRoleInstructions: Boolean = false,
) {
    val isActive: Boolean get() =
        excludedArtifactKinds.isNotEmpty() || redactObjective || redactRoleInstructions
}
