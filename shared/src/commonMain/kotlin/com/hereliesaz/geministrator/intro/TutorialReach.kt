package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.CheckInReply
import com.hereliesaz.geministrator.tour.StepOutcome

/**
 * Whether the keeper has reached what the tutorial is for: knowing what they are doing. It is judged, never asked. The keeper is
 * not asked to choose or confirm what "reached" means; their own answers to the queen's check-ins ("are you following so far",
 * "do you feel you know what you are doing now") are the first-class evidence, together with what the app saw them do.
 */
object TutorialReach {
    /** The keeper's latest stated feeling. "Show me on the real thing" is a request, not a feeling. */
    fun feeling(replies: List<CheckInReply>): CheckInReply? = replies.lastOrNull { it != CheckInReply.ShowMe }

    /** Every step was done, moved past or skipped as already known; none was left missed or never reached. */
    fun allPassed(outcomes: List<StepOutcome?>): Boolean =
        outcomes.isNotEmpty() && outcomes.all { it == StepOutcome.Met || it == StepOutcome.Next || it == StepOutcome.Skipped }

    /**
     * Reached when every step is passed and the keeper's latest feeling allows it: a "yes" is enough (with the steps passed); a
     * "sort of" or "lost" holds it back whatever the app saw, because the keeper said so; with no answer at all, only what the app
     * saw counts, and at least half the steps must have been done on the real control, not just moved past.
     */
    fun reached(outcomes: List<StepOutcome?>, replies: List<CheckInReply>): Boolean {
        if (!allPassed(outcomes)) return false
        return when (feeling(replies)) {
            CheckInReply.Yes -> true
            null -> outcomes.count { it == StepOutcome.Met } * 2 >= outcomes.size
            else -> false
        }
    }
}
