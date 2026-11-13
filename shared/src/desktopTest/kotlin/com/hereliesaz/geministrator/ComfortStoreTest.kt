package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.tour.Comfort
import com.hereliesaz.geministrator.tour.ComfortRecord
import com.hereliesaz.geministrator.tour.ComfortStore
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ComfortStoreTest {
    private val ids = setOf("MESSAGES_BUTTON", "KEEPERS_ROOM_GEAR")

    @Test
    fun savesLoadsAndClears() {
        val store = ComfortStore(DurableUiStateStore(MapSettings()), ids)
        val rec = ComfortRecord().mark("MESSAGES_BUTTON", Comfort.Shaky, 10)
        store.save(rec)
        assertEquals(rec, store.load())
        store.clear()
        assertEquals(ComfortRecord(), store.load())
    }
}
