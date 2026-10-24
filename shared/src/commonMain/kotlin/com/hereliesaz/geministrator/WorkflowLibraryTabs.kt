package com.hereliesaz.geministrator

/**
 * Tabs of the Workflows destination. [Library] and [Store] are always available; the rest work on
 * the pinned selected workflow, so they only exist while one is selected.
 */
internal enum class WorkflowLibraryTab(val needsSelection: Boolean) {
    Library(needsSelection = false),
    Store(needsSelection = false),
    Overview(needsSelection = true),
    /** The workflow as a fish bowl: its creatures at rest, no run. docs/Fishbowl-Builder.md */
    Bowl(needsSelection = true),
    Tasks(needsSelection = true),
    Combine(needsSelection = true),
    Save(needsSelection = true),
    ;

    companion object {
        fun fromName(name: String?): WorkflowLibraryTab? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Pure state of the Workflows tab row and the pinned selection above it. The screen keeps the two
 * raw strings in durable UI state (so they survive leaving the destination and coming back) and
 * runs every change through this holder, so the rules live in one place and are unit tested.
 *
 * [tabName] and [selectedKey] are raw so that a stale or unknown stored value degrades to the
 * library instead of crashing.
 */
internal data class WorkflowLibraryTabState(
    val tabName: String = WorkflowLibraryTab.Library.name,
    val selectedKey: String = "",
) {
    val hasSelection: Boolean get() = selectedKey.isNotBlank()

    /** Tabs to show, in order. Saving needs the library host, so [canSave] gates [WorkflowLibraryTab.Save]. */
    fun availableTabs(canSave: Boolean): List<WorkflowLibraryTab> =
        WorkflowLibraryTab.entries.filter { tab ->
            when {
                !tab.needsSelection -> true
                !hasSelection -> false
                tab == WorkflowLibraryTab.Save -> canSave
                else -> true
            }
        }

    /** The tab to render: the stored one when it is available, otherwise the library. */
    fun resolvedTab(canSave: Boolean): WorkflowLibraryTab {
        val stored = WorkflowLibraryTab.fromName(tabName)
        return if (stored != null && stored in availableTabs(canSave)) stored else WorkflowLibraryTab.Library
    }

    fun selectTab(tab: WorkflowLibraryTab, canSave: Boolean): WorkflowLibraryTabState =
        if (tab in availableTabs(canSave)) copy(tabName = tab.name) else this

    /** Pins [key]. The current tab is kept, so browsing the library stays in the library. */
    fun selectWorkflow(key: String): WorkflowLibraryTabState = copy(selectedKey = key)

    /** Opens the pinned workflow's overview. Without a selection there is nothing to open. */
    fun openSelected(): WorkflowLibraryTabState =
        if (hasSelection) copy(tabName = WorkflowLibraryTab.Overview.name) else this

    /** Unpins the workflow. A tab that only exists for a selection falls back to the library. */
    fun clearSelection(): WorkflowLibraryTabState {
        val stored = WorkflowLibraryTab.fromName(tabName)
        val fallBack = stored == null || stored.needsSelection
        return copy(
            tabName = if (fallBack) WorkflowLibraryTab.Library.name else tabName,
            selectedKey = "",
        )
    }
}
