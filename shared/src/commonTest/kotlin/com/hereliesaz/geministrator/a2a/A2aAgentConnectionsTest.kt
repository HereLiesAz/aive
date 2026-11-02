package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.ProviderCatalog
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aAgentConnectionsTest {
    @BeforeTest
    fun isolate() = A2aAgentConnections.useSettings(MapSettings())

    @AfterTest
    fun reset() = A2aAgentConnections.useSettings(MapSettings())

    @Test
    fun aBareOriginMeansItsWellKnownCard() {
        val agent = A2aAgentConnections.add("https://agent.example/")
        assertEquals("https://agent.example/.well-known/agent-card.json", agent.cardUrl)
        assertEquals("a2a:agent.example", agent.providerId)
        val explicit = A2aAgentConnections.add("https://other.example/cards/me.json")
        assertEquals("https://other.example/cards/me.json", explicit.cardUrl)
    }

    @Test
    fun cleartextIsOnlyForLocalHosts() {
        assertFailsWith<IllegalArgumentException> { A2aAgentConnections.add("http://agent.example") }
        assertFailsWith<IllegalArgumentException> { A2aAgentConnections.add("ftp://agent.example") }
        assertFailsWith<IllegalArgumentException> { A2aAgentConnections.add("agent.example") }
        assertEquals("http://127.0.0.1:41241/.well-known/agent-card.json", A2aAgentConnections.add("http://127.0.0.1:41241").cardUrl)
    }

    @Test
    fun connectionsJoinTheCatalogAsKeyOptionalAndPersist() {
        val settings = MapSettings()
        A2aAgentConnections.useSettings(settings)
        val agent = A2aAgentConnections.add("https://agent.example")
        val twin = A2aAgentConnections.add("https://agent.example/other-card.json")
        assertNotEquals(agent.providerId, twin.providerId)
        assertEquals(agent, A2aAgentConnections.add("https://agent.example"))

        val entry = ProviderCatalog.entry(agent.providerId)!!
        assertTrue(entry.keyOptional)
        assertTrue(ProviderCatalog.isA2aAgent(entry.id))

        A2aAgentConnections.useSettings(settings)
        assertEquals(listOf(agent, twin), A2aAgentConnections.all())

        A2aAgentConnections.remove(agent.providerId)
        assertNull(ProviderCatalog.entry(agent.providerId))
        A2aAgentConnections.useSettings(settings)
        assertEquals(listOf(twin), A2aAgentConnections.all())
    }
}
