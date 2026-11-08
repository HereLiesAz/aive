package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun anUnreadableListReadsAsEmptyInsteadOfBreakingTheApp() = runTest {
        val settings = InMemorySettings()
        settings.putString(SettingsWorkflowScheduleStore.DEFAULT_KEY, "{not json")
        assertEquals(emptyList(), SettingsWorkflowScheduleStore(settings).all())
    }
}
