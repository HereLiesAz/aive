package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppStartupDestinationTest {
    @Test
    fun startupOpensTheHiveEvenWithNoProvider() {
        // The first hive asks for a provider as its first step (docs/Keepers-Room.md, First launch).
        assertEquals(
            ControlRoomDestination.Overview,
            startupControlRoomDestination(hasConfiguredProvider = false),
        )
    }

    @Test
    fun startupOpensOverviewWhenAProviderIsConfigured() {
        assertEquals(
            ControlRoomDestination.Overview,
            startupControlRoomDestination(hasConfiguredProvider = true),
        )
    }

    @Test
    fun computeIsOfferedOnlyWhereDistributedComputeIsAvailable() {
        assertEquals(ControlRoomDestination.entries, ControlRoomDestination.available(distributedComputeAvailable = true))
        val withoutCompute = ControlRoomDestination.available(distributedComputeAvailable = false)
        assertFalse(ControlRoomDestination.Compute in withoutCompute)
        assertEquals(ControlRoomDestination.entries.size - 1, withoutCompute.size)
    }
}
