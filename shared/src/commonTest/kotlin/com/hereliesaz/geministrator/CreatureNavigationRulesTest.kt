package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions behind `openCreature`, `openHiveInWorkflows` and the pinned-unconnected warning of `CreatureProviderRow`. */
class CreatureNavigationRulesTest {
    private fun role(provider: String?) = RoleDefinition(
        id = RoleDefinitionId("r"),
        name = "R",
        description = "d",
        instructions = "i",
        preferredProviderId = provider?.let(::AgentProviderId),
    )

    @Test
    fun aLinkToACreatureOpensItAndNeverClosesIt() {
        // Its panel was set aside: the link brings the panel back, whatever is selected.
        assertEquals(CreatureOpenAction.ReopenPanel, creatureOpenAction("build", selectedTaskId = "build", setAsideTaskId = "build"))
        assertEquals(CreatureOpenAction.ReopenPanel, creatureOpenAction("build", selectedTaskId = "review", setAsideTaskId = "build"))
        // Another creature (or none) is selected: select this one.
        assertEquals(CreatureOpenAction.Select, creatureOpenAction("build", selectedTaskId = "review", setAsideTaskId = null))
        assertEquals(CreatureOpenAction.Select, creatureOpenAction("build", selectedTaskId = null, setAsideTaskId = "review"))
        // Already open: a second tap does nothing (it must not toggle it shut).
        assertEquals(CreatureOpenAction.KeepOpen, creatureOpenAction("build", selectedTaskId = "build", setAsideTaskId = null))
        assertEquals(CreatureOpenAction.KeepOpen, creatureOpenAction("build", selectedTaskId = "build", setAsideTaskId = "review"))
    }

    @Test
    fun workflowsOpensOnTheNamedHiveElseTheOneOnScreen() {
        assertEquals("named", hiveToOpenInWorkflows("named", "live"))
        assertEquals("live", hiveToOpenInWorkflows(null, "live"))
        assertNull(hiveToOpenInWorkflows(null, null))
    }

    @Test
    fun aPinToAProviderThatIsNotConnectedIsFlagged() {
        assertTrue(pinnedProviderNeedsConnection("openai", connectedProviderIds = setOf("anthropic")))
        assertTrue(pinnedProviderNeedsConnection("openai", connectedProviderIds = emptySet()))
        assertFalse(pinnedProviderNeedsConnection("openai", connectedProviderIds = setOf("openai", "anthropic")))
        // Auto has nothing pinned, so nothing to warn about.
        assertFalse(pinnedProviderNeedsConnection(null, connectedProviderIds = emptySet()))
    }

    @Test
    fun aProviderTheTaskRequiresIsPinnedByTheTaskNotTheRole() {
        assertTrue(providerPinnedByTask("anthropic", role(provider = null)))
        assertFalse(providerPinnedByTask("openai", role(provider = "openai")))
        assertFalse(providerPinnedByTask(null, role(provider = null)))
    }
}
