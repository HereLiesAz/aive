package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationHistoryTest {
    private val creature = NavEntry(ControlRoomDestination.Overview, taskId = "t1", tab = "Mind")

    @Test
    fun leavingACreatureKeepsItsIdAndTab() {
        val h = historyAfterNavigate(emptyList(), creature, ControlRoomDestination.Settings)
        assertEquals(listOf(creature), h)
    }

    @Test
    fun theHiveClearsTheWayBack() {
        val h = historyAfterNavigate(listOf(creature), NavEntry(ControlRoomDestination.Settings), ControlRoomDestination.Overview)
        assertEquals(emptyList(), h)
    }

    @Test
    fun returningToAnEarlierPlacePopsToIt() {
        val settings = NavEntry(ControlRoomDestination.Settings)
        val h = historyAfterNavigate(listOf(creature, settings), NavEntry(ControlRoomDestination.Compute), ControlRoomDestination.Settings)
        assertEquals(listOf(creature), h)
    }

    @Test
    fun aRestoredTabAppliesOnlyToItsCreature() {
        assertEquals("Mind", restoredTabOrDefault("t1", "Mind", "t1", "Work"))
        assertEquals("Work", restoredTabOrDefault("t1", "Mind", "t2", "Work"))
        assertEquals("Work", restoredTabOrDefault("", "", "t1", "Work"))
    }
}
