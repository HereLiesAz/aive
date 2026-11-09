package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading text that has not changed is not decoded again, but a change made by anyone is always seen. */
class SettingsSnapshotCacheTest {
    private fun project(name: String) = Project(ProjectId("p"), name, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    @Test
    fun aWriteThroughAnotherStoreOnTheSameSettingsIsSeenByAReaderThatHadCachedTheOldText() = runBlocking<Unit> {
        val settings = MapSettings()
        val reader = SettingsWorkflowPersistence(settings)
        val writer = SettingsWorkflowPersistence(settings)
        writer.projects.put(project("first"))
        assertEquals("first", reader.projects.get(ProjectId("p"))?.name)
        assertEquals("first", reader.projects.get(ProjectId("p"))?.name, "read again with nothing changed")

        writer.projects.put(project("second"))

        assertEquals("second", reader.projects.get(ProjectId("p"))?.name)
    }

    @Test
    fun clearingTheStoreIsSeenAtOnce() = runBlocking<Unit> {
        val store = SettingsWorkflowPersistence(MapSettings())
        store.projects.put(project("kept"))
        assertEquals("kept", store.projects.get(ProjectId("p"))?.name)

        store.clearWorkflowData()

        assertEquals(null, store.projects.get(ProjectId("p")))
    }
}
