package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.orchestration.PlanSuggestion
import com.hereliesaz.geministrator.orchestration.PlanSuggestionKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import kotlinx.serialization.Serializable

/**
 * What planning did for one run: which creatures planned and reviewed, on which Source, in how many rounds, and what code checked.
 * Plain facts, no model text. It goes on the run's Story as one line ([storyLine]).
 */
@Serializable
data class PlanningRecord(
    val plannerRole: String,
    /** The Source that drafted the plan, when known (a catalog id); null when the app chose and did not say. */
    val plannerSource: String? = null,
    val reviewerRole: String,
    val reviewerSource: String? = null,
    /** Reviews made, counting a checklist-only one. */
    val rounds: Int = 0,
    /** Why the reviewer was not asked, when it was not: the checklist was then the whole review. */
    val skipped: GateReason? = null,
    /** The rules code can tick on the plan that was kept, and how many it met. */
    val checkedByRules: Int = 0,
    val metByRules: Int = 0,
    /** Ideas beyond the requirements held for the next run. */
    val suggestions: Int = 0,
) {
    /** One plain line for the Story. */
    fun storyLine(): String = buildString {
        append("Planning: ").append(plannerRole).append(" drafted the plan on ").append(sourceName(plannerSource)).append("; ")
        if (skipped != null) {
            append(reviewerRole).append(" was not asked (").append(skippedWords(skipped)).append(")")
        } else {
            append(reviewerRole).append(" reviewed it on ").append(sourceName(reviewerSource))
            append(" in ").append(rounds).append(if (rounds == 1) " round" else " rounds")
        }
        append("; the rules met ").append(metByRules).append(" of ").append(checkedByRules)
        if (suggestions > 0) append("; ").append(suggestions).append(if (suggestions == 1) " idea" else " ideas").append(" kept for next time")
    }

    private fun sourceName(id: String?): String = id?.let { ProviderCatalog.entry(it)?.displayName ?: it } ?: "a Source the app chose"

    private fun skippedWords(reason: GateReason): String = when (reason) {
        GateReason.UnpricedUnderCap -> "its model has no known price and a dollar cap is set"
        GateReason.OverHourlyCap -> "the hourly cap leaves no room"
        GateReason.NoRoom -> "no room on the Source"
        GateReason.CallLimit -> "the call limit was reached"
        GateReason.UnpricedUsage -> "some use this hour has no known price"
    }
}

/** What planning leaves for the run once the keeper starts it: its record, and the ideas the reviewer had beyond the requirements. */
@Serializable
data class PlanningNotes(val record: PlanningRecord, val suggestions: List<PlanSuggestion> = emptyList())

/**
 * What is missing because the reviewer was not asked, with where to fix it (pure; no model). [linkedSources] is how many
 * Sources are linked: with no room and only one, a second Source is the fix. A reached call limit is not something to fix.
 */
fun missingForSkippedReviewer(reason: GateReason?, linkedSources: Int): Missing? = when (reason) {
    null, GateReason.CallLimit -> null
    GateReason.UnpricedUnderCap, GateReason.UnpricedUsage -> Missing(MissingKind.ReviewerSkippedNoPrice, null, MissingFix.RaiseCap)
    GateReason.OverHourlyCap -> Missing(MissingKind.ReviewerSkippedOverCap, null, MissingFix.RaiseCap)
    GateReason.NoRoom ->
        if (linkedSources <= 1) Missing(MissingKind.ReviewerSkippedNoSecondSource, null, MissingFix.ConnectSource)
        else Missing(MissingKind.ReviewerSkippedNoRoom, null, MissingFix.ConnectSource)
}

/**
 * The ideas the reviewer had beyond the requirements, as rows for the improvement ledger (source QueenKept, kept for the NEXT
 * run). They are only records: nothing in a row changes the running plan or any role, and none is applied without the keeper.
 * The id comes from the swarm, the kind, the step and the words, so the same idea is written once. A goal that came from outside
 * keeps only ideas that lead somewhere (no free-text kind), and marks them outside.
 */
fun planSuggestionRows(
    runId: String,
    definitionId: String,
    suggestions: List<PlanSuggestion>,
    existing: List<ImprovementProposal>,
    now: Long,
    objectiveOutside: Boolean,
): List<ImprovementProposal> =
    suggestions
        .filter { !objectiveOutside || it.kind != PlanSuggestionKind.Other }
        .map { idea ->
            ImprovementProposal(
                id = queenNoteId("plan-idea", definitionId, idea.kind.name, idea.stepId.orEmpty(), idea.text),
                cycleRunId = runId,
                kind = ImprovementKind.Other,
                target = idea.stepId?.let { queenSlot(it, 60) } ?: idea.kind.name,
                summary = queenText("The reviewer suggests (" + idea.kind.name + "): " + idea.text, 240),
                newText = "",
                expectedEffect = queenText(idea.why, 240),
                undo = "",
                meta = false,
                createdAtEpochMillis = now,
                source = ImprovementSource.QueenKept,
                sourceRef = definitionId,
                evidence = queenText("Offered while the plan was reviewed, before it started. For the next run, never applied to this one. " + idea.why, 300),
                outside = objectiveOutside,
            )
        }
        .distinctBy { it.id }
        .filter { row -> existing.none { it.id == row.id } }
