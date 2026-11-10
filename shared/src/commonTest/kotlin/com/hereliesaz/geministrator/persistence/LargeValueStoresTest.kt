package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.DurableUiStateStore
import com.hereliesaz.geministrator.InboxReadLog
import com.hereliesaz.geministrator.testing.PreferencesLimitedSettings
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetRecord
import com.hereliesaz.geministrator.workflow.refinement.SettingsLadderDatasetStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop Preferences throws on values over 8192 chars; stores that grow must not hit that. */
class LargeValueStoresTest {
    @Test
    fun durableUiStateStoreKeepsAValueLargerThanOnePreferencesEntry() {
        val settings = PreferencesLimitedSettings()
        val store = DurableUiStateStore(settings)
        val big = "x".repeat(30_000)
        store.putString("draft", big)
        assertEquals(big, store.getString("draft"))
        store.putString("draft", "small")
        assertEquals("small", store.getString("draft"))
        store.remove("draft")
        assertEquals(null, store.getString("draft"))
        assertTrue(settings.keys.none { it.contains("__chunk") }, "no stale chunks: ${settings.keys}")
    }

    @Test
    fun inboxReadLogPersistsPastOneEntry() {
        val settings = PreferencesLimitedSettings()
        val log = InboxReadLog(settings, capacity = 1_000)
        val keys = (1..600).map { "inbox-item-key-number-$it" }
        log.markRead(keys)
        val restarted = InboxReadLog(settings, capacity = 1_000)
        assertTrue(keys.all { it in restarted.read })
    }

    @Test
    fun ladderDatasetStoreGrowsPastOneEntry() = runBlocking {
        val settings = PreferencesLimitedSettings()
        val store = SettingsLadderDatasetStore(settings)
        val records = (1..60).map {
            LadderDatasetRecord(id = "r$it", kind = "level", ladder = "run/task", taskId = "t$it", objective = "o".repeat(400), capturedAtEpochMillis = 1L)
        }
        store.append(records)
        assertEquals(60, SettingsLadderDatasetStore(settings).all().size)
    }
}
