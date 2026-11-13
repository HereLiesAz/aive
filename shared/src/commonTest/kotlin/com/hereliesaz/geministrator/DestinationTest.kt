package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DestinationTest {
    @Test
    fun everyPlaceLandsOnItself() {
        for (place in ControlRoomDestination.entries) {
            assertEquals(place, Destination.Place(place).place)
        }
    }

    @Test
    fun keeperPartsAndConnectionsLandInTheKeepersRoom() {
        for (section in KeeperSection.entries) {
            assertEquals(ControlRoomDestination.Settings, Destination.KeeperPart(section).place)
        }
        assertEquals(ControlRoomDestination.Settings, Destination.Connections("https://agent.example").place)
    }

    @Test
    fun aRoleLinkLandsOnTheRosterWithOrWithoutAField() {
        assertEquals(ControlRoomDestination.Company, Destination.Role("writer").place)
        assertEquals(ControlRoomDestination.Company, Destination.Role("writer", RoleField.TokenBudget).place)
        assertNull(Destination.Role("writer").field)
    }

    @Test
    fun creaturesHivesAndTheEditorLandOnTheHive() {
        assertEquals(ControlRoomDestination.Overview, Destination.Task("t1", CreatureTab.Mind).place)
        assertEquals(ControlRoomDestination.Overview, Destination.Hive("h").place)
        assertEquals(ControlRoomDestination.Overview, Destination.SwarmEditor.place)
    }

    @Test
    fun aPendingDestinationIsTakenOnce() {
        PendingDestination.request = Destination.KeeperPart(KeeperSection.Connections)
        assertEquals(Destination.KeeperPart(KeeperSection.Connections), PendingDestination.take())
        assertNull(PendingDestination.take())
    }

    @Test
    fun aRoleFieldRequestIsTakenOnce() {
        RosterRequests.field = RoleField.Instructions
        assertEquals(RoleField.Instructions, RosterRequests.takeField())
        assertNull(RosterRequests.takeField())
    }
}
