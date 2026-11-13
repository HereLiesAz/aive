package com.hereliesaz.geministrator

/**
 * Keyless Sources start enabled (docs/Keepers-Room.md, Connections).
 *
 * A keyless Source is a built-in one that works with no key ([ProviderCatalogEntry.keyOptional]): the free
 * gateways Kilo, LLM7 and OVHcloud, and the AI Horde image generator. Remote A2A agents, store models and
 * vendor CLIs are never in this set: the keeper adds those on purpose, and they are used only by a role that
 * names them. Ollama Cloud is not keyless (it needs an account key, and from 2026-11-09 credits for its larger
 * models), so it is never turned on here.
 *
 * Each Source is turned on once, ever: [KeylessDefaultsState.appliedIds] remembers which have had their
 * default, so a Source the keeper disconnects later is not turned back on at the next launch. One switch
 * ([KeylessDefaultsState.allOff]) keeps all of them off.
 */
data class KeylessDefaultsState(
    /** Source ids connected right now. */
    val connectedIds: Set<String>,
    /** Source ids that have already had their default applied (persisted). */
    val appliedIds: Set<String>,
    /** The keeper turned every keyless Source off (persisted). */
    val allOff: Boolean = false,
)

/** What to do at launch: connect [connect] (in this order) and then remember [applied]. */
data class KeylessDefaultsPlan(val connect: List<String>, val applied: Set<String>)

/** The keyless built-in Sources, text writers first and best limits first, then the image-only ones. */
internal fun keylessSources(catalog: List<ProviderCatalogEntry> = ProviderCatalog.builtInEntries): List<ProviderCatalogEntry> =
    catalog.filter { it.keyOptional }.sortedWith(
        compareBy({ ProviderCatalog.isImageOnly(it.id) }, { ProviderCatalog.freeReliabilityRank(it.id) }),
    )

/**
 * Which keyless Sources to turn on now. A Source is turned on only if it has not had its default before and is
 * not already connected; either way it is then remembered. Nothing happens while the keeper has them all off.
 * Pure.
 */
fun defaultsToApply(
    state: KeylessDefaultsState,
    catalog: List<ProviderCatalogEntry> = ProviderCatalog.builtInEntries,
): KeylessDefaultsPlan {
    if (state.allOff) return KeylessDefaultsPlan(emptyList(), state.appliedIds)
    val fresh = keylessSources(catalog).map { it.id }.filter { it !in state.appliedIds }
    return KeylessDefaultsPlan(
        connect = fresh.filter { it !in state.connectedIds },
        applied = state.appliedIds + fresh,
    )
}

/** Where the defaults are remembered: the app's durable UI state, which survives upgrades. */
object KeylessDefaults {
    private const val APPLIED_KEY = "sources.keyless.defaults-applied"
    private const val ALL_OFF_KEY = "sources.keyless.all-off"

    internal var store: DurableUiStateStore = DurableUiState.store

    internal fun appliedIds(): Set<String> =
        store.getString(APPLIED_KEY)?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()

    /** True when the keeper turned every keyless Source off. */
    fun allOff(): Boolean = store.getBoolean(ALL_OFF_KEY) == true

    fun setAllOff(off: Boolean) {
        store.putBoolean(ALL_OFF_KEY, off)
    }

    /** The keyless Sources to connect at this launch ([connected] is what is connected now); then [commit] it. */
    fun pending(connected: Set<String>): KeylessDefaultsPlan =
        defaultsToApply(KeylessDefaultsState(connected, appliedIds(), allOff()))

    /** Remember [plan] after its Sources were connected, so they are not turned on again. */
    fun commit(plan: KeylessDefaultsPlan) {
        if (plan.applied != appliedIds()) store.putString(APPLIED_KEY, plan.applied.sorted().joinToString(","))
    }

    /**
     * Turn every keyless Source on, or off, through the app's own connect and disconnect: the one switch in
     * Connections. Turning on counts as applying every default, so a later disconnect sticks.
     */
    fun setAll(
        on: Boolean,
        connectedIds: Set<String>,
        connect: (String) -> Unit,
        disconnect: (String) -> Unit,
    ) {
        setAllOff(!on)
        val ids = keylessSources().map { it.id }
        if (on) {
            ids.filter { it !in connectedIds }.forEach(connect)
            commit(KeylessDefaultsPlan(emptyList(), appliedIds() + ids))
        } else {
            ids.filter { it in connectedIds }.forEach(disconnect)
        }
    }
}
