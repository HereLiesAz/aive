package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.serialization.json.Json

/**
 * The keeper's stable, kept on this device (it's the keeper's, not a workflow's). Changes save at
 * once. Compose reads [stable] and [catalog] as state.
 */
internal class StableHost(private val store: DurableUiStateStore = DurableUiStateStore(rootKey = "aive.stable.v1")) {
    var catalog by mutableStateOf(BreedCatalog())
        private set
    var stable by mutableStateOf(read())
        private set

    suspend fun load() {
        catalog = runCatching { BreedCatalog.load() }.getOrDefault(BreedCatalog())
    }

    fun chooseStarter(breed: String) {
        if (stable.starter == null && catalog[breed]?.starter == true) update(stable.copy(starter = breed))
    }

    fun assign(role: RoleDefinitionId, key: String?) = update(stable.assign(role, key))

    /**
     * Every cleared run counts toward the next generation, programmatic work included; only the
     * agents' creatures are its parents, so the neuron never passes on genes or sways the mutation.
     */
    fun runCleared(run: WorkflowRun) = update(stable.onRunCleared(run, stable.contributorsOf(run), catalog))

    fun dismissHatching() = update(stable.copy(lastHatching = null))

    /** A breed bought from the Store joins the stable, one creature. */
    fun bought(breed: String) {
        if (catalog[breed]?.sold == true) update(stable.add(breed))
    }

    /** The rig the agent [roleId] is drawn with (the starter when unassigned), or null before a starter is chosen. */
    fun rigFor(roleId: RoleDefinitionId?): String? {
        val key = roleId?.let(stable::creatureOf) ?: stable.starter ?: return null
        return stable.rigOf(key)
    }

    private fun update(next: Stable) {
        if (next == stable) return
        stable = next
        store.putString(KEY, json.encodeToString(Stable.serializer(), next))
    }

    private fun read(): Stable = store.getString(KEY)
        ?.let { runCatching { json.decodeFromString(Stable.serializer(), it) }.getOrNull() }
        ?: Stable()

    private companion object {
        const val KEY = "stable"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

internal val LocalStableHost = staticCompositionLocalOf<StableHost?> { null }

/**
 * Which rig an agent wears, by its role label, once the keeper has a stable. Null leaves the old
 * role-based look (before a starter is chosen, or outside the app).
 */
internal val LocalCreatureRig = compositionLocalOf<(String) -> String?> { { null } }
