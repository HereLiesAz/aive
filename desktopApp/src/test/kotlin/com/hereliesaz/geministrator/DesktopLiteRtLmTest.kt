package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLiteRtLmTest {
    @Test
    fun readsMemAvailableInBytes() {
        val text = "MemTotal:       16000000 kB\nMemFree:          100000 kB\nMemAvailable:    8000000 kB\n"
        assertEquals(8_000_000L * 1024L, parseMemAvailableBytes(text))
    }

    @Test
    fun missingMemAvailableIsNull() {
        assertNull(parseMemAvailableBytes("MemTotal: 1 kB\n"))
    }
}
