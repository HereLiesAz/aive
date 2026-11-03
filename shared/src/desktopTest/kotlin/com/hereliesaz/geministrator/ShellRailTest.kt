package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellRailTest {
    @Test
    fun everyRailPlaceLightsForItsOwnDestination() {
        ShellRailPlace.entries.forEach { place -> assertEquals(place, ShellRailPlace.of(place.destination)) }
    }

    @Test
    fun theKeepersRoomIsNotARailPlace() {
        assertNull(ShellRailPlace.of(ControlRoomDestination.Settings))
        assertNull(ShellRailPlace.of(ControlRoomDestination.Compute))
    }

    @Test
    fun workflowsOpenedFromAHiveKeepHiveLit() {
        assertEquals(ShellRailPlace.Hive, ShellRailPlace.of(ControlRoomDestination.Workflows))
    }

    @Test
    fun everyDestinationButTheKeepersRoomHasARailPlace() {
        ControlRoomDestination.entries
            .filter { it.navigationItem != ControlRoomDestination.Settings }
            .forEach { kotlin.test.assertNotNull(ShellRailPlace.of(it), "$it has no place on the rail") }
    }

    @Test
    fun hiveGoesBackFromAPlaceThenOutToTheDistrictAndBackIn() {
        assertEquals(RailHiveStep.BackToHive, railHiveStep(ControlRoomDestination.Memory, inDistrict = false))
        assertEquals(RailHiveStep.BackToHive, railHiveStep(ControlRoomDestination.Stable, inDistrict = true))
        assertEquals(RailHiveStep.OutToDistrict, railHiveStep(ControlRoomDestination.Overview, inDistrict = false))
        assertEquals(RailHiveStep.IntoHive, railHiveStep(ControlRoomDestination.Overview, inDistrict = true))
    }
}
