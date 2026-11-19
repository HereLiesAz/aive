package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which connected Sources the keeper has switched off. A Source switched off keeps its key (or, if keyless, its link) but is used
 * by nothing: no creature, no planner, no queen, no memory stage. Switching it on again needs no key to be entered. The set lives in the
 * app's durable UI state, so it survives restarts and upgrades; disconnecting a Source forgets it ([set] with `true`).
 */
object ProviderEnablement {
    private const val KEY = "sources.disabled"

    internal var store: DurableUiStateStore = DurableUiState.store
        set(value) {
            field = value
            reload()
        }

    private var disabledIds: Set<String> by mutableStateOf(read())

    private fun read(): Set<String> = store.getString(KEY)?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()

    internal fun reload() {
        disabledIds = read()
    }

    /** Ids the keeper switched off. Read in composition, it recomposes when a switch is flipped. */
    val disabled: Set<String> get() = disabledIds

    fun isEnabled(id: String): Boolean = id !in disabledIds

    /** Switches [id] on ([enabled] true) or off. */
    fun set(id: String, enabled: Boolean) {
        val next = if (enabled) disabledIds - id else disabledIds + id
        if (next == disabledIds) return
        disabledIds = next
        store.putString(KEY, next.sorted().joinToString(","))
    }

    /** [linked] (a map keyed by Source id) without the Sources that are switched off. */
    fun <V> usable(linked: Map<String, V>): Map<String, V> = if (disabledIds.isEmpty()) linked else linked.filterKeys { it !in disabledIds }
}
