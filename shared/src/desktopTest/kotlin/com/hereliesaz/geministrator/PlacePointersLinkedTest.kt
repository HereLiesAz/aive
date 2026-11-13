package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A sentence that points at a place (the nav map's "everything is a link") must be next to a link that goes there.
 * The sentences reworded in cluster D no longer carry the pointer in prose, and the screens that showed them name
 * the link composable: this fails if a pointer comes back as plain text, or a screen drops its link.
 */
class PlacePointersLinkedTest {
    private fun strings(): String = File("src/commonMain/composeResources/values/strings.xml").readText()

    private fun source(name: String): String =
        File("src/commonMain/kotlin/com/hereliesaz/geministrator/$name").readText()

    @Test
    fun retiredPointersStayOutOfProse() {
        val text = strings()
        listOf(
            "or set one up from any swarm",
            "Remove the package in the Store instead",
            "Link a source in the Keeper’s room",
            "Switch some on in the Roster",
            "Raise it on this creature’s Mind, in the hive",
            "Answer it on this creature’s Needs, in the hive",
        ).forEach { retired -> assertTrue(retired !in text, "\"$retired\" points at a place without a link") }
    }

    @Test
    fun eachScreenThatNamesAPlaceLinksToIt() {
        mapOf(
            "SchedulesPart.kt" to "openHive",
            "DelegationsPart.kt" to "onNameClick",
            "ImprovementPart.kt" to "openKeeperPart",
            "HallMonitorInboxScreen.kt" to "nannyScopePlace",
            "CompanyCustomizationScreen.kt" to "roleHiveList",
            "SwarmDraftPanel.kt" to "DraftFix",
            "HiveDeletion.kt" to "hive_delete_open_store",
            "LiveInboxScreen.kt" to "inbox_open_creature",
            "ComputeDelegationScreen.kt" to "links.openRole",
            "StableScreens.kt" to "openRole",
            "MemorySimpleScreen.kt" to "onOpenTechnical",
            "MascotNodeTerrarium.kt" to "badge.roleId",
            "DistrictScreen.kt" to "KeeperSection.Projects",
            "MindMapRunScreen.kt" to "onEyebrowClick",
            "ProviderManagementScreens.kt" to "OtherDevicesCard",
        ).forEach { (file, marker) ->
            assertTrue(marker in source(file), "$file no longer links with $marker")
        }
    }

    @Test
    fun aNannySolutionsTargetLinksLikeItsFindingsScope() {
        val text = source("HallMonitorInboxScreen.kt")
        assertTrue(
            Regex("openScopePlace").findAll(text).count() >= 2,
            "the finding's scope and the solution's target both open where that is changed",
        )
    }
}
