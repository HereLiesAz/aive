package com.hereliesaz.geministrator.tour

/**
 * What the pacing remembers about check-in answers so far. Pure data; nothing here is a score. Steps are 1-based.
 */
data class CheckInRecord(
    val gap: Int = TeachingLimits.CHECKIN_BASE_GAP,
    val yesStreak: Int = 0,
    /** How many of the next lessons are written in smaller mode. */
    val smallerRemaining: Int = 0,
    /** The last five answers, newest last. */
    val recent: List<CheckInReply> = emptyList(),
    /** The step number after which a check-in was last asked, or null. */
    val lastAskedAfter: Int? = null,
) {
    /** Two "Lost me" in the last five make "Show me on the real thing" the default first reply offered. */
    val defaultShowMe: Boolean get() = recent.count { it == CheckInReply.Lost } >= 2

    fun asked(afterStep: Int): CheckInRecord = copy(lastAskedAfter = afterStep)

    /** A written lesson uses up one smaller-mode lesson. */
    fun lessonWritten(): CheckInRecord = copy(smallerRemaining = (smallerRemaining - 1).coerceAtLeast(0))

    fun answered(reply: CheckInReply): CheckInRecord {
        val recent5 = (recent + reply).takeLast(5)
        return when (reply) {
            CheckInReply.Yes -> {
                val streak = yesStreak + 1
                if (streak >= 2) {
                    copy(gap = (gap + 2).coerceAtMost(TeachingLimits.CHECKIN_MAX_GAP), yesStreak = 0, recent = recent5)
                } else copy(yesStreak = streak, recent = recent5)
            }
            CheckInReply.SortOf -> copy(gap = 3, yesStreak = 0, recent = recent5)
            CheckInReply.Lost -> copy(gap = TeachingLimits.CHECKIN_MIN_GAP, yesStreak = 0, smallerRemaining = 2, recent = recent5)
            // A request, not a feeling: nothing about pacing changes.
            CheckInReply.ShowMe -> copy(recent = recent5)
        }
    }
}

/** Whether a check-in is asked after a step. Pure; the factory only places cheap slots, this decides at run time. */
object CheckInPolicy {
    /**
     * @param stepNumber 1-based number of the lesson just shown
     * @param totalSteps the plan's step count
     * @param shown the lesson was actually shown to the keeper (never ask about one that was not)
     * @param harder the concept has two or more prerequisites in the registry, or its checkpoint was missed
     */
    fun shouldAsk(record: CheckInRecord, stepNumber: Int, totalSteps: Int, shown: Boolean, harder: Boolean): Boolean {
        if (!shown) return false
        if (stepNumber < 2) return false
        if (stepNumber >= totalSteps) return false
        val since = stepNumber - (record.lastAskedAfter ?: 0)
        if (record.lastAskedAfter != null && since < TeachingLimits.CHECKIN_MIN_GAP) return false
        if (harder) return since >= TeachingLimits.CHECKIN_MIN_GAP
        return since >= record.gap
    }

    /** True when [target] is harder by data: two or more prerequisites, or its previous checkpoint was missed. */
    fun isHarder(prerequisiteCount: Int, previousOutcome: StepOutcome?): Boolean =
        prerequisiteCount >= 2 || previousOutcome == StepOutcome.Missed
}

/** What the layer should do next after a reply, for the integration to render. */
enum class NextMove { Continue, ShowAnotherWay, OfferShowMe, ShowSmaller, RingAgain }

data class CheckInResult(
    val state: TourState,
    val record: CheckInRecord,
    /** The comfort to record for the concept, or null for none (a request is not a feeling). */
    val comfort: Comfort?,
    val move: NextMove,
    /** True when the optional "what part lost you?" line is offered, once. */
    val offerNote: Boolean = false,
)

/** The four quick-reply handlers. Deterministic: no model call, because the alternatives were written with the lesson. */
object CheckInTransitions {
    /**
     * @param lessonIndex index of the lesson the check-in is about
     * @param sortOfCount how many times this concept already got "Sort of" in this check-in
     */
    fun handle(
        state: TourState,
        record: CheckInRecord,
        reply: CheckInReply,
        lesson: LessonPayload,
        lessonIndex: Int,
        sortOfCount: Int = 0,
    ): CheckInResult {
        val closed = state.copy(checkInLine = null)
        val next = record.answered(reply)
        return when (reply) {
            CheckInReply.Yes -> CheckInResult(closed.copy(note = null, smallerQueue = emptyList()), next, Comfort.Comfortable, NextMove.Continue)
            CheckInReply.SortOf ->
                if (sortOfCount >= TeachingLimits.ANOTHER_WAY_RETRIES) {
                    // A third explanation helps less than doing it: offer the real thing, keep the check-in open.
                    CheckInResult(state.copy(note = null), next, Comfort.Shaky, NextMove.OfferShowMe)
                } else {
                    CheckInResult(closed.copy(note = lesson.anotherWay), next, Comfort.Shaky, NextMove.ShowAnotherWay)
                }
            CheckInReply.Lost -> {
                val first = lesson.smaller.firstOrNull()
                CheckInResult(
                    closed.copy(note = first, smallerQueue = lesson.smaller.drop(1)),
                    next,
                    Comfort.Shaky,
                    NextMove.ShowSmaller,
                    offerNote = true,
                )
            }
            CheckInReply.ShowMe -> {
                val back = closed.copy(
                    index = lessonIndex,
                    outcomes = closed.outcomes - lessonIndex,
                    note = null,
                    smallerQueue = emptyList(),
                    explainOpen = false,
                    missedShown = false,
                )
                CheckInResult(back, next, null, NextMove.RingAgain)
            }
        }
    }
}
