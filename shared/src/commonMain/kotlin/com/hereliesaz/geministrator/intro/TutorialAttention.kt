package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.tour.CheckInReply

/**
 * What the queen does about the keeper's progress at one moment. She follows the tutorial the whole way through, so this is
 * decided over and over from signals that cost no model call; there is no fixed count of calls she may make.
 */
enum class TutorialAttention {
    /** Say nothing. */
    Nothing,

    /** Say where the keeper is, in Messages (a string, at the level's pace). */
    Update,

    /** Ask a comprehension question: the existing check-in, in the lesson's own generated line. */
    Ask,

    /** The keeper said they were lost: acknowledge it in Messages and make the next lessons smaller. */
    SlowDown,
}

/** The numbers behind the signals. Tuning knobs, not a syllabus. */
object AttentionLimits {
    /** Going back this many times since the last question says the keeper may be lost. */
    const val BACK_NAVIGATIONS_LOST = 2

    /** Staying this long on one step without its checkpoint (the layer's own "missed" comes later, at 240 seconds). */
    const val LONG_DWELL_MILLIS = 150_000L

    /** At Helpful, one progress update after this many steps. Quiet gets none mid-run. */
    const val UPDATE_EVERY_STEPS = 3
}

/**
 * Everything the queen can see of the keeper, all deterministic: what they opened, tapped and answered (the layer's own state
 * and events), how long they stayed, and which way they moved.
 */
data class AttentionSignals(
    /** Steps the keeper has moved past. */
    val stepsPassed: Int = 0,
    /** A check-in is on screen, waiting for an answer. */
    val checkInOpen: Boolean = false,
    /** An answer just given and not yet acted on. */
    val unhandledAnswer: CheckInReply? = null,
    /** The checkpoint allowance passed on the current step without its event. */
    val missedCheckpoint: Boolean = false,
    /** Times the keeper went back since the last question. */
    val backNavigations: Int = 0,
    /** How long the keeper has stayed on the current step while the layer was showing. */
    val dwellMillis: Long = 0L,
    /** A question was already asked about this step or the one just left. */
    val askedForThisStep: Boolean = false,
    /** The pace says a check-in is due after the step the keeper just left (see [com.hereliesaz.geministrator.tour.CheckInPolicy]). */
    val boundaryDue: Boolean = false,
    /** Steps passed since the last progress update. */
    val stepsSinceUpdate: Int = 0,
    val level: QueenLevel = QueenLevel.Quiet,
)

/** True when the signals say the keeper may be lost: a missed checkpoint, repeated back-navigation or a long dwell. */
fun keeperMayBeLost(s: AttentionSignals): Boolean =
    s.missedCheckpoint || s.backNavigations >= AttentionLimits.BACK_NAVIGATIONS_LOST || s.dwellMillis >= AttentionLimits.LONG_DWELL_MILLIS

/**
 * The decision (pure: signals in, one of four out). In order: a "Lost me" answer slows her down; an open question is never stacked
 * on; a lost-looking keeper is asked (once per step, and a mixed signal such as a "Yes" followed by a missed checkpoint is also asked
 * rather than guessed at, since the answer settles it for no model call); a lesson boundary the pace marks is asked; at Helpful,
 * a progress update every few steps; otherwise nothing.
 */
fun tutorialAttention(s: AttentionSignals): TutorialAttention = when {
    s.unhandledAnswer == CheckInReply.Lost -> TutorialAttention.SlowDown
    s.checkInOpen -> TutorialAttention.Nothing
    keeperMayBeLost(s) && !s.askedForThisStep -> TutorialAttention.Ask
    s.boundaryDue -> TutorialAttention.Ask
    s.level == QueenLevel.Helpful && s.stepsPassed > 0 && s.stepsSinceUpdate >= AttentionLimits.UPDATE_EVERY_STEPS -> TutorialAttention.Update
    else -> TutorialAttention.Nothing
}
