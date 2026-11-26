package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
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
    @Test
    fun repositoryBreadcrumbSeparatesOwnerAndRepositoryWithoutSourcePrefix() {
        assertEquals(listOf("HereLiesAz", "aive"), shellRepositoryLabels(RepositoryRef("HereLiesAz", "aive", "main")))
        assertEquals(
            listOf("team/platform", "aive"),
            shellRepositoryLabels(RepositoryRef("team/platform", "aive", "main", source = RepositorySource.GitLab)),
        )
        assertEquals(
            listOf("aive"),
            shellRepositoryLabels(RepositoryRef("", "aive", source = RepositorySource.Local, localPath = "/work/aive")),
        )
    }

    @Test
    fun districtGlyphContainsThreeHexagons() {
        assertEquals(3, ShellIcon.District.paths.size)
    }
}
