package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.RoleDefinition

/** One finished use of a role in a run: [domain] is the project the run belonged to (the one place the app already groups work by). */
data class RoleUse(val roleId: String, val runId: String, val domain: String)

/** A generic role has been used at least this many times, over at least [TRAIN_SUGGEST_MIN_RUNS] runs, in one domain. */
const val TRAIN_SUGGEST_MIN_USES: Int = 5
const val TRAIN_SUGGEST_MIN_RUNS: Int = 3

/**
 * The roles she could suggest training a specialist for, from [uses] alone: no model. A role qualifies when it is a generic
 * enabled role (no parents, not hatched from a run, not a transient draft) that has no specialist yet, and one domain holds at
 * least [TRAIN_SUGGEST_MIN_USES] uses over [TRAIN_SUGGEST_MIN_RUNS] runs. Most used first. It only leads to the Roster, where
 * the keeper starts training with a few generic words; adoption is still gated behind the keeper's approval.
 */
internal fun trainingCandidates(uses: List<RoleUse>, roles: Collection<RoleDefinition>): List<QueenCandidate> {
    val byId = roles.associateBy { it.id.value }
    return uses.groupBy { it.roleId }.mapNotNull { (roleId, ofRole) ->
        val role = byId[roleId] ?: return@mapNotNull null
        val generic = role.enabled && role.parents.isEmpty() && role.hatchedFromRunId == null && !roleId.endsWith(TRAINING_DRAFT_SUFFIX)
        val hasSpecialist = roles.any { other -> other.parents.any { it.value == roleId } }
        if (!generic || hasSpecialist) return@mapNotNull null
        val best = ofRole.groupBy { it.domain }.values
            .filter { it.size >= TRAIN_SUGGEST_MIN_USES && it.map { u -> u.runId }.distinct().size >= TRAIN_SUGGEST_MIN_RUNS }
            .maxOfOrNull { it.size } ?: return@mapNotNull null
        best to QueenCandidate(
            QueenAction.TrainSpecialist, roleId, QueenVoice.SuggestTrainSpecialist,
            listOf(queenSlot(role.name), best.toString()), outside = false,
        )
    }.sortedWith(compareByDescending<Pair<Int, QueenCandidate>> { it.first }.thenBy { it.second.subject }).map { it.second }
}
