package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.builtins.nullable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "More editing" and the definition-error link open Workflows on one hive. The screen saves its
 * draft, not its pinned key, so a draft left from another hive must not survive the link.
 */
class WorkflowLibraryLinkTest {
    private fun hive(id: String) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = id,
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("t"),
                name = "t",
                objective = "o",
                roleId = BuiltInRoles.Architect.id,
            ),
        ),
    )

    private fun entry(id: String) = WorkflowLibraryEntry(
        key = "authored:$id",
        definition = hive(id),
        origin = WorkflowLibraryOrigin.Authored,
    )

    private fun storeWithDraftOf(id: String): DurableUiStateStore {
        val store = DurableUiStateStore(MapSettings())
        store.putString(
            WorkflowLibraryKeys.DRAFT,
            DurableUiState.json.encodeToString(WorkflowDefinition.serializer().nullable, hive(id)),
        )
        store.putString(WorkflowLibraryKeys.DRAFT_ORIGIN, WorkflowLibraryOrigin.Authored.name)
        store.putString(WorkflowLibraryKeys.SELECTED_TASK_ID, "t")
        store.putString(WorkflowLibraryKeys.SELECTED_KEY, "authored:$id")
        return store
    }

    @Test
    fun linkingToAnotherHiveDropsTheDraftSoSaveCannotWriteToTheFirst() {
        val store = storeWithDraftOf("hive-x")

        store.openWorkflowsOn(entry("hive-y"))

        assertEquals("authored:hive-y", store.getString(WorkflowLibraryKeys.SELECTED_KEY))
        assertEquals(WorkflowLibraryTab.Overview.name, store.getString(WorkflowLibraryKeys.TAB))
        assertNull(store.getString(WorkflowLibraryKeys.DRAFT), "the screen loads hive Y into a fresh draft")
        assertNull(store.getString(WorkflowLibraryKeys.DRAFT_ORIGIN))
        assertNull(store.getString(WorkflowLibraryKeys.SELECTED_TASK_ID))
    }

    @Test
    fun linkingToTheHiveBeingEditedKeepsTheKeepersDraft() {
        val store = storeWithDraftOf("hive-x")
        val before = store.getString(WorkflowLibraryKeys.DRAFT)

        store.openWorkflowsOn(entry("hive-x"))

        assertEquals("authored:hive-x", store.getString(WorkflowLibraryKeys.SELECTED_KEY))
        assertEquals(before, store.getString(WorkflowLibraryKeys.DRAFT))
        assertEquals("t", store.getString(WorkflowLibraryKeys.SELECTED_TASK_ID))
    }

    @Test
    fun anUnreadableDraftIsDroppedToo() {
        val store = DurableUiStateStore(MapSettings())
        store.putString(WorkflowLibraryKeys.DRAFT, "{not json")

        store.openWorkflowsOn(entry("hive-y"))

        assertNull(store.getString(WorkflowLibraryKeys.DRAFT))
    }
}
