package com.hereliesaz.geministrator

import androidx.compose.runtime.mutableStateOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one route to Connections: every link to it, from any place, lands the same way. */
class PlaceLinksTest {
    @AfterTest
    fun reset() {
        ConnectionsRequests.a2aAddress = null
    }

    @Test
    fun openingConnectionsOpensTheConnectionsPartInTheKeepersRoom() {
        val section = mutableStateOf(KeeperSection.Limits.name)
        val open = mutableStateOf(false)
        val opened = mutableListOf<ControlRoomDestination>()
        openConnectionsPlace(section, open, a2aAddress = null) { opened += it }
        assertEquals(KeeperSection.Connections.name, section.value)
        assertEquals(true, open.value)
        assertEquals(listOf(ControlRoomDestination.Settings), opened)
        assertNull(ConnectionsRequests.a2aAddress)
    }

    @Test
    fun anAddressIsHandedToTheConnectFormOnce() {
        val section = mutableStateOf(KeeperSection.Limits.name)
        val open = mutableStateOf(false)
        openConnectionsPlace(section, open, a2aAddress = "  https://agent.example/.well-known/agent-card.json ") {}
        assertEquals("https://agent.example/.well-known/agent-card.json", ConnectionsRequests.a2aAddress)
        assertEquals("https://agent.example/.well-known/agent-card.json", ConnectionsRequests.take())
        assertNull(ConnectionsRequests.take(), "a request is consumed, so reopening Connections later does not refill the form")
    }

    @Test
    fun aBlankOrAbsurdAddressIsNoRequest() {
        assertNull(connectionsAddressRequest(null))
        assertNull(connectionsAddressRequest("   "))
        assertNull(connectionsAddressRequest("h".repeat(5000)))
    }

    @Test
    fun rosterAndSwarmEditRequestsAreTakenOnce() {
        RosterRequests.roleId = "writer"
        assertEquals("writer", RosterRequests.take())
        assertNull(RosterRequests.take())
        SwarmEditRequests.pending = true
        assertEquals(true, SwarmEditRequests.take())
        assertEquals(false, SwarmEditRequests.take())
    }
}
