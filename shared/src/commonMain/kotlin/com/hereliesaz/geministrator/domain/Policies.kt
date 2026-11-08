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
 * remain authoritative. [ResourceAware] authorizes the preparer to choose between the resource-selected
 * topologies using task structure and durable provider resource history.
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

    /**
     * Authorizes a governed Skeleton-of-Thought subgraph.
     *
     * The skeleton and branch-expansion roles are reasoning-only. The independence reviewer checks
     * branch overlap before parallel expansion. The original task remains the aggregator so its
     * durable identity, authority, output contract, and downstream dependencies are preserved.
     */
    @Serializable
    data class SkeletonOfThought(
        val skeletonRoleId: RoleDefinitionId,
        val independenceReviewerRoleId: RoleDefinitionId,
        val expansionRoleIds: List<RoleDefinitionId>,
        val aggregatorRoleId: RoleDefinitionId,
    ) : CompoundInferencePolicy {
        init {
            require(expansionRoleIds.size in 2..MAX_PROPOSERS) {
                "Skeleton-of-Thought requires 2..$MAX_PROPOSERS expansion branches"
            }
            require(independenceReviewerRoleId != skeletonRoleId) {
                "Skeleton-of-Thought independence review must use a role distinct from the skeleton role"
            }
            require(independenceReviewerRoleId != aggregatorRoleId) {
                "Skeleton-of-Thought independence review must be distinct from aggregation"
            }
        }
    }

    /**
     * Authorizes a governed refinement ladder: the task is worked coarse to fine, and every
     * reasoning level must pass an independent gate before the next, finer level (and finally the
     * original task) is spent. A coarse plan the gate rejects stops the run before any finer work
     * is paid for.
     *
     * Levels are reasoning-only. The original task stays the final level so its durable identity,
     * authority, output contract, and downstream dependencies are preserved. One [gateReviewerRoleId]
     * reviews every level and must be distinct from every level role and the final role.
     */
    @Serializable
    data class RefinementLadder(
        val levels: List<RefinementLevel>,
        val gateReviewerRoleId: RoleDefinitionId,
        val finalRoleId: RoleDefinitionId,
    ) : CompoundInferencePolicy {
        init {
            require(levels.size in 1..MAX_REFINEMENT_LEVELS) {
                "Refinement ladder requires 1..$MAX_REFINEMENT_LEVELS reasoning levels"
            }
            require(levels.none { it.roleId == gateReviewerRoleId }) {
                "Refinement ladder gate review must use a role distinct from every level role"
            }
            require(gateReviewerRoleId != finalRoleId) {
                "Refinement ladder gate review must be distinct from the final role"
            }
        }
    }

    /**
     * Authorizes bounded automatic selection between Single and centralized MoA.
     *
     * At least one resource ceiling is required. If the runtime lacks enough measured history for
     * a configured ceiling, or the estimate exceeds it, planning conservatively remains Single.
     */
    @Serializable
    data class ResourceAware(
        val proposerRoleIds: List<RoleDefinitionId>,
        val aggregatorRoleId: RoleDefinitionId,
        val maxEstimatedCostUsd: Double? = null,
        val maxEstimatedLatencyMillis: Long? = null,
        val minimumHistoricalSamplesPerProvider: Int = 2,
        val minimumComplexityScore: Int = 3,
    ) : CompoundInferencePolicy {
        init {
            require(proposerRoleIds.size in 2..MAX_PROPOSERS) {
                "Resource-aware MoA requires 2..$MAX_PROPOSERS proposers"
            }
            require(maxEstimatedCostUsd != null || maxEstimatedLatencyMillis != null) {
                "Resource-aware inference requires a cost and/or latency ceiling"
            }
            require(maxEstimatedCostUsd == null || (maxEstimatedCostUsd.isFinite() && maxEstimatedCostUsd >= 0.0)) {
                "Resource-aware cost ceiling must be finite and non-negative"
            }
            require(maxEstimatedLatencyMillis == null || maxEstimatedLatencyMillis >= 1L) {
                "Resource-aware latency ceiling must be positive"
            }
            require(minimumHistoricalSamplesPerProvider >= 1) {
                "Resource-aware inference requires at least one historical sample per provider"
            }
            require(minimumComplexityScore >= 0) {
                "Resource-aware complexity threshold must not be negative"
            }
        }
    }

    companion object {
        const val MAX_PROPOSERS: Int = 8
        const val MAX_REFINEMENT_LEVELS: Int = 4
    }
}

/** One reasoning level of a [CompoundInferencePolicy.RefinementLadder], ordered coarse to fine. */
@Serializable
data class RefinementLevel(
    val roleId: RoleDefinitionId,
    /** What this level decides, e.g. "intent and responsibilities" or "executor and parameter bindings". */
    val focus: String,
    /**
     * Opt-in output contract: the level answers with the `aive.ladder.level.v1` JSON document
     * (workflow/refinement/LadderArtifact.kt) instead of prose, so its structure can be checked
     * against the coarser level it refines. Off by default.
     */
    val structured: Boolean = false,
) {
    init {
        require(focus.isNotBlank() && focus.length <= MAX_FOCUS_LENGTH) {
            "Refinement level focus must be 1..$MAX_FOCUS_LENGTH characters"
        }
    }

    companion object {
        const val MAX_FOCUS_LENGTH: Int = 512
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
