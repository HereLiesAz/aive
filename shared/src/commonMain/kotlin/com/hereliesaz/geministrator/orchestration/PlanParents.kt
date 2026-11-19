package com.hereliesaz.geministrator.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * A plan with three parents (docs/Orchestration_layer.md, Plan parents). The queen drafts the plan and two other planners,
 * each with a lens of its own and a Source other than hers where one is linked, draft it too. The queen then writes the
 * child from the three drafts, tagging every step with the parents it came from. Code checks the child like any plan, and if
 * it fails, the most unusual draft that passes is kept instead. The result goes to the plan reviewer as before: the parents
 * only raise the plan, they never approve it.
 *
 * The choice between drafts follows "The Alien Space of Science" (arXiv:2603.01092): a plan has to cohere (it passes every
 * rule code can tick) and, of those that do, the one least like the others is preferred, because a planner that samples
 * the usual shape every time keeps writing the usual plan. Nothing here is random.
 */
const val QUEEN_ORIGIN = "queen"

/** What each of the two planners is asked to be, besides a planner. */
enum class PlannerLens(val origin: String, val instruction: String) {
    Fewest(
        "planner-fewest",
        "Plan for the fewest steps that still meet every acceptance criterion: give one creature the work of two wherever one role can do both.",
    ),
    Unusual(
        "planner-unusual",
        "Plan the least usual shape that is still sound: where the obvious plan is a straight chain, consider parallel branches, a different mix of roles, or review in a different place. Every step must still be needed.",
    ),
}

/** One parent's plan and who drafted it. */
data class PlanDraft(val origin: String, val plan: OrchestrationPlan)

/**
 * The plan that goes to review, with its parents. [draftsMade] is how many drafts there were (1 means the queen alone),
 * [calls] the planning calls made to raise it, and [merged] whether the queen's merge was kept.
 */
data class ParentedPlan(
    val plan: OrchestrationPlan,
    val drafts: List<PlanDraft>,
    val calls: Int,
    val merged: Boolean,
    val keptOrigin: String? = null,
) {
    /** One plain line for the Story; null when the queen drafted alone. */
    fun storyLine(): String? {
        if (drafts.size < 2) return null
        val names = drafts.joinToString(", ") { it.origin }
        return if (merged) {
            val from = drafts.joinToString(", ") { draft -> "${draft.origin} ${plan.steps.count { draft.origin in it.parents }}" }
            "Parents: ${drafts.size} drafts ($names) were merged into this plan; steps from each: $from"
        } else {
            "Parents: ${drafts.size} drafts ($names) were made; the merge did not pass the rules, so the ${keptOrigin ?: QUEEN_ORIGIN} draft was kept"
        }
    }
}

private val parentsJson = Json { encodeDefaults = false }

/**
 * The plan to review, raised from the queen's [queenDraft] and the drafts of her companions ([OrchestrationAgentRuntime.planners]).
 * [unmet] counts the rules code finds unmet in a plan; a plan with none passes. A companion that fails is no parent; a merge that
 * fails or does not pass leaves the most unusual draft that does. With no companions this is [queenDraft] unchanged.
 */
suspend fun planFromParents(
    packet: OrchestrationPacket,
    queen: OrchestrationAgentRuntime,
    queenDraft: OrchestrationPlan,
    unmet: (OrchestrationPlan) -> Int,
): ParentedPlan {
    val companions = queen.planners
    val queenOnly = ParentedPlan(queenDraft, listOf(PlanDraft(QUEEN_ORIGIN, queenDraft)), calls = 1, merged = false, keptOrigin = QUEEN_ORIGIN)
    if (companions.isEmpty()) return queenOnly
    val asked = PlannerLens.entries.zip(companions)
    val others = coroutineScope {
        asked.map { (lens, runtime) ->
            async {
                try {
                    PlanDraft(lens.origin, runtime.plan(packet.copy(instruction = packet.instruction + " " + lens.instruction)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
    var calls = 1 + asked.size
    if (others.isEmpty()) return queenOnly.copy(calls = calls)
    val drafts = listOf(PlanDraft(QUEEN_ORIGIN, queenDraft)) + others

    val child = try {
        calls += 1
        queen.plan(packet.copy(instruction = packet.instruction + mergeInstruction(drafts)))
            .let { plan -> plan.copy(steps = plan.steps.map { it.copy(parents = it.parents.filter { origin -> drafts.any { d -> d.origin == origin } }.distinct()) }) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (child != null && unmet(child) == 0) {
        return ParentedPlan(child, drafts, calls, merged = true)
    }
    val passing = drafts.filter { unmet(it.plan) == 0 }
    val kept = passing.maxByOrNull { draft -> unusualness(draft, drafts) }
        ?: drafts.minByOrNull { unmet(it.plan) }
        ?: drafts.first()
    return ParentedPlan(kept.plan, drafts, calls, merged = false, keptOrigin = kept.origin)
}

/** What the queen is told when she writes the child: the drafts, and that each step must say which of them it comes from. */
internal fun mergeInstruction(drafts: List<PlanDraft>): String = buildString {
    append("\n\nMERGE: ").append(drafts.size).append(" drafts of this plan follow, each by a different planner. Write ONE plan that takes the best steps from them. ")
    append("Do not copy the first draft; where drafts disagree, keep the steps that best meet the acceptance criteria and drop any step that is not needed. ")
    append("Give every step a \"parents\" array naming the drafts it comes from (one or more of ")
    append(drafts.joinToString(", ") { "\"${it.origin}\"" }).append("). Every step must still use only roles from availableAgents.")
    drafts.forEach { draft ->
        append("\nDraft ").append(draft.origin).append(": ").append(parentsJson.encodeToString(OrchestrationPlan.serializer(), draft.plan))
    }
}

/** What a step is, for comparing plans: its role or template, and how many steps it waits on. */
private fun shape(plan: OrchestrationPlan): Set<String> =
    plan.steps.map { step -> "${step.templateId ?: step.roleId}:${step.dependsOn.size}" }.toSet()

/** How unlike two plans are: 0 for the same shape, 1 for nothing in common. */
internal fun distance(a: OrchestrationPlan, b: OrchestrationPlan): Double {
    val x = shape(a)
    val y = shape(b)
    val union = (x + y).size
    return if (union == 0) 0.0 else 1.0 - (x intersect y).size.toDouble() / union
}

/** How unlike the other drafts a draft is, on average; the first draft wins a tie, which is the queen's. */
internal fun unusualness(draft: PlanDraft, drafts: List<PlanDraft>): Double {
    val others = drafts.filter { it !== draft }
    return if (others.isEmpty()) 0.0 else others.map { distance(draft.plan, it.plan) }.average()
}
