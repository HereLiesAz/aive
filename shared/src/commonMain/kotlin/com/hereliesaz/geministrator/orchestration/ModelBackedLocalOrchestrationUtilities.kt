package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Minimal synchronous local-inference boundary for tiny orchestration specialists.
 *
 * Implementations are expected to execute locally. Returning null means the requested specialist is
 * unavailable and the guarded family falls back to the deterministic oracle for that one decision.
 */
fun interface LocalOrchestrationSpecialistRuntime {
    fun infer(role: OrchestrationUtilityRole, inputJson: String): String?
}

@Serializable
private data class ExecutionStateModelInput(
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
)

/**
 * Model-backed implementation of the nine local orchestration utilities.
 *
 * Every specialist output is decoded into the existing typed contract and checked against runtime
 * inputs plus the deterministic baseline. Invalid, fabricated, or less-conservative answers are
 * discarded per-call; model availability can therefore improve control decisions without becoming
 * a workflow-correctness dependency.
 */
class GuardedModelBackedOrchestrationUtilities(
    private val runtime: LocalOrchestrationSpecialistRuntime,
    private val fallback: LocalOrchestrationUtilityFamily = DeterministicLocalOrchestrationUtilities,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) : LocalOrchestrationUtilityFamily {
    override fun composeMemoryQueries(input: MemoryQueryInput): MemoryQueryPlan {
        val baseline = fallback.composeMemoryQueries(input)
        return infer<MemoryQueryInput, MemoryQueryPlan>(
            OrchestrationUtilityRole.MemoryQueryComposer,
            input,
            baseline,
        ) { candidate ->
            candidate.queries.size <= input.maxQueries &&
                candidate.queries.all { it.text.isNotBlank() && it.reasonCode.isNotBlank() } &&
                (!candidate.enoughEvidence || baseline.enoughEvidence) &&
                (baseline.enoughEvidence || candidate.queries.isNotEmpty())
        }
    }

    override fun packContext(input: ContextPackingInput): ContextPackingPlan {
        val baseline = fallback.packContext(input)
        val byId = input.evidence.associateBy(ContextEvidence::id)
        return infer<ContextPackingInput, ContextPackingPlan>(
            OrchestrationUtilityRole.ContextPacker,
            input,
            baseline,
        ) { candidate ->
            val selected = candidate.selectedEvidenceIds
            val selectedSet = selected.toSet()
            val known = byId.keys
            val omittedExpected = input.evidence.map { it.id }.filterNot(selectedSet::contains)
            val recomputedTokens = selected.sumOf { byId[it]?.estimatedTokens ?: Int.MAX_VALUE }
            val requiredIncluded = input.evidence.filter(ContextEvidence::required).all { it.id in selectedSet }
            val groupsAtomic = input.evidence
                .filter { it.conflictGroup != null }
                .groupBy(ContextEvidence::conflictGroup)
                .values
                .all { group ->
                    val count = group.count { it.id in selectedSet }
                    count == 0 || count == group.size
                }
            selected.size == selectedSet.size &&
                selectedSet.all(known::contains) &&
                candidate.omittedEvidenceIds == omittedExpected &&
                candidate.totalEstimatedTokens == recomputedTokens &&
                recomputedTokens <= input.tokenBudget &&
                requiredIncluded &&
                groupsAtomic
        }
    }

    override fun routeAgent(input: AgentRoutingInput): AgentRoute {
        val baseline = fallback.routeAgent(input)
        val candidates = input.candidates.associateBy(AgentRouteCandidate::id)
        return infer<AgentRoutingInput, AgentRoute>(
            OrchestrationUtilityRole.AgentRouter,
            input,
            baseline,
        ) { candidate ->
            if (candidate.decision == AgentRouteDecision.Escalate) {
                true
            } else {
                val selected = candidate.selectedAgent?.let(candidates::get)
                selected != null &&
                    selected.available &&
                    selected.contextLimitTokens >= input.requiredContextTokens &&
                    selected.capabilities.containsAll(input.requiredCapabilities) &&
                    candidate.fallbackAgent?.let { fallbackId ->
                        candidates[fallbackId]?.let { fallbackCandidate ->
                            fallbackCandidate.available &&
                                fallbackCandidate.contextLimitTokens >= input.requiredContextTokens &&
                                fallbackCandidate.capabilities.containsAll(input.requiredCapabilities)
                        } ?: false
                    } != false &&
                    !(baseline.decision == AgentRouteDecision.Escalate)
            }
        }
    }

    override fun routeTool(input: ToolRoutingInput): ToolRoute {
        val baseline = fallback.routeTool(input)
        val capabilities = input.capabilities.associateBy(ToolCapability::id)
        return infer<ToolRoutingInput, ToolRoute>(
            OrchestrationUtilityRole.ToolRouter,
            input,
            baseline,
        ) { candidate ->
            if (candidate.decision != ToolRouteDecision.Tool) {
                candidate.decision == baseline.decision
            } else {
                val operation = input.operationClass?.trim().orEmpty()
                val selected = candidate.tool?.let(capabilities::get)
                baseline.decision == ToolRouteDecision.Tool &&
                    selected != null &&
                    selected.available &&
                    operation.isNotEmpty() &&
                    operation in selected.operationClasses &&
                    candidate.operationClass == operation &&
                    candidate.requiredInputs.all(input.requiredInputs.toSet()::contains)
            }
        }
    }

    override fun composeHandoff(input: HandoffInput): HandoffPacket {
        val baseline = fallback.composeHandoff(input)
        return infer<HandoffInput, HandoffPacket>(
            OrchestrationUtilityRole.HandoffComposer,
            input,
            baseline,
        ) { candidate ->
            candidate.objective == baseline.objective &&
                candidate.completed.toSet() == baseline.completed.toSet() &&
                candidate.artifacts.toSet() == baseline.artifacts.toSet() &&
                candidate.state == baseline.state &&
                candidate.unresolved.toSet() == baseline.unresolved.toSet() &&
                candidate.failures.toSet() == baseline.failures.toSet() &&
                candidate.nextAction == baseline.nextAction &&
                candidate.acceptanceCriteria.toSet() == baseline.acceptanceCriteria.toSet() &&
                candidate.provenance.toSet() == baseline.provenance.toSet()
        }
    }

    override fun evaluateEscalation(input: CapabilityAssessment): EscalationResult {
        val baseline = fallback.evaluateEscalation(input)
        return infer<CapabilityAssessment, EscalationResult>(
            OrchestrationUtilityRole.EscalationGate,
            input,
            baseline,
        ) { candidate ->
            candidate.recommendedTier.isNotBlank() &&
                candidate.reasonCodes.containsAll(baseline.reasonCodes) &&
                escalationIsAtLeastAsConservative(candidate.decision, baseline.decision)
        }
    }

    override fun evaluateCompletion(input: CompletionInput): CompletionResult {
        val baseline = fallback.evaluateCompletion(input)
        val knownEvidence = baseline.evidenceIds.toSet()
        return infer<CompletionInput, CompletionResult>(
            OrchestrationUtilityRole.CompletionGate,
            input,
            baseline,
        ) { candidate ->
            candidate.evidenceIds.all(knownEvidence::contains) &&
                (baseline.decision == CompletionDecision.Complete ||
                    candidate.decision != CompletionDecision.Complete) &&
                (baseline.unsatisfiedCriteria.isEmpty() ||
                    candidate.unsatisfiedCriteria.containsAll(baseline.unsatisfiedCriteria))
        }
    }

    override fun summarizeExecution(
        definition: WorkflowDefinition,
        run: WorkflowRun,
    ): ExecutionStateSummary {
        val baseline = fallback.summarizeExecution(definition, run)
        return infer<ExecutionStateModelInput, ExecutionStateSummary>(
            OrchestrationUtilityRole.ExecutionStateSummarizer,
            ExecutionStateModelInput(definition, run),
            baseline,
        ) { candidate ->
            candidate.completedSteps.toSet() == baseline.completedSteps.toSet() &&
                candidate.activeSteps.toSet() == baseline.activeSteps.toSet() &&
                candidate.blockedSteps.toSet() == baseline.blockedSteps.toSet() &&
                candidate.failedSteps.toSet() == baseline.failedSteps.toSet() &&
                candidate.waitingForApprovalSteps.toSet() == baseline.waitingForApprovalSteps.toSet() &&
                candidate.artifacts.toSet() == baseline.artifacts.toSet() &&
                candidate.knownConstraints.toSet() == baseline.knownConstraints.toSet() &&
                candidate.openQuestions.toSet() == baseline.openQuestions.toSet()
        }
    }

    override fun planVerification(input: VerificationPlanningInput): VerificationPlan {
        val baseline = fallback.planVerification(input)
        val baselineKeys = baseline.steps.mapTo(linkedSetOf()) { stepKey(it) }
        val criteria = input.acceptanceCriteria.toSet()
        return infer<VerificationPlanningInput, VerificationPlan>(
            OrchestrationUtilityRole.VerificationPlanner,
            input,
            baseline,
        ) { candidate ->
            val candidateKeys = candidate.steps.map { stepKey(it) }
            candidate.steps.all { step ->
                step.id.isNotBlank() &&
                    step.operationClass.isNotBlank() &&
                    step.reasonCode.isNotBlank() &&
                    (step.criterion == null || step.criterion in criteria)
            } &&
                candidateKeys.toSet().containsAll(baselineKeys) &&
                candidateKeys.size == candidateKeys.distinct().size
        }
    }

    private inline fun <reified I, reified O> infer(
        role: OrchestrationUtilityRole,
        input: I,
        fallbackValue: O,
        crossinline validate: (O) -> Boolean,
    ): O {
        val raw = runCatching {
            runtime.infer(role, json.encodeToString(input))
        }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return fallbackValue

        val candidate = runCatching {
            json.decodeFromString<O>(raw)
        }.getOrNull() ?: return fallbackValue
        return if (runCatching { validate(candidate) }.getOrDefault(false)) candidate else fallbackValue
    }

    private fun escalationIsAtLeastAsConservative(
        candidate: EscalationDecision,
        baseline: EscalationDecision,
    ): Boolean = when (baseline) {
        EscalationDecision.Local -> true
        EscalationDecision.NeedMoreContext ->
            candidate == EscalationDecision.NeedMoreContext || candidate == EscalationDecision.Escalate
        EscalationDecision.NeedTool ->
            candidate == EscalationDecision.NeedTool || candidate == EscalationDecision.Escalate
        EscalationDecision.Escalate -> candidate == EscalationDecision.Escalate
    }

    private fun stepKey(step: VerificationStep): String =
        "${step.operationClass}|${step.criterion.orEmpty()}"
}
