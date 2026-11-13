package com.hereliesaz.geministrator.tour

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourTargetIdTest {
    @Test
    fun registeredIdsAreTheEnumNamesAndUnique() {
        assertEquals(TourTargetId.entries.size, TourTargetId.registeredIds.size)
        assertEquals(TourTargetId.entries.map { it.name }.toSet(), TourTargetId.registeredIds)
    }

    @Test
    fun fromIdFindsOnlyRegisteredIds() {
        assertNotNull(TourTargetId.fromId("MESSAGES_BUTTON"))
        assertNull(TourTargetId.fromId("messages_button"))
        assertNull(TourTargetId.fromId("NOT_A_CONTROL"))
    }

    @Test
    fun needsAreClosedAndAcyclic() {
        for (t in TourTargetId.entries) {
            assertTrue(t !in reachable(t), "${t.name} needs itself")
        }
    }

    @Test
    fun everyTargetHasAnObservableEvent() {
        for (t in TourTargetId.entries) assertTrue(t.observable.isNotEmpty(), t.name)
    }

    @Test
    fun expectKindWireNamesRoundTrip() {
        for (k in ExpectKind.entries) assertEquals(k, ExpectKind.fromWire(k.wire))
        assertNull(ExpectKind.fromWire("clicked"))
    }

    @Test
    fun rowsDropATargetWhoseLabelIsMissing() {
        val rows = tourTargetRows { key -> if (key == "keeper_room") "Keeper's room" else null }
        assertEquals(listOf("KEEPERS_ROOM_GEAR"), rows.map { it.id })
        assertEquals("Keeper's room", rows.single().label)
    }

    private fun reachable(from: TourTargetId): Set<TourTargetId> {
        val seen = mutableSetOf<TourTargetId>()
        val stack = ArrayDeque(from.needs)
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (seen.add(next)) stack.addAll(next.needs)
        }
        return seen
    }
}
