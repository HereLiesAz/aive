package com.hereliesaz.geministrator

/**
 * The durable UI state survives upgrades, so "where I was" would otherwise carry an old build's screens into
 * a new one. The store remembers the major.minor of the build that last wrote it; when the running build's
 * differs, the navigation keys below are forgotten. Drafts, applied-once markers and every other key are user
 * data and stay.
 */
internal object UiStateVersionGate {
    const val VERSION_KEY: String = "ui-state.app-version"

    /** The "where I was" keys: which part, tab, view or item was open. Never a draft the keeper typed. */
    val NAVIGATION_KEYS: List<String> = listOf(
        "keeper.section",
        "keeper.open",
        "keeper.connections.adding-mcp",
        "overview.zoom.district",
        "overview.resting-hive",
        "overview.timeline",
        "overview.timeline.view",
        "overview.timeline.log.source",
        "creature.panel.tab",
        "creature.panel.task",
        "navigation.selected-task-id",
        "hive.view.swarm",
        "inbox.selected",
        "azphalt-store.selected-package",
        WorkflowLibraryKeys.TAB,
        WorkflowLibraryKeys.SELECTED_KEY,
        WorkflowLibraryKeys.SELECTED_TASK_ID,
    )

    /** "MAJOR.MINOR" of a version name, or null when it has no such prefix (a "dev" build). */
    fun majorMinor(version: String?): String? {
        val parts = version?.trim()?.split('.') ?: return null
        if (parts.size < 2) return null
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null
        if (!parts[0].all(Char::isDigit) || !parts[1].all(Char::isDigit)) return null
        return parts[0] + "." + parts[1]
    }

    /**
     * True when the navigation keys should be forgotten: the stored version is known and its major.minor differs
     * from the running one. A first run (nothing stored) and an unparseable running version never reset.
     */
    fun shouldResetNavigation(stored: String?, running: String): Boolean {
        val now = majorMinor(running) ?: return false
        val before = majorMinor(stored) ?: return false
        return before != now
    }

    /** Apply the gate to [store] for the running [running] version, then record it. */
    fun apply(store: DurableUiStateStore, running: String) {
        val stored = store.getString(VERSION_KEY)
        if (shouldResetNavigation(stored, running)) NAVIGATION_KEYS.forEach(store::remove)
        val now = majorMinor(running) ?: return
        if (stored != now) store.putString(VERSION_KEY, now)
    }
}
