package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import kotlinx.coroutines.CancellationException
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

    /** Whether [catalog] is the real one; purchases are claimed only once it is. */
    var catalogLoaded = false
        private set

    /** Loads the catalog; false (leaving an empty catalog) when it could not be read. */
    suspend fun load(): Boolean {
        val loaded = try {
            BreedCatalog.load()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
        catalog = loaded ?: BreedCatalog()
        catalogLoaded = loaded != null
        return catalogLoaded
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

    /**
     * Breeds bought from the Store join the stable, one creature per breed of each purchase. Each
     * purchase is granted (saved at once) and only then consumed through [shop]. Nothing is claimed
     * before the catalog has loaded, and a purchase naming a breed the catalog does not sell stays
     * unconsumed for a later launch. A failed consume leaves the purchase to
     * [BreedPurchases.unclaimed]; its token, kept in [Stable.granted] until consumed, stops it being
     * granted twice. Returns the breeds granted by this call.
     */
    suspend fun claim(shop: BreedPurchases, paid: List<BreedPurchase>): List<String> {
        if (!catalogLoaded) return emptyList()
        val added = mutableListOf<String>()
        for (purchase in paid) {
            if (purchase.token !in stable.granted) {
                if (purchase.breedIds.isEmpty() || purchase.breedIds.any { catalog[it]?.sold != true }) continue
                update(purchase.breedIds.fold(stable, Stable::add).let { it.copy(granted = it.granted + purchase.token) })
                added += purchase.breedIds
            }
            val consumed = try {
                shop.consume(purchase)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                false
            }
            if (consumed) update(stable.copy(granted = stable.granted - purchase.token))
        }
        return added
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
