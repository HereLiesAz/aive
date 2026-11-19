package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which connected Sources the keeper has switched off. A Source switched off keeps its key (or, if keyless, its link) but is used
 * by nothing: no creature, no planner, no queen, no memory stage. Switching it on again needs no key to be entered. The set lives in the
 * app's durable UI state, so it survives restarts and upgrades; disconnecting a Source forgets it ([set] with `true`).
 *
 * The store is read on first use and never at start-up, and a store that cannot be reached (a host unit test with no platform settings)
 * reads as "nothing is switched off": the code that builds providers must never fail because of this.
 */
object ProviderEnablement {
    private const val KEY = "sources.disabled"

    private var override: DurableUiStateStore? = null
    private var loaded = false
    private var disabledIds: Set<String> by mutableStateOf(emptySet())

    private fun store(): DurableUiStateStore? = override ?: runCatching { DurableUiState.store }.getOrNull()

    private fun load() {
        if (loaded) return
        loaded = true
        disabledIds = runCatching { store()?.getString(KEY)?.split(',')?.filter(String::isNotBlank)?.toSet() }.getOrNull().orEmpty()
    }

    /** Tests: read [store] (null: the app's own) and forget what was loaded. */
    internal fun useStore(store: DurableUiStateStore?) {
        override = store
        loaded = false
        disabledIds = emptySet()
    }

    /** Ids the keeper switched off. Read in composition, it recomposes when a switch is flipped. */
    val disabled: Set<String>
        get() {
            load()
            return disabledIds
        }

    fun isEnabled(id: String): Boolean = id !in disabled

    /** Switches [id] on ([enabled] true) or off. */
    fun set(id: String, enabled: Boolean) {
        load()
        val next = if (enabled) disabledIds - id else disabledIds + id
        if (next == disabledIds) return
        disabledIds = next
        runCatching { store()?.putString(KEY, next.sorted().joinToString(",")) }
    }

    /** [linked] (a map keyed by Source id) without the Sources that are switched off. */
    fun <V> usable(linked: Map<String, V>): Map<String, V> {
        val off = disabled
        return if (off.isEmpty()) linked else linked.filterKeys { it !in off }
    }
}
