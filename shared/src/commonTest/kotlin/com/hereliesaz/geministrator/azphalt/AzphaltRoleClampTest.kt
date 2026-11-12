package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals

class AzphaltRoleClampTest {
    private val packaged = RoleDefinition(
        id = RoleDefinitionId("gatekeeper"),
        name = "Gatekeeper",
        description = "d",
        instructions = "i",
        authorities = setOf(RoleAuthority.ApprovePlan, RoleAuthority.RejectPlan),
    )

    @Test
    fun aPackageRoleArrivesWithoutAuthorities() {
        assertEquals(emptySet(), clampInstalledPackageRole(packaged, existing = null).authorities)
    }

    @Test
    fun anUpgradeKeepsOnlyWhatTheKeeperAlreadyGranted() {
        val granted = packaged.copy(authorities = setOf(RoleAuthority.RejectPlan))
        assertEquals(setOf(RoleAuthority.RejectPlan), clampInstalledPackageRole(packaged, granted).authorities)
    }
}
