package com.hereliesaz.geministrator.tour

/**
 * The tutorial's "With me so far?" check-in as an ordinary question in Messages. Derived, never stored: it exists exactly while the
 * overlay's [TourState.checkInLine] is open, so an answer given in the overlay closes it (the director clears the line) and an answer
 * given in Messages goes to the same [TourController.checkInSink] the overlay's pills use ([TourController.reply]), so the director
 * reacts identically. Only the keeper's feeling is asked (the line is the lesson's own generated "With me so far?"); never what they know.
 */
data class CheckInMessage(
    /** The Messages key: a new open check-in is a new item. */
    val key: String,
    /** The lesson's own check-in line, plain. */
    val line: String,
)

object CheckInMessages {
    const val KEY_PREFIX = "tour-checkin|"

    /** The four quick replies, in the order the overlay offers them. */
    val replies: List<CheckInReply> = listOf(CheckInReply.Yes, CheckInReply.SortOf, CheckInReply.Lost, CheckInReply.ShowMe)

    /** The open check-in of [state] as a Messages item, or null when none is open (or the line is blank). */
    fun of(state: TourState?): CheckInMessage? {
        val line = state?.checkInLine?.let { plain(it) }?.trim().orEmpty()
        if (state == null || line.isEmpty()) return null
        return CheckInMessage(key = KEY_PREFIX + state.index + "|" + line.hashCode(), line = line)
    }
}
