package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ArtifactKind
import kotlinx.serialization.Serializable

/*
 * Specialist inputs with the arithmetic already done.
 *
 * A 0.5B model is unreliable at comparing and summing numbers (context limit vs required tokens,
 * rank vs cost, running token totals). These views keep every raw field and add the comparisons as
 * booleans and running totals, in the order the rule applies them, so the model selects instead of
 * calculating. The guard sends exactly these; the training corpus is generated from them.
 *
 * Text is cleaned here the way the baseline cleans it (trimmed, blanks and repeats dropped), so a
 * model is never asked to filter: trained adapters copy what they are given, blanks included.
 */

@Serializable
internal data class AgentRoutingModelInput(
    /**
     * Ids of the eligible candidates in routing order, first so the model reads it first: select the
     * first, fall back to the second, escalate when empty. Trained adapters ignored per-candidate
     * `eligible` flags (picking ineligible or cheaper agents), so the answer is copied, not filtered.
     */
    val eligibleInRoutingOrder: List<String>,
    val requiredCapabilities: Set<String>,
    val requiredContextTokens: Int,
    val requiredContextType: String,
    /** In routing order: preferenceRank, then estimatedCost, then id. */
    val candidates: List<AgentCandidateFacts>,
) {
    fun toInput(): AgentRoutingInput = AgentRoutingInput(
        requiredCapabilities = requiredCapabilities,
        requiredContextTokens = requiredContextTokens,
        candidates = candidates.map {
            AgentRouteCandidate(it.id, it.capabilities, it.estimatedCost, it.contextLimitTokens, it.available, it.preferenceRank)
        },
        requiredContextType = requiredContextType,
    )

    companion object {
        fun of(input: AgentRoutingInput): AgentRoutingModelInput {
            val candidates = input.candidates
                .sortedWith(compareBy<AgentRouteCandidate> { it.preferenceRank }.thenBy { it.estimatedCost }.thenBy { it.id })
                .map { candidate ->
                    val fits = candidate.contextLimitTokens >= input.requiredContextTokens
                    val capable = candidate.capabilities.containsAll(input.requiredCapabilities)
                    AgentCandidateFacts(
                        id = candidate.id,
                        capabilities = candidate.capabilities,
                        estimatedCost = candidate.estimatedCost,
                        contextLimitTokens = candidate.contextLimitTokens,
                        available = candidate.available,
                        preferenceRank = candidate.preferenceRank,
                        fitsContext = fits,
                        hasRequiredCapabilities = capable,
                        eligible = candidate.available && fits && capable,
                    )
                }
            return AgentRoutingModelInput(
                eligibleInRoutingOrder = candidates.filter { it.eligible }.map { it.id },
                requiredCapabilities = input.requiredCapabilities,
                requiredContextTokens = input.requiredContextTokens,
                requiredContextType = input.requiredContextType,
                candidates = candidates,
            )
        }
    }
}

@Serializable
internal data class AgentCandidateFacts(
    val id: String,
    val capabilities: Set<String>,
    val estimatedCost: Double,
    val contextLimitTokens: Int,
    val available: Boolean,
    val preferenceRank: Int,
    val fitsContext: Boolean,
    val hasRequiredCapabilities: Boolean,
    val eligible: Boolean,
)

/**
 * The memory query input with its text already cleaned: the objective trimmed, and each list trimmed
 * with blank entries and case-only repeats dropped, exactly as the baseline drops them. A trained
 * adapter copied list entries verbatim, blanks included ("" and "\t" became queries), so it is never
 * handed one.
 */
@Serializable
internal data class MemoryQueryModelInput(
    val objective: String,
    val knownEntities: List<String>,
    val knownActions: List<String>,
    val codeSymbols: List<String>,
    val chronologicalContextRequired: Boolean,
    val alreadyRetrievedEvidenceCount: Int,
    val maxQueries: Int,
) {
    fun toInput(): MemoryQueryInput = MemoryQueryInput(
        objective, knownEntities, knownActions, codeSymbols, chronologicalContextRequired, alreadyRetrievedEvidenceCount, maxQueries,
    )

    companion object {
        fun of(input: MemoryQueryInput): MemoryQueryModelInput {
            fun clean(values: List<String>) = values.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
            return MemoryQueryModelInput(
                objective = input.objective.trim(),
                knownEntities = clean(input.knownEntities),
                knownActions = clean(input.knownActions),
                codeSymbols = clean(input.codeSymbols),
                chronologicalContextRequired = input.chronologicalContextRequired,
                alreadyRetrievedEvidenceCount = input.alreadyRetrievedEvidenceCount,
                maxQueries = input.maxQueries,
            )
        }
    }
}

