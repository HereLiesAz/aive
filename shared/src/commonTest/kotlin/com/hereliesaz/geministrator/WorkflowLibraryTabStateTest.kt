package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowLibraryTabStateTest {
    @Test
    fun withoutASelectionOnlyLibraryAndStoreAreOffered() {
        val state = WorkflowLibraryTabState()
        assertFalse(state.hasSelection)
        assertEquals(
            listOf(WorkflowLibraryTab.Library, WorkflowLibraryTab.Store),
            state.availableTabs(canSave = true),
        )
    }

    @Test
    fun aSelectionAddsTheWorkflowTabsInOrder() {
        val state = WorkflowLibraryTabState().selectWorkflow("role:crash-test-dummy")
        assertTrue(state.hasSelection)
        assertEquals(
            listOf(
                WorkflowLibraryTab.Library,
                WorkflowLibraryTab.Store,
                WorkflowLibraryTab.Overview,
                WorkflowLibraryTab.Bowl,
                WorkflowLibraryTab.Tasks,
                WorkflowLibraryTab.Combine,
                WorkflowLibraryTab.Save,
            ),
            state.availableTabs(canSave = true),
        )
    }

    @Test
    fun saveIsHiddenWhenThereIsNoLibraryHost() {
        val state = WorkflowLibraryTabState().selectWorkflow("authored:mine")
        assertFalse(WorkflowLibraryTab.Save in state.availableTabs(canSave = false))
        assertEquals(state, state.selectTab(WorkflowLibraryTab.Save, canSave = false))
    }

    @Test
    fun selectingAWorkflowKeepsTheCurrentTab() {
        val onStore = WorkflowLibraryTabState().selectTab(WorkflowLibraryTab.Store, canSave = true)
        val selected = onStore.selectWorkflow("installed:pkg:wf")
        assertEquals(WorkflowLibraryTab.Store, selected.resolvedTab(canSave = true))
        assertEquals("installed:pkg:wf", selected.selectedKey)
    }

    @Test
    fun theSelectedTabAndSelectionSurviveSwitchingTabs() {
        var state = WorkflowLibraryTabState().selectWorkflow("role:r")
        state = state.selectTab(WorkflowLibraryTab.Tasks, canSave = true)
        assertEquals(WorkflowLibraryTab.Tasks, state.resolvedTab(canSave = true))
        state = state.selectTab(WorkflowLibraryTab.Library, canSave = true)
        assertEquals("role:r", state.selectedKey)
        state = state.selectTab(WorkflowLibraryTab.Combine, canSave = true)
        assertEquals("role:r", state.selectedKey)
        assertEquals(WorkflowLibraryTab.Combine, state.resolvedTab(canSave = true))
    }

    @Test
    fun aWorkflowTabCannotBeChosenWithoutASelection() {
        val state = WorkflowLibraryTabState()
        assertSame(state, state.selectTab(WorkflowLibraryTab.Tasks, canSave = true))
        assertSame(state, state.openSelected())
    }

    @Test
    fun openSelectedShowsTheOverview() {
        val state = WorkflowLibraryTabState().selectWorkflow("role:r").openSelected()
        assertEquals(WorkflowLibraryTab.Overview, state.resolvedTab(canSave = true))
    }

    @Test
    fun clearingTheSelectionLeavesAWorkflowTabForTheLibrary() {
        val state = WorkflowLibraryTabState(tabName = WorkflowLibraryTab.Tasks.name, selectedKey = "role:r")
            .clearSelection()
        assertFalse(state.hasSelection)
        assertEquals(WorkflowLibraryTab.Library.name, state.tabName)
    }

    @Test
    fun clearingTheSelectionKeepsTheStoreTab() {
        val state = WorkflowLibraryTabState(tabName = WorkflowLibraryTab.Store.name, selectedKey = "role:r")
            .clearSelection()
        assertEquals("", state.selectedKey)
        assertEquals(WorkflowLibraryTab.Store, state.resolvedTab(canSave = true))
    }

    @Test
    fun aBlankSelectedKeyCountsAsNoSelection() {
        val state = WorkflowLibraryTabState(tabName = WorkflowLibraryTab.Tasks.name, selectedKey = "  ")
        assertFalse(state.hasSelection)
        assertEquals(WorkflowLibraryTab.Library, state.resolvedTab(canSave = true))
    }

    @Test
    fun unknownStoredTabNamesFallBackToTheLibrary() {
        val state = WorkflowLibraryTabState(tabName = "Gone", selectedKey = "role:r")
        assertEquals(WorkflowLibraryTab.Library, state.resolvedTab(canSave = true))
    }

    @Test
    fun aStoredSaveTabFallsBackWhenSavingBecomesUnavailable() {
        val state = WorkflowLibraryTabState(tabName = WorkflowLibraryTab.Save.name, selectedKey = "role:r")
        assertEquals(WorkflowLibraryTab.Save, state.resolvedTab(canSave = true))
        assertEquals(WorkflowLibraryTab.Library, state.resolvedTab(canSave = false))
    }
}
