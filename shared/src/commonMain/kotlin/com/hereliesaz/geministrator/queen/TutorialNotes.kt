package com.hereliesaz.geministrator.queen

/**
 * What the queen says in Messages while she follows a keeper through the tutorial. Each is a closed fact (a count or a yes/no), never
 * text from a model: the words are string resources ([QueenVoice.TutorialProgress] and the others), so saying one costs no model call.
 */
sealed interface TutorialSay {
    /** The keeper has done [done] of [total] steps. Said at Helpful only (see [tutorialNotesFor]). */
    data class Progress(val done: Int, val total: Int) : TutorialSay

    /** The keeper answered that they were lost: the next lessons are smaller and she asks sooner. */
    data object Slower : TutorialSay

    /** The tour is over. [reached] is [com.hereliesaz.geministrator.intro.TutorialReach]'s reading of the keeper's answers and what the app saw. */
    data class Finished(val reached: Boolean) : TutorialSay
}

/** How many tutorial lines one run keeps: the living progress line, the slower line and the closing line. */
private const val TUTORIAL_NOTES_KEPT = 4

private fun tutorialNote(runId: String, say: TutorialSay): QueenNote = when (say) {
    // One living row: the same id with new counts replaces the old line.
    is TutorialSay.Progress -> QueenNote(
        queenNoteId(runId, "tutorial", "progress"),
        QueenVoice.TutorialProgress,
        listOf(say.done.coerceAtLeast(0).toString(), say.total.coerceAtLeast(0).toString()),
        QueenLink.Story,
    )
    TutorialSay.Slower -> QueenNote(queenNoteId(runId, "tutorial", "slower"), QueenVoice.TutorialSlower, link = QueenLink.Story)
    is TutorialSay.Finished -> QueenNote(
        queenNoteId(runId, "tutorial", "finished"),
        if (say.reached) QueenVoice.TutorialHiveYours else QueenVoice.TutorialHiveYoursShaky,
        link = QueenLink.Story,
    )
}

/**
 * [known] (a run's tutorial lines) with [say] added (pure). A repeat of the same line replaces it, the closing line takes the
 * progress line's place, and at most [TUTORIAL_NOTES_KEPT] are kept.
 */
fun tutorialNotesAfter(known: List<QueenNote>, runId: String, say: TutorialSay): List<QueenNote> {
    val note = tutorialNote(runId, say)
    val rest = known.filter { it.id != note.id && !(say is TutorialSay.Finished && it.voice == QueenVoice.TutorialProgress) }
    return (rest + note).takeLast(TUTORIAL_NOTES_KEPT)
}

/** What of a run's tutorial lines shows at [level]: the progress line is Helpful's (Quiet keeps to the slower line and the closing line). */
fun tutorialNotesFor(notes: List<QueenNote>, level: QueenLevel): List<QueenNote> =
    notes.filter { it.voice != QueenVoice.TutorialProgress || level == QueenLevel.Helpful }