@Serializable
internal data class ToolRoutingModelInput(
    /**
     * Ids of the available tools that support [operationClass], in routing order (preferenceRank,
     * then id): select the first. Empty means UnavailableCapability, or NoTool when [operationClass]
     * is blank. Trained adapters picked the first-listed supporting tool instead of the lowest rank.
     */
    val eligibleInRoutingOrder: List<String>,
    val operationClass: String?,
    /** In routing order: preferenceRank, then id. */
    val capabilities: List<ToolCapability>,
    val requiredInputs: List<String>,
    /** Null when the caller did not say; then no tool is excluded for inputs. */
    val providedInputs: List<String>? = null,
    /** Supporting tools exist but none has its required inputs: the answer is MissingInputs. */
    val missingInputs: Boolean = false,
) {
    fun toInput(): ToolRoutingInput = ToolRoutingInput(operationClass, capabilities, requiredInputs, providedInputs)

    companion object {
        fun of(input: ToolRoutingInput): ToolRoutingModelInput {
            val operation = input.operationClass?.trim().orEmpty()
            val ordered = input.capabilities.sortedWith(compareBy<ToolCapability> { it.preferenceRank }.thenBy { it.id })
            val supporting = if (operation.isEmpty()) emptyList() else ordered.filter { it.available && operation in it.operationClasses }
            val provided = input.providedInputs?.toSet()
            val runnable = if (provided == null) supporting else supporting.filter { provided.containsAll(it.requiredInputs) }
            return ToolRoutingModelInput(
                eligibleInRoutingOrder = runnable.map { it.id },
                operationClass = input.operationClass,
                capabilities = ordered,
                requiredInputs = input.requiredInputs,
                providedInputs = input.providedInputs,
                missingInputs = supporting.isNotEmpty() && runnable.isEmpty(),
            )
        }
    }
}

@Serializable
internal data class ContextPackingModelInput(
    val tokenBudget: Int,
    val evidence: List<ContextEvidence>,
    /**
     * Groups in packing order (required first, then priority, then smaller). A group is kept when
     * [ContextGroupFacts.fits]; [ContextGroupFacts.tokensUsedAfter] is the running total after it.
     */
    val groups: List<ContextGroupFacts>,
) {
    fun toInput(): ContextPackingInput = ContextPackingInput(tokenBudget, evidence)

    companion object {
        fun of(input: ContextPackingInput): ContextPackingModelInput {
            val ordered = input.evidence
                .groupBy { it.conflictGroup ?: "__single__:${it.id}" }
                .values
                .sortedWith(
                    compareByDescending<List<ContextEvidence>> { group -> group.any { it.required } }
                        .thenByDescending { group -> group.maxOfOrNull { it.priority } ?: 0 }
                        .thenBy { group -> group.sumOf { it.estimatedTokens } },
                )
            var used = 0
            val groups = ordered.map { group ->
                val tokens = group.sumOf { it.estimatedTokens }
                val fits = used + tokens <= input.tokenBudget
                if (fits) used += tokens
                ContextGroupFacts(
                    evidenceIds = group.map { it.id },
                    tokens = tokens,
                    required = group.any { it.required },
                    fits = fits,
                    tokensUsedAfter = used,
                )
            }
            return ContextPackingModelInput(input.tokenBudget, input.evidence, groups)
        }
    }
}

@Serializable
internal data class ContextGroupFacts(
    val evidenceIds: List<String>,
    val tokens: Int,
    val required: Boolean,
    val fits: Boolean,
    val tokensUsedAfter: Int,
)

@Serializable
internal data class CapabilityAssessmentModelInput(
    val assessment: CapabilityAssessment,
    val contextLimitExceeded: Boolean,
    /** Points from the reasoning flags (multi-step 1, codebase-wide 2, architectural 3, contradiction 3). */
    val reasoningLoad: Int = 0,
    /** reasoningLoad reaches the escalation threshold. */
    val reasoningEscalates: Boolean = false,
) {
    companion object {
        fun of(input: CapabilityAssessment): CapabilityAssessmentModelInput = CapabilityAssessmentModelInput(
            assessment = input,
            contextLimitExceeded = input.requiredContextTokens > input.localContextLimitTokens,
            reasoningLoad = reasoningLoad(input),
            reasoningEscalates = reasoningLoad(input) >= ESCALATION_THRESHOLD,
        )
    }
}

