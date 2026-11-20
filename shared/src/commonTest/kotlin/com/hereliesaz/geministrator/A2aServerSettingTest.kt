package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aServerSettingTest {
    @Test
    fun aDeviceThatNeverChoseGetsTheServerOff() {
        assertFalse(A2A_SERVER_ENABLED_BY_DEFAULT)
        assertFalse(a2aServerEnabled(storedChoice = null))
    }

    @Test
    fun anExplicitChoiceIsKept() {
        assertFalse(a2aServerEnabled(storedChoice = false))
        assertTrue(a2aServerEnabled(storedChoice = true))
    }

    @Test
    fun storedValuesParseOnlyAsAnExplicitChoice() {
        assertEquals(true, parseA2aServerChoice("true"))
        assertEquals(false, parseA2aServerChoice(" FALSE "))
        assertNull(parseA2aServerChoice(null))
        assertNull(parseA2aServerChoice(""))
        assertNull(parseA2aServerChoice("maybe"))
        // An unreadable value falls back to the default (off).
        assertFalse(a2aServerEnabled(parseA2aServerChoice("garbage")))
    }

    @Test
    fun anExistingInstallThatTurnedItOffStaysOff() {
        // The key is written only when the keeper pressed the switch.
        assertFalse(a2aServerEnabled(parseA2aServerChoice("false")))
    }

    @Test
    fun theNetworkStaysClosedUnlessTheKeeperOpensIt() {
        assertFalse(a2aServerOpenToNetwork(storedChoice = null))
        assertFalse(a2aServerOpenToNetwork(parseA2aServerChoice("garbage")))
        assertFalse(a2aServerOpenToNetwork(storedChoice = false))
        assertTrue(a2aServerOpenToNetwork(storedChoice = true))
    }
}
