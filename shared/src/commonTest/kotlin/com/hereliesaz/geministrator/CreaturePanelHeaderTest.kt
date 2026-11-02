package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreaturePanelHeaderTest {
    private fun task(id: String) = TaskDefinition(id = TaskDefinitionId(id), name = id, objective = "Do $id", roleId = null)

    private val tasks = listOf(task("plan"), task("build"), task("review"))

    @Test
    fun placeIsItsPositionInPlanOrder() {
        assertEquals(PlanPlace(1, 3), planPlace(tasks, TaskDefinitionId("plan")))
        assertEquals(PlanPlace(3, 3), planPlace(tasks, TaskDefinitionId("review")))
    }

    @Test
    fun aTaskOutsideThePlanHasNoPlace() {
        assertNull(planPlace(tasks, TaskDefinitionId("deploy")))
    }

    @Test
    fun providerNameComesFromTheCatalogElseItsId() {
        val known = ProviderCatalog.entry(ProviderCatalog.ANTHROPIC_ID)
        assertEquals(known?.displayName ?: ProviderCatalog.ANTHROPIC_ID, providerDisplayName(ProviderCatalog.ANTHROPIC_ID))
        assertEquals("not-a-provider", providerDisplayName("not-a-provider"))
    }

    @Test
    fun headerLineLeavesOutWhatIsMissing() {
        assertEquals("Running · Claude · 2 of 3", creatureHeaderLine("Running", "Claude", "2 of 3"))
        assertEquals("Ready · 2 of 3", creatureHeaderLine("Ready", null, "2 of 3"))
        assertEquals("Ready", creatureHeaderLine("Ready", " ", null))
    }
}
