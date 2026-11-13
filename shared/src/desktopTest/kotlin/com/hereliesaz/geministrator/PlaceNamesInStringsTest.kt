package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** User text names the places by what they are called now (the Keeper's room, the Hive, the Roster). */
class PlaceNamesInStringsTest {
    private val stale = listOf("in Settings", "Overview screen", "Open Overview", "role in Swarm", "under This device")

    @Test
    fun noStringSendsTheKeeperToAPlaceThatIsGone() {
        val dir = File("src/commonMain/composeResources/values")
        val files = dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
        assertTrue(files.isNotEmpty(), "resources not found from ${File(".").absolutePath}")
        val hits = files.flatMap { file ->
            file.readLines().filter { line -> stale.any { it in line } }.map { "${file.name}: ${it.trim().take(90)}" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun theSpendCapsAreInTheAllowancePartOnEveryWidth() {
        assertEquals(KeeperSection.Limits, spendLimitsSection(compact = false))
        assertEquals(KeeperSection.Limits, spendLimitsSection(compact = true))
        assertEquals(KeeperGroup.Allowance, spendLimitsSection(compact = true).group)
    }
}
