package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.BuiltInRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequirementsTest {
    private val roles = BuiltInRoles.all
    private fun inputs(goal: String, external: Boolean = false) =
        RequirementInputs(goal, external, roles, roles.map { it.id.value }.toSet())
    private fun step(id: String, role: String, deps: List<String> = emptyList(), approval: Boolean = false, name: String = "Step $id") =
        OrchestrationPlanStep(id, name, "Do $id", role, deps, approval)
    private val engineer = BuiltInRoles.ImplementationEngineer.id.value
    private val researcher = BuiltInRoles.Researcher.id.value

    private fun unmet(goal: String, plan: OrchestrationPlan, external: Boolean = false): List<String> {
        val i = inputs(goal, external)
        return Requirements.checkPure(i, plan, Requirements.extract(i)).filter { it.verdict == RequirementVerdict.Unmet }.map { it.id }
    }

    @Test
    fun extractsExplicitGoalClausesVerbatimAndNeverInventsOne() {
        val list = Requirements.extract(inputs("Build a page. It must load fast. Be nice.")).items
        assertEquals("It must load fast", list.single { it.source == RequirementSource.Goal }.quote)
        assertTrue(Requirements.extract(inputs("Build a page.")).items.none { it.source == RequirementSource.Goal })
    }

    @Test
    fun sameInputsGiveTheSameChecklistAndJudgedItemsAreCapped() {
        assertEquals(Requirements.extract(inputs("It must a. It should b.")), Requirements.extract(inputs("It must a. It should b.")))
        val many = (1..30).joinToString(". ") { "It must do $it" }
        assertTrue(Requirements.extract(inputs(many)).judged.size <= Requirements.MAX_GOAL_CLAUSES)
    }

    @Test
    fun aNumericLimitBecomesAPureRule() {
        val plan = OrchestrationPlan((1..3).map { step("s$it", researcher) })
        assertEquals(listOf("R-structure"), unmet("Use at most 2 steps", plan))
        assertTrue(unmet("Use at most 5 steps", plan).isEmpty())
    }

    @Test
    fun aMutatingStepWithoutApprovalIsUnmetAndAnApprovalAncestorFixesIt() {
        assertEquals(listOf("R-gates"), unmet("Go", OrchestrationPlan(listOf(step("a", engineer)))))
        assertTrue(unmet("Go", OrchestrationPlan(listOf(step("a", researcher, approval = true), step("b", engineer, listOf("a"))))).isEmpty())
    }

    @Test
    fun anExternalGoalWithAMutatingStepIsUnmetTwice() {
        assertTrue("R-outside" in unmet("Go", OrchestrationPlan(listOf(step("a", engineer))), external = true))
    }

    @Test
    fun aRetiredWordOrALongNameOrAnUnknownRoleIsUnmet() {
        assertEquals(listOf("R-words"), unmet("Go", OrchestrationPlan(listOf(step("a", researcher, name = "Check the inbox")))))
        assertEquals(listOf("R-words"), unmet("Go", OrchestrationPlan(listOf(step("a", researcher, name = "one two three four five")))))
        assertEquals(listOf("R-roles"), unmet("Go", OrchestrationPlan(listOf(step("a", "nobody")))))
    }

    @Test
    fun aModelYesNeverOverrulesAnUnmetResult() {
        val unmetResult = RequirementResult("R-1", RequirementVerdict.Unmet, "x", byCode = false)
        assertFalse(Requirements.approves(listOf(unmetResult)))
        assertTrue(Requirements.approves(listOf(RequirementResult("R-1", RequirementVerdict.CannotTell, "x", false))))
    }

    @Test
    fun theReplyParserKeepsKnownLinesCountsMalformedOnesAndBoundsSuggestions() {
        val list = Requirements.extract(inputs("It must be short."))
        val reply = ReviewReplyParser.parse(
            """
            REQ | R-goal-1 | UNMET | s1 | too long
            REQ | R-nope | MET | s1 | dropped
            REQ | R-goal-1 | MAYBE | s1 | bad
            SUGGEST | AddCheck | Add a check after step 2 http://x.y | it catches slips | s2
            SUGGEST | Bogus | nothing | nothing | -
            SUGGEST | Narrow | a | b | -
            SUGGEST | Other | c | d | -
            SUGGEST | Other | e | f | -
            """.trimIndent(),
            list,
        )
        assertEquals(listOf("R-goal-1"), reply.results.map { it.id })
        assertEquals(1, reply.malformed)
        assertEquals(3, reply.suggestions.size)
        assertFalse(reply.suggestions.first().text.contains("http"))
        assertEquals("s2", reply.suggestions.first().stepId)
    }
}
