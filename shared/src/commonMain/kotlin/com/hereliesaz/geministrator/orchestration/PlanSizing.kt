package com.hereliesaz.geministrator.orchestration

/*
 * One creature or a swarm (docs/Orchestration_layer.md, Sizing the plan). The queen decides how many creatures a goal
 * needs and the keeper approves or overrides it on the plan approval card. Everything here is a pure function of the
 * goal text and the plan: it makes no model call.
 */

/** What the keeper may ask for instead of the plan the queen proposed. */
enum class PlanSizeChoice {
    One,
    Swarm,
    ;

    val opposite: PlanSizeChoice get() = if (this == One) Swarm else One
}

/** The one-line reason a swarm needs more than one creature, in the order the queen prefers to give it. */
enum class SwarmReason { NeedsApproval, DifferentKinds, SideBySide, Separate }

/** How big a plan is, as the approval card says it. */
data class PlanSize(
    val creatures: Int,
    /** Why more than one; null for a single creature. */
    val reason: SwarmReason?,
    /** The plan has more creatures than the goal's length justifies (see [PlanSizing.bigForSmallGoal]). */
    val bigForSmallGoal: Boolean,
) {
    val single: Boolean get() = creatures <= 1
    val choice: PlanSizeChoice get() = if (single) PlanSizeChoice.One else PlanSizeChoice.Swarm
}

object PlanSizing {
    /** A goal of fewer words than this is a small goal. */
    const val SMALL_GOAL_WORDS = 12

    /** The most creatures a small goal justifies; a plan with more is flagged, never silently started. */
    const val SMALL_GOAL_MAX_CREATURES = 3

    fun goalWords(objective: String): Int = objective.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** True when [creatures] is more than a goal of this length justifies: a goal under [SMALL_GOAL_WORDS] words and more than [SMALL_GOAL_MAX_CREATURES] creatures. */
    fun bigForSmallGoal(objective: String, creatures: Int): Boolean =
        goalWords(objective) < SMALL_GOAL_WORDS && creatures > SMALL_GOAL_MAX_CREATURES

    fun measure(objective: String, plan: OrchestrationPlan): PlanSize {
        val steps = plan.steps
        return PlanSize(steps.size, if (steps.size <= 1) null else reasonFor(steps), bigForSmallGoal(objective, steps.size))
    }

    /** Why the steps are more than one creature: an approval between them, different kinds of work, steps that can run together, else simply separate pieces of work. */
    fun reasonFor(steps: List<OrchestrationPlanStep>): SwarmReason = when {
        steps.any { it.requiresHumanApproval } -> SwarmReason.NeedsApproval
        steps.map { it.templateId ?: it.roleId }.distinct().size > 1 -> SwarmReason.DifferentKinds
        steps.map { it.dependsOn.toSet() }.let { it.size != it.distinct().size } -> SwarmReason.SideBySide
        else -> SwarmReason.Separate
    }

    /** The sentence added to the planner's packet when the keeper asks for [choice] instead of what the queen proposed. */
    fun overrideInstruction(choice: PlanSizeChoice): String = when (choice) {
        PlanSizeChoice.One ->
            " The keeper asked for ONE creature: return a plan with exactly one step that does all of the work."
        PlanSizeChoice.Swarm ->
            " The keeper asked for a swarm: return a plan of two or more steps, split where the work differs in kind, needs different permissions or checks, or can run in parallel."
    }

    /** The Story words for a size, e.g. "one creature" or "3 creatures". */
    fun words(creatures: Int): String = if (creatures == 1) "one creature" else "$creatures creatures"

    /** The one Story line for the queen's choice and, when the keeper changed it, the override. */
    fun storyLine(proposed: Int, overrideTo: PlanSizeChoice?, finalCreatures: Int, flagged: Boolean): String = buildString {
        append("Size: the queen proposed ").append(words(proposed))
        if (flagged) append(" and flagged it as a big plan for a small goal")
        if (overrideTo != null) {
            append("; you asked for ").append(if (overrideTo == PlanSizeChoice.One) "one creature" else "a swarm")
            append(" instead and the plan was made again with ").append(words(finalCreatures))
        }
    }
}

/**
 * The planner's size rule, added once to the instruction for a new plan. The marker phrase at its start is what the
 * test counts, so it must not be repeated in any other planner text.
 */
const val SMALLEST_PLAN_INSTRUCTION: String =
    " Prefer the smallest sufficient plan: use ONE creature (a single step) when the goal is one kind of work with no need for " +
        "separate permissions, models, checks or parallelism. Use more creatures only when the steps differ in kind, need " +
        "different permissions or checks between them, or can run in parallel."

/**
 * The keeper's request, made on the plan approval card, to see the other size. Set by the screen before it answers the
 * decision with "do not start"; the launch then plans once more with [PlanSizing.overrideInstruction].
 */
class PlanResize {
    var requested: Boolean = false
}
