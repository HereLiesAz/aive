package com.hereliesaz.geministrator.tour

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckInMessageTest {
    @AfterTest
    fun reset() = TourController.stop()

    @Test
    fun noOpenCheckInIsNoMessage() {
        assertNull(CheckInMessages.of(null))
        assertNull(CheckInMessages.of(TourState(total = 3)))
        assertNull(CheckInMessages.of(TourState(total = 3, checkInLine = "   ")))
    }

    @Test
    fun anOpenCheckInIsOneMessageWithItsOwnLine() {
        val message = CheckInMessages.of(TourState(total = 3, index = 2, checkInLine = "With me so far?"))
        assertEquals("With me so far?", message?.line)
        assertTrue(message!!.key.startsWith(CheckInMessages.KEY_PREFIX))
    }

    @Test
    fun aLaterCheckInIsANewItem() {
        val a = CheckInMessages.of(TourState(total = 5, index = 2, checkInLine = "With me so far?"))
        val b = CheckInMessages.of(TourState(total = 5, index = 4, checkInLine = "With me so far?"))
        assertNotEquals(a?.key, b?.key)
    }

    @Test
    fun theMessageClosesWhenTheOverlayClearsTheLine() {
        val open = TourState(total = 3, index = 1, checkInLine = "With me so far?")
        assertTrue(CheckInMessages.of(open) != null)
        assertNull(CheckInMessages.of(open.copy(checkInLine = null)))
    }

    @Test
    fun theFourQuickRepliesAreTheOverlaysFour() {
        assertEquals(CheckInReply.entries.toList(), CheckInMessages.replies.toList())
    }

    @Test
    fun aReplyFromMessagesReachesTheSameSinkAsTheOverlay() {
        val got = mutableListOf<CheckInReply>()
        TourController.checkInSink = { reply: CheckInReply -> got.add(reply); Unit }
        CheckInMessages.replies.forEach { TourController.reply(it) }
        assertEquals(CheckInReply.entries.toList(), got.toList())
    }
}
