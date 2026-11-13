package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleTimeFieldTest {
    @Test
    fun wholeHoursParse() {
        assertEquals(7, parseScheduleHour("07:00"))
        assertEquals(7, parseScheduleHour(" 7:00 "))
        assertEquals(23, parseScheduleHour("23:00"))
        assertEquals(0, parseScheduleHour("00:00"))
    }

    @Test
    fun halfTypedOrOutOfRangeChangesNothing() {
        assertNull(parseScheduleHour(""))
        assertNull(parseScheduleHour("07"))
        assertNull(parseScheduleHour("07:0"))
        assertNull(parseScheduleHour("07:30"))
        assertNull(parseScheduleHour("24:00"))
        assertNull(parseScheduleHour("seven"))
    }
}
