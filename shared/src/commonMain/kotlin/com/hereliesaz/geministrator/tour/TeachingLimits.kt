package com.hereliesaz.geministrator.tour

/**
 * The sanity bounds and pacing numbers of the tutorial. They are tuning knobs, not a syllabus: code only enforces
 * limits; what to teach, in what order and when to stop is decided by the queen's planner on every run.
 */
object TeachingLimits {
    const val BROAD_GOAL_MIN_STEPS = 6
    const val MAX_STEPS = 30
    const val FOCUSED_MIN_STEPS = 1
    const val FOCUSED_MAX_STEPS = 6

    const val STEP_OBJECTIVE_MAX_WORDS = 25
    /** The one thing to do, as shown on the overlay card. */
    const val SENTENCE_MAX_CHARS = 160
    /** About twenty seconds of reading. */
    const val EXPLAIN_MAX_WORDS = 60
    const val IF_MISSED_MAX_WORDS = 30
    /** Repairs after the first attempt: attempts = LESSON_REPAIRS + 1. */
    const val LESSON_REPAIRS = 2
    /** Then "missed": the "if missed" line once, and "I know this" stays offered. */
    const val CHECKPOINT_WAIT_MILLIS = 240_000L

    const val CHECKIN_LINE_MAX_CHARS = 80
    const val ANOTHER_WAY_MAX_WORDS = 40
    const val SMALLER_STEPS = 2
    const val SMALLER_STEP_MAX_WORDS = 20
    const val BRIDGE_MAX_WORDS = 40
    /** An unanswered check-in ends quietly. */
    const val CHECKIN_QUIET_TIMEOUT_MILLIS = 60_000L
    /** The optional "what part lost you?" line. */
    const val CHECKIN_NOTE_MAX_CHARS = 140
    /** Steps between check-ins at the start. */
    const val CHECKIN_BASE_GAP = 4
    const val CHECKIN_MIN_GAP = 2
    const val CHECKIN_MAX_GAP = 8
    /** After this many "Sort of" on one concept, offer "Show me on the real thing" instead of a third explanation. */
    const val ANOTHER_WAY_RETRIES = 1
    const val PAYLOAD_MAX_CHARS = 1_800
}
