package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadLimitsTest {
    @Test
    fun theLimitIsTheDeclaredSizePlusSlack() {
        assertEquals(1_000L + 64L * 1024L, downloadByteLimit(1_000L))
        assertTrue(downloadByteLimit(null) > downloadByteLimit(1_000L))
    }

    @Test
    fun freeSpaceMustHoldTheFileAndAReserve() {
        assertTrue(hasRoomFor(100L, 100L * 1024L * 1024L))
        assertFalse(hasRoomFor(100L * 1024L * 1024L, 100L * 1024L * 1024L))
        assertTrue(hasRoomFor(0L, 0L))
    }
}