/** The handoff input already cleaned as the packet carries it: copy every field. */
@Serializable
internal data class HandoffModelInput(
    val objective: String,
    val completed: List<String>,
    val artifacts: List<String>,
    val state: Map<String, String>,
    val unresolved: List<String>,
    val failures: List<String>,
    val nextAction: String?,
    val acceptanceCriteria: List<String>,
    val provenance: List<String>,
) {
    fun toInput(): HandoffInput =
        HandoffInput(objective, completed, artifacts, state, unresolved, failures, nextAction, acceptanceCriteria, provenance)

    companion object {
        fun of(input: HandoffInput): HandoffModelInput = HandoffModelInput(
            objective = input.objective.trim(),
            completed = input.completed.distinct(),
            artifacts = input.artifacts.distinct(),
            state = input.state,
            unresolved = input.unresolved.distinct(),
            failures = input.failures.distinct(),
            nextAction = input.nextAction?.trim()?.takeIf(String::isNotEmpty),
            acceptanceCriteria = input.acceptanceCriteria.distinct(),
            provenance = input.provenance.distinct(),
        )
    }
}

@Serializable
internal data class CompletionModelInput(
    val objective: String,
    val criteria: List<CriterionEvidence>,
    val taskTerminal: Boolean,
    /** Every criterion's evidence ids, flattened, repeats dropped: the result's evidenceIds. */
    val evidenceIds: List<String>,
    /** Criteria not Passed or without evidence, in order: the result's unsatisfiedCriteria. */
    val unsatisfiedCriteria: List<String>,
    /**
     * Over required criteria, in decision order: anyFailed → Failed, anyBlocked → Blocked,
     * !taskTerminal → Incomplete, noRequiredCriteria → NeedsVerification, anyNotRun →
     * NeedsVerification, anyStale → NeedsVerification, anyMissingEvidence → Incomplete, else Complete.
     */
    val anyFailed: Boolean,
    val anyBlocked: Boolean,
    val anyNotRun: Boolean,
    val noRequiredCriteria: Boolean = false,
    val anyStale: Boolean = false,
    val anyMissingEvidence: Boolean = false,
) {
    fun toInput(): CompletionInput = CompletionInput(objective, criteria, taskTerminal)

    companion object {
        fun of(input: CompletionInput): CompletionModelInput = CompletionModelInput(
            objective = input.objective,
            criteria = input.criteria,
            taskTerminal = input.taskTerminal,
            evidenceIds = input.criteria.flatMap { it.evidenceIds }.distinct(),
            unsatisfiedCriteria = input.criteria
                .filter { it.status != EvidenceStatus.Passed || it.evidenceIds.isEmpty() || it.stale }
                .map { it.criterion },
            anyFailed = input.criteria.any { it.required && it.status == EvidenceStatus.Failed },
            anyBlocked = input.criteria.any { it.required && it.status == EvidenceStatus.Blocked },
            anyNotRun = input.criteria.any { it.required && it.status == EvidenceStatus.NotRun },
            noRequiredCriteria = input.criteria.none { it.required },
            anyStale = input.criteria.any { it.required && it.status == EvidenceStatus.Passed && it.stale },
            anyMissingEvidence = input.criteria.any { it.required && it.evidenceIds.isEmpty() },
        )
    }
}

@Serializable
internal data class VerificationPlanningModelInput(
    val objective: String,
    /** Blank criteria and repeats dropped. */
    val acceptanceCriteria: List<String>,
    /** Per criterion, in order: each operation its wording calls for. */
    val criterionOperations: List<CriterionOperation>,
    val artifactKinds: Set<ArtifactKind>,
    /** Repeats dropped. */
    val targetPlatforms: List<String>,
) {
    fun toInput(): VerificationPlanningInput =
        VerificationPlanningInput(objective, acceptanceCriteria, artifactKinds, targetPlatforms)

    companion object {
        fun of(input: VerificationPlanningInput): VerificationPlanningModelInput {
            val criteria = input.acceptanceCriteria.filter(String::isNotBlank).distinct()
            return VerificationPlanningModelInput(
                objective = input.objective,
                acceptanceCriteria = criteria,
                criterionOperations = criteria.flatMap { c -> verificationOperationsFor(c).map { CriterionOperation(c, it) } },
                artifactKinds = input.artifactKinds,
                targetPlatforms = input.targetPlatforms.distinct(),
            )
        }
    }
}

@Serializable
internal data class CriterionOperation(val criterion: String, val operationClass: String)
