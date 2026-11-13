package com.hereliesaz.geministrator.tour

/** What a lesson step waits for: one observable event, optionally on one registered target. */
data class TourExpect(val kind: ExpectKind, val target: String?)

/**
 * The typed payload a teacher creature writes for ONE lesson step: one target, one sentence, one expected event, and
 * everything a check-in answer may need later (so handling an answer needs no model call). Every field is untrusted
 * model output until [LessonValidator] has passed it, and is rendered as plain text only.
 */
data class LessonPayload(
    val target: String,
    val sentence: String,
    val expect: TourExpect,
    val explain: String,
    val ifMissed: String,
    val checkIn: String,
    val anotherWay: String,
    /** Two tiny moves that split the same single action. */
    val smaller: List<String>,
    /** An optional re-explanation of the previous concept, from a new angle. */
    val bridge: String? = null,
)
