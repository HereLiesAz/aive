package com.hereliesaz.geministrator.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddonSystemTest {

    @Test
    fun testPermissionDenial() {
        val mediator = HaiveAddonMediator("testAddon", emptySet())
        assertFailsWith<SecurityException> { mediator.app.version }
        assertFailsWith<SecurityException> { mediator.repository.getInfo() }
    }

    @Test
    fun testPermissionGranted() {
        val mediator = HaiveAddonMediator("testAddon", setOf(HostPermission.AppRead))
        assertEquals("0.9.3", mediator.app.version)
    }

    @Test
    fun testUnknownPermissionsDenied() {
        val parser = AzphaltPackageParser()
        val mapped = parser.mapPermissions(listOf("AppRead", "UnknownPermission", "CompanyRead"))
        assertTrue(mapped.contains(HostPermission.AppRead))
        assertTrue(mapped.contains(HostPermission.CompanyRead))
        assertEquals(2, mapped.size)
    }
}
