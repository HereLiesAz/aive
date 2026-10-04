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
    /** In routing order: preferenceRank, then cost per reliability, then id. */
    val candidates: List<AgentCandidateFacts>,
    /** Capable agents passed over because their circuit is open. */
    val circuitOpen: List<String> = emptyList(),
    /** Every capable agent is open: the single eligible agent is a trial (reason CIRCUIT_HALF_OPEN). */
    val halfOpen: Boolean = false,
) {
    fun toInput(): AgentRoutingInput = AgentRoutingInput(
        requiredCapabilities = requiredCapabilities,
        requiredContextTokens = requiredContextTokens,
        candidates = candidates.map {
            AgentRouteCandidate(
                it.id, it.capabilities, it.estimatedCost, it.contextLimitTokens, it.available, it.preferenceRank,
                it.consecutiveFailures, it.successRate,
            )
        },
        requiredContextType = requiredContextType,
    )

    companion object {
        fun of(input: AgentRoutingInput): AgentRoutingModelInput {
            val routing = agentRouting(input)
            val eligible = routing.eligible.map { it.id }.toSet()
            val open = routing.open.map { it.id }.toSet()
            val candidates = input.candidates
                .sortedWith(agentRoutingOrder)
                .map { candidate ->
                    AgentCandidateFacts(
                        id = candidate.id,
                        capabilities = candidate.capabilities,
                        estimatedCost = candidate.estimatedCost,
                        contextLimitTokens = candidate.contextLimitTokens,
                        available = candidate.available,
                        preferenceRank = candidate.preferenceRank,
                        fitsContext = candidate.contextLimitTokens >= input.requiredContextTokens,
                        hasRequiredCapabilities = candidate.capabilities.containsAll(input.requiredCapabilities),
                        eligible = candidate.id in eligible,
                        consecutiveFailures = candidate.consecutiveFailures,
                        successRate = candidate.successRate,
                        circuitOpen = candidate.id in open,
                        costPerReliability = costPerReliability(candidate),
                    )
                }
            return AgentRoutingModelInput(
                eligibleInRoutingOrder = routing.eligible.map { it.id },
                requiredCapabilities = input.requiredCapabilities,
                requiredContextTokens = input.requiredContextTokens,
                requiredContextType = input.requiredContextType,
                candidates = candidates,
                circuitOpen = routing.open.map { it.id }.filterNot { it in eligible },
                halfOpen = routing.halfOpen,
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
    val consecutiveFailures: Int = 0,
    val successRate: Double? = null,
    val circuitOpen: Boolean = false,
    val costPerReliability: Double = 0.0,
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
    /** Each list rarest first when [termDocumentFrequency] is known: query in this order. */
    val knownEntities: List<String>,
    val knownActions: List<String>,
    val codeSymbols: List<String>,
    val chronologicalContextRequired: Boolean,
    val alreadyRetrievedEvidenceCount: Int,
    val maxQueries: Int,
    /** Memories per word, for the input's own words only. */
    val termDocumentFrequency: Map<String, Int> = emptyMap(),
    val feedbackTerms: List<String> = emptyList(),
    /** The second-pass query's text (RESULT_FEEDBACK, Phrase), or null when there is none. */
    val feedbackQuery: String? = null,
    /** Each known action's synonyms: copy as that action query's expansionTerms. */
    val actionSynonyms: Map<String, List<String>> = emptyMap(),
) {
    fun toInput(): MemoryQueryInput = MemoryQueryInput(
        objective, knownEntities, knownActions, codeSymbols, chronologicalContextRequired, alreadyRetrievedEvidenceCount, maxQueries,
        termDocumentFrequency, feedbackTerms,
    )

    companion object {
        fun of(input: MemoryQueryInput): MemoryQueryModelInput {
            fun clean(values: List<String>) =
                rarestFirst(values.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase), input.termDocumentFrequency)
            val words = (listOf(input.objective) + input.knownEntities + input.knownActions + input.codeSymbols + input.feedbackTerms)
                .flatMap(::queryWords)
                .toSet()
            return MemoryQueryModelInput(
                objective = input.objective.trim(),
                knownEntities = clean(input.knownEntities),
                knownActions = clean(input.knownActions),
                codeSymbols = clean(input.codeSymbols),
                chronologicalContextRequired = input.chronologicalContextRequired,
                alreadyRetrievedEvidenceCount = input.alreadyRetrievedEvidenceCount,
                maxQueries = input.maxQueries,
                termDocumentFrequency = input.termDocumentFrequency.filterKeys(words::contains),
                feedbackTerms = input.feedbackTerms,
                feedbackQuery = feedbackQueryText(input),
                actionSynonyms = clean(input.knownActions).associateWith(::actionSynonyms).filterValues { it.isNotEmpty() },
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
    /** Evidence without its text; a detected near-repeat carries [ContextEvidence.repeatOf]. */
    val evidence: List<ContextEvidence>,
    /**
     * Groups in rank order (required first, then priority tiers). A group is kept when
     * [ContextGroupFacts.fits]; [ContextGroupFacts.tokensUsedAfter] is the running total after it.
     */
    val groups: List<ContextGroupFacts>,
    /** The kept evidence ids in prompt order: copy as selectedEvidenceIds. */
    val promptOrder: List<String> = emptyList(),
) {
    fun toInput(): ContextPackingInput = ContextPackingInput(tokenBudget, evidence)

    companion object {
        fun of(input: ContextPackingInput): ContextPackingModelInput {
            val packing = contextPacking(input)
            return ContextPackingModelInput(
                tokenBudget = input.tokenBudget,
                evidence = input.evidence.map { it.copy(text = null, repeatOf = packing.repeatOf[it.id] ?: it.repeatOf) },
                groups = packing.groups.map { group ->
                    ContextGroupFacts(
                        evidenceIds = group.evidenceIds,
                        tokens = group.tokens,
                        required = group.required,
                        fits = group.selected,
                        tokensUsedAfter = group.tokensUsedAfter,
                        repeatOf = group.repeatOf,
                    )
                },
                promptOrder = packing.promptOrder,
            )
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
    /** The id this group's single item nearly repeats; it is omitted. */
    val repeatOf: String? = null,
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

/**
 * The handoff input already cleaned as the packet carries it: copy every field. When [fitted] is
 * present the packet was over [maxChars]: copy [fitted]'s lists instead (sections already cut).
 */
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
    val sources: Map<String, String> = emptyMap(),
    val maxChars: Int? = null,
    val fitted: HandoffPacket? = null,
) {
    fun toInput(): HandoffInput =
        HandoffInput(objective, completed, artifacts, state, unresolved, failures, nextAction, acceptanceCriteria, provenance, sources, maxChars)

    companion object {
        fun of(input: HandoffInput): HandoffModelInput {
            val packet = DeterministicLocalOrchestrationUtilities.composeHandoff(input)
            return HandoffModelInput(
                objective = input.objective.trim(),
                completed = input.completed.distinct(),
                artifacts = input.artifacts.distinct(),
                state = input.state,
                unresolved = input.unresolved.distinct(),
                failures = input.failures.distinct(),
                nextAction = input.nextAction?.trim()?.takeIf(String::isNotEmpty),
                acceptanceCriteria = input.acceptanceCriteria.distinct(),
                provenance = input.provenance.distinct(),
                sources = input.sources,
                maxChars = input.maxChars,
                fitted = packet.takeIf { it.cuts.isNotEmpty() },
            )
        }
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
    val changedFiles: List<String> = emptyList(),
    /** Tests picked from [changedFiles] (one CHANGED_FILES test step with these targets). */
    val testTargets: List<String> = emptyList(),
) {
    fun toInput(): VerificationPlanningInput =
        VerificationPlanningInput(objective, acceptanceCriteria, artifactKinds, targetPlatforms, changedFiles)

    companion object {
        fun of(input: VerificationPlanningInput): VerificationPlanningModelInput {
            val criteria = input.acceptanceCriteria.filter(String::isNotBlank).distinct()
            return VerificationPlanningModelInput(
                objective = input.objective,
                acceptanceCriteria = criteria,
                criterionOperations = criteria.flatMap { c -> verificationOperationsFor(c).map { CriterionOperation(c, it) } },
                artifactKinds = input.artifactKinds,
                targetPlatforms = input.targetPlatforms.distinct(),
                changedFiles = input.changedFiles,
                testTargets = testTargetsFor(input.changedFiles),
            )
        }
    }
}

@Serializable
internal data class CriterionOperation(val criterion: String, val operationClass: String)
