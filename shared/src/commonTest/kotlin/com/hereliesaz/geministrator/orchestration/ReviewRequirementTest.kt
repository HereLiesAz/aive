package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.aiveBuiltInRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewRequirementTest {
    private fun inputs(goal: String, review: Boolean = false, external: Boolean = false) =
        RequirementInputs(goal, external, aiveBuiltInRoles, aiveBuiltInRoles.map { it.id.value }.toSet(), review)

    private fun step(id: String, template: String?, deps: List<String> = emptyList()) =
        OrchestrationPlanStep(id, id, "o", "", deps, templateId = template, templateInputs = emptyMap())

    @Test
    fun theKeeperCanAddItAtTheStartAndOtherwiseTheQueenDecides() {
        assertFalse(Requirements.extract(inputs("Write a brief.")).requiresReview)
        assertTrue(Requirements.extract(inputs("Write a brief.", review = true)).requiresReview)
        assertTrue(Requirements.extract(inputs("Write a brief and it must be reviewed.")).requiresReview)
        assertFalse(Requirements.extract(inputs("Write a brief and it must be reviewed.", external = true)).requiresReview)
    }

    @Test
    fun aReviewableStepNeedsACheckOfTheWorkAfterIt() {
        val goal = inputs("Write a brief.", review = true)
        val checklist = Requirements.extract(goal)
        fun verdict(plan: OrchestrationPlan) =
            Requirements.checkPure(goal, plan, checklist).single { it.id == Requirements.REVIEW_ID }.verdict
        assertEquals(RequirementVerdict.Unmet, verdict(OrchestrationPlan(listOf(step("a", "brief")))))
        assertEquals(
            RequirementVerdict.Met,
            verdict(OrchestrationPlan(listOf(step("a", "brief"), step("b", "check-work", listOf("a"))))),
        )
        assertEquals(RequirementVerdict.Met, verdict(OrchestrationPlan(listOf(step("a", "clarifying-questions")))))
    }
}
