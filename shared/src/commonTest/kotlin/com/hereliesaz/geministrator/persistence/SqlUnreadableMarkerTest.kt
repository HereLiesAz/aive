package com.hereliesaz.geministrator.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlUnreadableMarkerTest {
    @Test
    fun anUnreadableMarkerCountsAttemptsSoARepairedCopyIsRetried() {
        assertNull(sqlUnreadableAttempts(null))
        assertNull(sqlUnreadableAttempts("done"))
        assertEquals(1, sqlUnreadableAttempts("skipped-unreadable"), "the marker an older build wrote")
        assertEquals(1, sqlUnreadableAttempts(sqlUnreadableMarker(1)))
        assertEquals(4, sqlUnreadableAttempts(sqlUnreadableMarker(4)))
    }

    @Test
    fun theRetryCapIsFinite() {
        assertEquals(5, SQL_MAX_UNREADABLE_ATTEMPTS)
    }
}
