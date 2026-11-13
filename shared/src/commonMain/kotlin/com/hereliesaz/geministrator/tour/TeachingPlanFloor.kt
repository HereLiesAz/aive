package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.orchestration.OrchestrationPlan

/** The id prefix of a run started by the teaching launch; the queen's Messages reads it to word a failure as a lesson's. */
const val TEACHING_WORKFLOW_ID_PREFIX: String = "tutorial"

/**
 * The deterministic floor under the queen's planner for a teaching goal (no model, no network). It checks the SHAPE of a
 * plan, never its content: how many steps, how long each objective is, that they form one chain, that nothing asks for an
 * approval, that only the one generic role is used and that no retired word is in them. What to teach, in what order and how
 * many steps is the planner's choice on every run; this only turns back a plan that cannot be walked one small step at a time.
 */
object TeachingPlanFloor {
    /** A goal that names the whole app (or no part of it) wants a long walk; a focused one a short one. */
    private val broadWords = Regex("\\b(whole|entire|everything|all of|the app|aive|basics|getting started|the ropes)\\b", RegexOption.IGNORE_CASE)

    private val retiredWords = listOf("provider", "inbox", "timeline", "hall monitor", "drifter")

    /** Said to the planner with the keeper's goal: the form of the plan, not its topics. */
    const val PLANNER_INSTRUCTION: String =
        "The keeper is new to agent swarms and wants to be taught this goal by being guided through the app itself. " +
            "Plan the walk as one chain of very small steps, each depending on exactly the one before it. " +
            "Each step teaches one control and checks one thing the keeper does. Choose the number, the topics and the order yourself, " +
            "from the goal. Use only the role worker, ask for no approvals, and name controls by the words the app shows."

    fun isBroad(goal: String): Boolean = broadWords.containsMatchIn(goal)

    fun check(plan: OrchestrationPlan, teacherRoleId: String, broadGoal: Boolean): List<Violation> = buildList {
        val steps = plan.steps
        val min = if (broadGoal) TeachingLimits.BROAD_GOAL_MIN_STEPS else TeachingLimits.FOCUSED_MIN_STEPS
        val max = if (broadGoal) TeachingLimits.MAX_STEPS else TeachingLimits.FOCUSED_MAX_STEPS
        if (steps.size < min) {
            add(Violation("T1", "Too coarse: ${steps.size} steps for this goal; split steps that teach more than one control (at least $min)."))
        }
        if (steps.size > max) add(Violation("T1", "Too many steps: ${steps.size}; use at most $max."))
        steps.forEachIndexed { index, step ->
            val words = step.objective.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            if (words > TeachingLimits.STEP_OBJECTIVE_MAX_WORDS) {
                add(Violation("T4", "Step ${step.id} covers too much: $words words; use at most ${TeachingLimits.STEP_OBJECTIVE_MAX_WORDS}."))
            }
            val expected = if (index == 0) emptyList() else listOf(steps[index - 1].id)
            if (step.dependsOn != expected) {
                add(Violation("T5", "Step ${step.id} must depend on exactly the step before it."))
            }
            if (step.requiresHumanApproval) add(Violation("T7", "Step ${step.id} asks for an approval; a lesson never does."))
            if (step.roleId != teacherRoleId) add(Violation("T8", "Step ${step.id} must use only role $teacherRoleId."))
            val text = (step.name + " " + step.objective).lowercase()
            retiredWords.firstOrNull { Regex("\\b" + Regex.escape(it) + "s?\\b").containsMatchIn(text) }?.let {
                add(Violation("T10", "Step ${step.id} uses a retired word: $it."))
            }
        }
    }
}
