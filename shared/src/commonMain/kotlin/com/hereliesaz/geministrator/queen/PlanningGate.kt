package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.workflow.HourlySpend
import com.hereliesaz.geministrator.workflow.SpendCaps

/** What a model's price is known to be. Free (a keyless Source or on-device), Priced (the price table has it), or Unknown. */
enum class PriceStatus { Free, Priced, Unknown }

/**
 * What planning and her own model calls may use. One object, so the numbers are tuned in one place. Planning is the
 * planner's call and the reviewer's calls; her own are rulings, checkpoints and wording.
 */
object PlanningLimits {
    /** Reviews of one plan on a keyless Source. */
    const val ROUNDS = 2

    /** Reviews of one plan on a Source the keeper pays for with their own key. */
    const val ROUNDS_OWN_KEY = 3

    /** Model calls for planning one swarm, hard: a plan, a review, and at most one rework and one re-review. */
    const val MAX_CALLS = 4

    /** What one planning call may be sent and may answer, in tokens. */
    const val MAX_INPUT_TOKENS = 6_000
    const val MAX_OUTPUT_TOKENS = 1_200

    /** What one of her own calls may be sent and may answer, in tokens. */
    const val QUEEN_INPUT_TOKENS = 1_500
    const val QUEEN_OUTPUT_TOKENS = 200

    /** How many calls of her own (checkpoints and wording; rulings are counted apart) one run may cost, and how far apart. */
    const val QUEEN_CALLS_PER_RUN = 3
    const val QUEEN_CALL_SPACING_MILLIS = 10L * 60_000L

    /** Her calls stay under this share of the keeper's hourly spend cap. */
    const val HOURLY_SHARE = 0.5

    fun rounds(ownKey: Boolean): Int = if (ownKey) ROUNDS_OWN_KEY else ROUNDS
}

/** Which planning creature is asking. The planner's call is needed for any plan; the reviewer's can be done by code alone. */
enum class PlanningCaller { Planner, Reviewer }

/** Why a call was not made. Closed: the keeper's words for each live in the queen's strings, never in run text. */
enum class GateReason { UnpricedUnderCap, OverHourlyCap, NoRoom, CallLimit, UnpricedUsage }

/** What the gate says. Fail closed: anything it cannot see is not allowed. */
sealed interface CallDecision {
    data object Allow : CallDecision

    /** The reviewer is skipped; the checklist the code can tick is the whole review. */
    data class ChecklistOnly(val reason: GateReason) : CallDecision

    /** No call: there is no plan rather than an unpriced spend the keeper's cap cannot see. */
    data class Refuse(val reason: GateReason) : CallDecision
}

private fun SpendCaps.anyDollarCap(): Boolean = runCostCapUsd != null || hourlySpendCapUsd != null

/**
 * Whether a planning call may be made now (pure; fail closed). [estimateUsd] is the call's priced estimate, null when the
 * price is unknown. With any dollar cap set the model must be priced, there must be no unpriced use this hour, and the known
 * hourly spend plus this call stays under half the hourly cap. A reviewer with no room on a Source's request limit, or past
 * the call limit, is skipped (checklist only) instead of blocking the plan; a planner past the limit is refused.
 */
fun planningMayCall(
    caller: PlanningCaller,
    price: PriceStatus,
    estimateUsd: Double?,
    caps: SpendCaps,
    hourly: HourlySpend,
    callsSoFar: Int,
    pacerRoom: Boolean,
): CallDecision {
    fun no(reason: GateReason): CallDecision =
        if (caller == PlanningCaller.Reviewer) CallDecision.ChecklistOnly(reason) else CallDecision.Refuse(reason)
    if (callsSoFar >= PlanningLimits.MAX_CALLS) return no(GateReason.CallLimit)
    if (caps.anyDollarCap()) {
        if (price == PriceStatus.Unknown) return no(GateReason.UnpricedUnderCap)
        val hourlyCap = caps.hourlySpendCapUsd
        if (hourlyCap != null) {
            if (hourly.unpricedUsage) return no(GateReason.UnpricedUsage)
            val cost = if (price == PriceStatus.Free) 0.0 else estimateUsd ?: return no(GateReason.UnpricedUnderCap)
            if (hourly.knownUsd + cost >= hourlyCap * PlanningLimits.HOURLY_SHARE) return no(GateReason.OverHourlyCap)
        }
    }
    if (caller == PlanningCaller.Reviewer && !pacerRoom) return CallDecision.ChecklistOnly(GateReason.NoRoom)
    return CallDecision.Allow
}

/** What her own call is for. A ruling settles something that cannot wait; the rest are optional and yield to the keeper's caps. */
enum class QueenCallKind { Ruling, Checkpoint }

/**
 * Whether one of her own model calls may be made now (pure; fail closed). Quiet makes rulings only. A checkpoint needs
 * Helpful, a run that is neither frozen nor stopped, the objective the keeper's own (outside content is never sent to her
 * wording), the per-run count and spacing, and no Source creature waiting for room ([creatureWaiting]). Every call needs a
 * known price under any dollar cap and half the hourly cap left, as planning does.
 */
fun queenMayCall(
    kind: QueenCallKind,
    level: QueenLevel,
    price: PriceStatus,
    estimateUsd: Double?,
    caps: SpendCaps,
    hourly: HourlySpend,
    checkpointCalls: Int,
    lastCheckpointAtEpochMillis: Long?,
    now: Long,
    frozenOrStopped: Boolean,
    objectiveOutside: Boolean,
    creatureWaiting: Boolean,
): Boolean {
    if (kind == QueenCallKind.Checkpoint) {
        if (level != QueenLevel.Helpful || frozenOrStopped || objectiveOutside || creatureWaiting) return false
        if (checkpointCalls >= PlanningLimits.QUEEN_CALLS_PER_RUN) return false
        if (lastCheckpointAtEpochMillis != null && now - lastCheckpointAtEpochMillis < PlanningLimits.QUEEN_CALL_SPACING_MILLIS) return false
    }
    // The shared money rule: one place for planning and for her, so they cannot disagree.
    val asPlanner = planningMayCall(PlanningCaller.Planner, price, estimateUsd, caps, hourly, callsSoFar = 0, pacerRoom = true)
    return asPlanner == CallDecision.Allow
}

/** What the queen needs to know about one linked Source to order them (a pin is the keeper's, and is handled before this). */
data class SourceTraits(val id: String, val keyed: Boolean, val priced: Boolean, val healthy: Boolean)

/**
 * The order her own calls try Sources in. A keeper's pin is the only one tried (a pinned Source never switches
 * silently). Otherwise a Source with the keeper's own key and a known price first, then any keyed one, then a healthy
 * keyless one; an unhealthy Source is last. Ties keep the order given. Empty means she has no model.
 */
fun queenSourceOrder(pin: String?, linked: List<SourceTraits>): List<String> {
    if (pin != null) return listOf(pin)
    return linked.sortedWith(
        compareBy<SourceTraits>({ !it.healthy }, { !(it.keyed && it.priced) }, { !it.keyed }),
    ).map { it.id }
}

/**
 * The order the reviewer tries Sources in: a pin alone, else every linked Source other than the planner's first (a second
 * opinion from another Source is truer), then the planner's own Source as the last resort, which the keeper is told of.
 */
fun reviewerSourceOrder(plannerSource: String?, reviewerPin: String?, linked: List<String>): List<String> {
    if (reviewerPin != null) return listOf(reviewerPin)
    val others = linked.filter { it != plannerSource }
    return if (plannerSource != null && plannerSource in linked) others + plannerSource else others
}
