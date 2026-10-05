package com.hereliesaz.geministrator.memory

import kotlinx.serialization.Serializable

/**
 * How long raw history (the full context and chat of every agent session: episode chunks and
 * prompts) is kept. This is the user's decision; the default keeps everything and nothing is pruned
 * automatically. A cap purges the oldest raw history beyond it — the single audited exception to
 * add-only storage: the raw text is removed and the episode keeps a tombstone ([MemoryRawPurge]).
 * Consolidated and current memories, their time ranges and occurrence counts never depend on raw
 * history surviving and are never touched by a purge.
 */
@Serializable
data class MemoryRawRetention(
    val mode: Mode = Mode.KeepAll,
    /** [Mode.CapBySize]: raw characters kept, newest first. */
    val maxCharacters: Long? = null,
    /** [Mode.CapByAge]: raw history older than this is purged. */
    val maxAgeMillis: Long? = null,
) {
    @Serializable
    enum class Mode { KeepAll, CapBySize, CapByAge }

    init {
        require(mode != Mode.CapBySize || (maxCharacters != null && maxCharacters >= 0)) { "A size cap needs a size" }
        require(mode != Mode.CapByAge || (maxAgeMillis != null && maxAgeMillis >= 0)) { "An age cap needs an age" }
    }

    fun describe(): String = when (mode) {
        Mode.KeepAll -> "keep all raw history"
        Mode.CapBySize -> "keep the newest $maxCharacters characters of raw history"
        Mode.CapByAge -> "keep raw history for ${maxAgeMillis!! / 86_400_000L} days"
    }

    companion object {
        val KeepAll = MemoryRawRetention()
    }
}

/** Raw history held: characters and episodes, and how many episodes have been purged. */
data class MemoryRawUsage(val characters: Long = 0, val episodes: Int = 0, val purgedEpisodes: Int = 0)

internal fun MemoryEpisode.rawCharacters(): Long =
    if (purged != null) 0 else userPrompt.length.toLong() + chunks.sumOf { it.text.length.toLong() }

internal fun Collection<MemorySnapshot>.rawUsage(): MemoryRawUsage {
    val episodes = flatMap { it.episodes }
    return MemoryRawUsage(episodes.sumOf { it.rawCharacters() }, episodes.size, episodes.count { it.purged != null })
}

/**
 * The episodes (by id) [retention] purges, across the given banks' own episodes: everything older
 * than the age cap, or the oldest beyond the size cap. Empty for [MemoryRawRetention.Mode.KeepAll].
 */
internal fun rawPurgeSelection(episodes: List<MemoryEpisode>, retention: MemoryRawRetention, nowEpochMillis: Long): Set<MemoryEpisodeId> {
    val live = episodes.filter { it.purged == null && it.rawCharacters() > 0 }
    return when (retention.mode) {
        MemoryRawRetention.Mode.KeepAll -> emptySet()
        MemoryRawRetention.Mode.CapByAge -> live.filter { nowEpochMillis - it.createdAtEpochMillis > retention.maxAgeMillis!! }.mapTo(hashSetOf()) { it.id }
        MemoryRawRetention.Mode.CapBySize -> {
            var kept = 0L
            live.sortedByDescending { it.createdAtEpochMillis }.filter { episode ->
                kept += episode.rawCharacters()
                kept > retention.maxCharacters!!
            }.mapTo(hashSetOf()) { it.id }
        }
    }
}

/** [snapshot] with [ids]' raw text removed and a tombstone on each. Nothing else changes. */
internal fun MemorySnapshot.purgeRaw(ids: Set<MemoryEpisodeId>, retention: MemoryRawRetention, setting: String, nowEpochMillis: Long): MemorySnapshot =
    copy(
        episodes = episodes.map { episode ->
            if (episode.id !in ids) return@map episode
            episode.copy(
                userPrompt = "",
                chunks = emptyList(),
                purged = MemoryRawPurge(nowEpochMillis, episode.chunks.size, episode.rawCharacters(), retention.describe(), setting),
            )
        },
    )
