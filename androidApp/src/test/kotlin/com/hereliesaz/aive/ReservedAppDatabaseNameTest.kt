package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservedAppDatabaseNameTest {
    @Test
    fun appOwnedStoresAreReserved() {
        assertTrue(isReservedAppDatabaseName("aive-workflows.db"))
        assertTrue(isReservedAppDatabaseName("AIVE-memory.db"))
        assertTrue(isReservedAppDatabaseName("aive-procedure-x.db"))
        assertTrue(isReservedAppDatabaseName("haive-old.db"))
    }

    @Test
    fun roleDataDatabasesAreAllowed() {
        assertFalse(isReservedAppDatabaseName("inventory.db"))
        assertFalse(isReservedAppDatabaseName("customers.sqlite"))
    }
}
