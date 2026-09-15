package com.hereliesaz.geministrator.addons

import kotlin.test.Test
import kotlin.test.assertTrue

class AddonArchitectureBoundaryTest {

    @Test
    fun testAddonApiDoesNotExposeMemory() {
        val hasMemory = HaiveAddonApi::class.java.methods.any { it.name.contains("memory", ignoreCase = true) }
        assertTrue(!hasMemory, "Add-on API must not expose memory.")
    }
}
