package com.hereliesaz.geministrator.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionInformedOrchestrationUtilitiesTest {
    /** Answers [answers] (question -> option) with [confidence]; every other question gets nothing. */
    private class FakeModel(
        private val answers: Map<Pair<String, OrchestrationQuestion>, String>,
        private val confidence: Double = 0.95,
    ) : OrchestrationDecisionModel {
        val asked = mutableListOf<Pair<String, OrchestrationQuestion>>()
        override fun answer(text: String, questions: List<OrchestrationQuestion>): Map<OrchestrationQuestion, List<Double>> =
            questions.mapNotNull { question ->
                asked += text to question
                val option = answers[text to question] ?: return@mapNotNull null
                val rest = (1.0 - confidence) / (question.options.size - 1)
                question to question.options.map { if (it == option) confidence else rest }
            }.toMap()
    }

    private val base = DeterministicLocalOrchestrationUtilities

    @Test
    fun confidentArchitecturalAnswerEscalates() {
        val objective = "Decide whether to use Room or SQLDelight"
        val utilities = DecisionInformedOrchestrationUtilities(
            FakeModel(mapOf((objective to OrchestrationQuestion.ArchitecturalDecision) to "yes")),
        )
        val input = CapabilityAssessment(objective = objective)
        assertEquals(EscalationDecision.Local, base.evaluateEscalation(input).decision)
        val result = utilities.evaluateEscalation(input)
        assertEquals(EscalationDecision.Escalate, result.decision)
        assertTrue("ARCHITECTURAL_DECISION" in result.reasonCodes)
    }

    @Test
    fun unconfidentAnswersLeaveTheHeuristicAlone() {
        val objective = "Decide whether to use Room or SQLDelight"
        val utilities = DecisionInformedOrchestrationUtilities(
            FakeModel(mapOf((objective to OrchestrationQuestion.ArchitecturalDecision) to "yes"), confidence = 0.6),
        )
        val input = CapabilityAssessment(objective = objective)
        assertEquals(base.evaluateEscalation(input), utilities.evaluateEscalation(input))
    }

    @Test
    fun modelNeverClearsAFlagTheCallerSet() {
        val objective = "Fix the typo in the title"
        val utilities = DecisionInformedOrchestrationUtilities(
            FakeModel(mapOf((objective to OrchestrationQuestion.MultiStepReasoning) to "no")),
        )
        val input = CapabilityAssessment(objective = objective, requiresMultiStepReasoning = true)
        assertTrue("MULTI_STEP_REASONING" in utilities.evaluateEscalation(input).reasonCodes)
    }

    @Test
    fun contradictionIsAskedOfTheJoinedCriteria() {
        val criteria = listOf("Data never leaves the device", "Data is synced to the cloud")
        val joined = criteria.joinToString(CRITERIA_SEPARATOR)
        val utilities = DecisionInformedOrchestrationUtilities(
            FakeModel(mapOf((joined to OrchestrationQuestion.ContradictoryCriteria) to "yes")),
        )
        val result = utilities.evaluateEscalation(CapabilityAssessment(acceptanceCriteria = criteria))
        assertTrue("CONTRADICTION_RECONCILIATION" in result.reasonCodes)
        assertEquals(EscalationDecision.Escalate, result.decision)
    }

    @Test
    fun verificationUsesTheModelPerPartAndTheLexiconWhenUnsure() {
        val criterion = "the checker is quiet and unit tests pass"
        val model = FakeModel(mapOf(("the checker is quiet" to OrchestrationQuestion.VerificationOperation) to "lint"))
        val plan = DecisionInformedOrchestrationUtilities(model)
            .planVerification(VerificationPlanningInput(objective = "x", acceptanceCriteria = listOf(criterion)))
        assertEquals(listOf("lint", "test"), plan.steps.map { it.operationClass })
        // The lexicon alone finds only the test.
        val lexicon = base.planVerification(VerificationPlanningInput(objective = "x", acceptanceCriteria = listOf(criterion)))
        assertEquals(listOf("test"), lexicon.steps.map { it.operationClass })
    }

    @Test
    fun confidentEvidenceCheckOverridesALexiconFalsePositive() {
        val criterion = "the style guide page is linked"
        val model = FakeModel(mapOf((criterion to OrchestrationQuestion.VerificationOperation) to "evidence-check"))
        val plan = DecisionInformedOrchestrationUtilities(model)
            .planVerification(VerificationPlanningInput(objective = "x", acceptanceCriteria = listOf(criterion)))
        assertEquals(listOf("evidence-check"), plan.steps.map { it.operationClass })
    }

    @Test
    fun chronologicalAnswerAddsTheDatedQuery() {
        val objective = "What changed in the sync engine since Monday?"
        val utilities = DecisionInformedOrchestrationUtilities(
            FakeModel(mapOf((objective to OrchestrationQuestion.ChronologicalContext) to "yes")),
        )
        val input = MemoryQueryInput(objective = objective)
        assertTrue(utilities.composeMemoryQueries(input).queries.any { it.chronological })
        assertTrue(base.composeMemoryQueries(input).queries.none { it.chronological })
    }

    @Test
    fun aFailingModelFallsBackToTheBaseline() {
        val utilities = DecisionInformedOrchestrationUtilities(OrchestrationDecisionModel { _, _ -> error("model crashed") })
        val input = CapabilityAssessment(objective = "Decide whether to use Room or SQLDelight")
        assertEquals(base.evaluateEscalation(input), utilities.evaluateEscalation(input))
    }
}
