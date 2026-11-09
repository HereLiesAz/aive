package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowScheduleStoreTest {
    private fun schedule(id: String, name: String = id) =
        WorkflowSchedule(
            id = id, workflowId = "hive", name = name, objective = "Do it", cadence = ScheduleCadence.weekly(2, 9),
            enabled = true, createdAtEpochMillis = 5L, lastNote = "kept",
        )

    @Test
    fun aScheduleSurvivesAWriteAndRead() = runTest {
        val settings = InMemorySettings()
        SettingsWorkflowScheduleStore(settings).put(schedule("b", "Beta"))
        SettingsWorkflowScheduleStore(settings).put(schedule("a", "alpha"))

        val read = SettingsWorkflowScheduleStore(settings).all()

        assertEquals(listOf("a", "b"), read.map { it.id })
        assertEquals(schedule("b", "Beta"), read.last())
    }

    @Test
    fun puttingTheSameIdReplacesItAndRemoveTakesItOut() = runTest {
        val store = SettingsWorkflowScheduleStore(InMemorySettings())
        store.put(schedule("a"))
        store.put(schedule("a").copy(enabled = false))
        assertEquals(listOf(false), store.all().map { it.enabled })
        store.remove("a")
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun anUnreadableListReadsAsEmptyButIsKeptAndNeverWrittenOver() = runTest {
        val settings = InMemorySettings()
        settings.putString(SettingsWorkflowScheduleStore.DEFAULT_KEY, "{not json")
        val store = SettingsWorkflowScheduleStore(settings)

        assertEquals(emptyList(), store.all())
        assertTrue(store.unreadable())
        assertFailsWith<ScheduleListUnreadableException> { store.put(schedule("a")) }
        assertFailsWith<ScheduleListUnreadableException> { store.remove("a") }
        assertEquals("{not json", settings.getStringOrNull(SettingsWorkflowScheduleStore.DEFAULT_KEY), "still there")
        assertEquals("{not json", settings.getStringOrNull(SettingsWorkflowScheduleStore.DEFAULT_KEY + ".corrupt"))

        // Only the keeper's decision starts over; the raw text stays under its .corrupt key.
        store.discardUnreadable()
        assertEquals(false, store.unreadable())
        store.put(schedule("a"))
        assertEquals(listOf("a"), store.all().map { it.id })
        assertEquals("{not json", settings.getStringOrNull(SettingsWorkflowScheduleStore.DEFAULT_KEY + ".corrupt"))
    }

    @Test
    fun updateChangesTheStoredCopyAndNeverBringsBackAScheduleThatIsGone() = runTest {
        val store = SettingsWorkflowScheduleStore(InMemorySettings())
        store.put(schedule("a"))
        store.put(schedule("a").copy(enabled = false)) // the keeper's change lands first

        val written = store.update("a") { it.copy(lastNote = "Started.") }

        assertEquals(false, written!!.enabled, "the change works on what is stored now")
        assertEquals("Started.", store.all().single().lastNote)
        assertNull(store.update("a") { null }, "null leaves it as it is")
        store.remove("a")
        assertNull(store.update("a") { it.copy(lastNote = "late") })
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun aListSavedBeforeTheNewFieldsStillLoads() = runTest {
        val settings = InMemorySettings()
        settings.putString(
            SettingsWorkflowScheduleStore.DEFAULT_KEY,
            """[{"id":"a","workflowId":"h","name":"A","objective":"Do","cadence":{"kind":"Daily","hourUtc":7},"enabled":true,"createdAtEpochMillis":5}]""",
        )

        val read = SettingsWorkflowScheduleStore(settings).all().single()

        assertEquals(null, read.definitionFingerprint)
        assertEquals(DaylightSaving.None, read.daylightSaving)
    }
}
