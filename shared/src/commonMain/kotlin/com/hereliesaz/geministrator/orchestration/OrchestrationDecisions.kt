package com.hereliesaz.geministrator.orchestration

/**
 * The judgement calls inside the orchestration utilities, asked as typed questions.
 *
 * Everything else the utilities do (routing to the first eligible id, packing by precomputed fits,
 * bucketing task statuses, copying evidence forward) is exact code in
 * [DeterministicLocalOrchestrationUtilities]. What code cannot read from text is asked here: one
 * small encoder answers every question, each through its own head, in one forward pass per text.
 *
 * [options] order is the model's output order; the first option is the conservative default.
 */
enum class OrchestrationQuestion(val id: String, val options: List<String>, val reads: DecisionText) {
    /** The objective names no concrete target or outcome ("make it better", "fix the thing"). */
    AmbiguousObjective("ambiguous-objective", listOf("no", "yes"), DecisionText.Objective),
    /** The objective asks to choose or change structure: modules, storage, protocols, frameworks. */
    ArchitecturalDecision("architectural-decision", listOf("no", "yes"), DecisionText.Objective),
    /** The objective needs several dependent steps rather than one change. */
    MultiStepReasoning("multi-step-reasoning", listOf("no", "yes"), DecisionText.Objective),
    /** The objective is about order, history or change over time: memory needs dated evidence. */
    ChronologicalContext("chronological-context", listOf("no", "yes"), DecisionText.Objective),
    /** The check one part of an acceptance criterion calls for. */
    VerificationOperation(
        "verification-operation",
        listOf("evidence-check", "lint", "test", "build", "health-check", "source-verification"),
        DecisionText.CriterionPart,
    ),
    ;

    companion object {
        fun byId(id: String): OrchestrationQuestion? = entries.firstOrNull { it.id == id }
    }
}

/** What text a question reads. */
enum class DecisionText { Objective, CriterionPart }

/**
 * A decision model: calibrated probabilities over [OrchestrationQuestion.options] for each question
 * asked of one text, or null when no model is installed or it failed. Implementations batch the
 * questions that share a text into one forward pass.
 */
fun interface OrchestrationDecisionModel {
    fun answer(text: String, questions: List<OrchestrationQuestion>): Map<OrchestrationQuestion, List<Double>>?

    /**
     * The confidence this model's answers to [question] need, set per question when the model was
     * gated (`config.json`), or null for the caller's default. A question the model was not released
     * for is simply not answered.
     */
    fun minConfidence(question: OrchestrationQuestion): Double? = null
}

/**
 * The deterministic utilities with their judgement inputs filled by an [OrchestrationDecisionModel].
 *
 * A model answer is used only when its top option reaches [minConfidence], or the higher threshold
 * the model was released with for that question; otherwise the caller's heuristic stands. Escalation flags only ever turn on (a model can make the gate more conservative,
 * never less), and memory queries only ever gain the chronological pass.
 */
class DecisionInformedOrchestrationUtilities(
    private val model: OrchestrationDecisionModel,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val base: DeterministicLocalOrchestrationUtilities = DeterministicLocalOrchestrationUtilities,
) : LocalOrchestrationUtilityFamily by base {

    override fun evaluateEscalation(input: CapabilityAssessment): EscalationResult {
        val objective = input.objective.trim()
        val fromObjective = if (objective.isEmpty()) emptyMap() else confident(
            objective,
            listOf(
                OrchestrationQuestion.AmbiguousObjective,
                OrchestrationQuestion.ArchitecturalDecision,
                OrchestrationQuestion.MultiStepReasoning,
            ),
        )
        fun yes(question: OrchestrationQuestion) = fromObjective[question] == "yes"
        return base.evaluateEscalation(
            input.copy(
                ambiguousObjective = input.ambiguousObjective || yes(OrchestrationQuestion.AmbiguousObjective),
                requiresArchitecturalDecision = input.requiresArchitecturalDecision || yes(OrchestrationQuestion.ArchitecturalDecision),
                requiresMultiStepReasoning = input.requiresMultiStepReasoning || yes(OrchestrationQuestion.MultiStepReasoning),
            ),
        )
    }

    override fun composeMemoryQueries(input: MemoryQueryInput): MemoryQueryPlan {
        if (input.chronologicalContextRequired || input.objective.isBlank()) return base.composeMemoryQueries(input)
        val chronological = confident(input.objective.trim(), listOf(OrchestrationQuestion.ChronologicalContext))
        return base.composeMemoryQueries(
            input.copy(chronologicalContextRequired = chronological[OrchestrationQuestion.ChronologicalContext] == "yes"),
        )
    }

    override fun planVerification(input: VerificationPlanningInput): VerificationPlan =
        base.planVerificationWith(input, ::operationsFor)

    /** Per part: the model's operation when confident, else the lexicon's; Given/When/Then is a test. */
    private fun operationsFor(criterion: String): List<String> {
        if (BEHAVIOUR_SPEC.containsMatchIn(criterion)) return listOf("test")
        val operations = CRITERION_PARTS.split(criterion).map(String::trim).filter(String::isNotEmpty).mapNotNull { part ->
            when (val answer = confident(part, listOf(OrchestrationQuestion.VerificationOperation))[OrchestrationQuestion.VerificationOperation]) {
                null -> lexiconOperationFor(part)
                "evidence-check" -> null  // confidently no named check; evidence-check stands only if no part has one
                else -> answer
            }
        }.distinct()
        return operations.ifEmpty { listOf("evidence-check") }
    }

    /** The confident answer to each question, by option name; unconfident or failed answers are left out. */
    private fun confident(text: String, questions: List<OrchestrationQuestion>): Map<OrchestrationQuestion, String> {
        val answers = runCatching { model.answer(text, questions) }.getOrNull() ?: return emptyMap()
        return questions.mapNotNull { question ->
            val probabilities = answers[question]?.takeIf { it.size == question.options.size } ?: return@mapNotNull null
            val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: return@mapNotNull null
            val threshold = maxOf(minConfidence, runCatching { model.minConfidence(question) }.getOrNull() ?: minConfidence)
            if (probabilities[best] >= threshold) question to question.options[best] else null
        }.toMap()
    }

    companion object {
        const val DEFAULT_MIN_CONFIDENCE: Double = 0.8
    }
}
