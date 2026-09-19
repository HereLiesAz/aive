package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class AppStartupDestinationTest {
    @Test
    fun startupOpensSettingsWhenNoProviderIsConfigured() {
        assertEquals(
            ControlRoomDestination.Settings,
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
}
