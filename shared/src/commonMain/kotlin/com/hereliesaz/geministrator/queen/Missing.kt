package com.hereliesaz.geministrator.queen

/** What the queen found missing. A closed set: the keeper's words for each live in string resources, never in run text. */
enum class MissingKind {
    NoSource, NoPlanner, NoReviewer, PairBroken, SameSource,

    // The reviewer was not asked (see missingForSkippedReviewer): its model has no price under a dollar cap, the hourly cap has no room, the Source has no room, or no second Source.
    ReviewerSkippedNoPrice, ReviewerSkippedOverCap, ReviewerSkippedNoRoom, ReviewerSkippedNoSecondSource,
}

/** Where the keeper goes to get it: an existing place, never a new one. */
enum class MissingFix { ConnectSource, EnableRole, EditRole, RaiseCap }

/** One missing thing. [detail] is a role name or a short rule, never model text. */
data class Missing(val kind: MissingKind, val detail: String?, val fix: MissingFix)

/**
 * What is missing for the queen to plan and review (pure; no model needed): no linked Source at all, or a planner
 * and reviewer that would both run on the same Source (only role independence then, a second Source would be a truer
 * second opinion). [linkedSourceIds] are the Sources that can answer; the pins are the roles' own choices, or null.
 */
fun missingForPlanning(linkedSourceIds: List<String>, plannerPin: String?, reviewerPin: String?): List<Missing> = buildList {
    if (linkedSourceIds.isEmpty()) {
        add(Missing(MissingKind.NoSource, null, MissingFix.ConnectSource))
        return@buildList
    }
    val plannerSource = plannerPin ?: linkedSourceIds.first()
    val reviewerSource = reviewerPin ?: linkedSourceIds.firstOrNull { it != plannerSource } ?: plannerSource
    if (plannerSource == reviewerSource && reviewerPin == null) add(Missing(MissingKind.SameSource, reviewerSource, MissingFix.ConnectSource))
}

/**
 * What she says once in Messages about [missing]: one note per missing thing, with a link to the existing place
 * where it is fixed. The advice that a second Source would give a truer second opinion is only for Helpful; the
 * rest block her work and are always said. Stable ids, so the same state gives the same notes.
 */
internal fun missingNotes(runId: String, missing: List<Missing>, level: QueenLevel): List<QueenNote> =
    missing
        .filter { it.kind != MissingKind.SameSource || level == QueenLevel.Helpful }
        .map { thing ->
            val voice = when (thing.kind) {
                MissingKind.NoSource -> QueenVoice.MissingNoSource
                MissingKind.NoPlanner -> QueenVoice.MissingNoPlanner
                MissingKind.NoReviewer -> QueenVoice.MissingNoReviewer
                MissingKind.PairBroken -> QueenVoice.MissingPairBroken
                MissingKind.SameSource -> QueenVoice.MissingSameSource
                MissingKind.ReviewerSkippedNoPrice -> QueenVoice.MissingReviewerNoPrice
                MissingKind.ReviewerSkippedOverCap -> QueenVoice.MissingReviewerOverCap
                MissingKind.ReviewerSkippedNoRoom -> QueenVoice.MissingReviewerNoRoom
                MissingKind.ReviewerSkippedNoSecondSource -> QueenVoice.MissingReviewerNoSecondSource
            }
            val args = when (thing.kind) {
                MissingKind.NoPlanner, MissingKind.NoReviewer, MissingKind.PairBroken -> listOf(queenSlot(thing.detail.orEmpty(), 80))
                MissingKind.NoSource, MissingKind.SameSource, MissingKind.ReviewerSkippedNoPrice, MissingKind.ReviewerSkippedOverCap,
                MissingKind.ReviewerSkippedNoRoom, MissingKind.ReviewerSkippedNoSecondSource,
                -> emptyList()
            }
            val link = when (thing.fix) {
                MissingFix.ConnectSource -> QueenLink.Connections
                MissingFix.EnableRole, MissingFix.EditRole -> QueenLink.Roster
                MissingFix.RaiseCap -> QueenLink.Limits
            }
            QueenNote(queenNoteId(runId, "missing", thing.kind.name, thing.detail.orEmpty()), voice, args, link)
        }
