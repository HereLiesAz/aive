package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider

/**
 * Where the connected agents can work: with no repository at all, and inside which kinds of
 * repository. Feeding a hive is gated on it (docs/Keepers-Room.md, First launch), so Feed it
 * leads to what is missing instead of starting work no agent can take.
 */
data class ProviderReach(
    /** Any agent the app may choose on its own. */
    val anyProvider: Boolean,
    /** Some agent works with no repository. */
    val withoutRepository: Boolean,
    /** Kinds of repository some agent works inside. */
    val sources: Set<RepositorySource>,
) {
    /** What feeding a hive with [repository] (null: none) still needs. */
    fun gateFor(repository: RepositorySource?): FeedGate = when {
        !anyProvider -> FeedGate.Provider
        repository == null && !withoutRepository -> FeedGate.Repository(sources)
        repository != null && repository !in sources ->
            if (withoutRepository) FeedGate.UnsupportedRepository(repository, sources) else FeedGate.Repository(sources)
        else -> FeedGate.Open
    }

    companion object {
        /** Before the agents have answered: assume they can, so nothing flashes a gate. */
        val Unknown = ProviderReach(anyProvider = true, withoutRepository = true, sources = RepositorySource.entries.toSet())
    }
}

/** What stands between a hive and its first meal, and where its resolution lives. */
sealed interface FeedGate {
    data object Open : FeedGate

    /** No agent at all: resolved in the Keeper's room, Connections. */
    data object Provider : FeedGate

    /** Every agent works inside a repository: resolved by giving the hive one of [sources]. */
    data class Repository(val sources: Set<RepositorySource>) : FeedGate

    /** No agent works in a [source] repository: resolved by another repository, or none. */
    data class UnsupportedRepository(val source: RepositorySource, val sources: Set<RepositorySource>) : FeedGate
}

internal suspend fun providerReach(providers: Collection<AgentProvider>): ProviderReach {
    // The on-device provider takes only small text tasks; it does not feed a hive by itself.
    val usable = providers.filterNot { it.explicitOnly || it is OnDeviceTextAgentProvider }
    suspend fun works(repository: RepositoryRef?) =
        usable.any { provider -> runCatching { provider.supportsRepository(repository) }.getOrDefault(false) }
    return ProviderReach(
        anyProvider = usable.isNotEmpty(),
        withoutRepository = works(null),
        sources = RepositorySource.entries.filterTo(linkedSetOf()) { source ->
            // A stand-in of that kind: providers decide by its source (and a local one by having a path).
            works(
                RepositoryRef(
                    owner = "reach",
                    name = "reach",
                    source = source,
                    localPath = "/reach".takeIf { source == RepositorySource.Local },
                ),
            )
        },
    )
}
