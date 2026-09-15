package com.hereliesaz.geministrator.memory

/**
 * Programmatic temporal organization for memory episodes.
 *
 * This is a derived index, not another learned memory layer. It is rebuilt deterministically from
 * immutable episode timestamps, so temporal compaction never deletes or rewrites remembered
 * experience. Older fine-grained windows simply stop being active retrieval buckets once their
 * episode provenance is represented by a coarser active bucket.
 */
enum class MemoryTemporalLevel(
    val durationMillis: Long,
    val maxActiveBuckets: Int?,
) {
    FifteenMinutes(15L * 60L * 1_000L, 8),
    OneHour(60L * 60L * 1_000L, 6),
    SixHours(6L * 60L * 60L * 1_000L, 3),
    TwelveHours(12L * 60L * 60L * 1_000L, 2),
    Day(24L * 60L * 60L * 1_000L, 7),
    Week(7L * 24L * 60L * 60L * 1_000L, null),
    ;

    val parent: MemoryTemporalLevel?
        get() = when (this) {
            FifteenMinutes -> OneHour
            OneHour -> SixHours
            SixHours -> TwelveHours
            TwelveHours -> Day
            Day -> Week
            Week -> null
        }
}

data class MemoryTemporalBucket(
    val level: MemoryTemporalLevel,
    val startEpochMillis: Long,
    val endEpochMillisExclusive: Long,
    val episodeIds: Set<MemoryEpisodeId>,
    /** IDs of finer buckets represented by this bucket, retained for derived-index provenance. */
    val sourceBucketIds: Set<String> = emptySet(),
) {
    val id: String = "${level.name}:$startEpochMillis"

    init {
        require(endEpochMillisExclusive > startEpochMillis)
        require(episodeIds.isNotEmpty())
    }
}

data class MemoryTemporalIndex(
    val buckets: List<MemoryTemporalBucket>,
) {
    fun bucketsForEpisode(episodeId: MemoryEpisodeId): List<MemoryTemporalBucket> =
        buckets.filter { episodeId in it.episodeIds }

    companion object {
        fun build(episodes: Collection<MemoryEpisode>): MemoryTemporalIndex {
            if (episodes.isEmpty()) return MemoryTemporalIndex(emptyList())

            val byLevel = MemoryTemporalLevel.entries.associateWith {
                linkedMapOf<Long, MutableTemporalBucket>()
            }

            episodes
                .sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .forEach { episode ->
                    val level = MemoryTemporalLevel.FifteenMinutes
                    val start = align(episode.createdAtEpochMillis, level.durationMillis)
                    val bucket = byLevel.getValue(level).getOrPut(start) {
                        MutableTemporalBucket(level, start)
                    }
                    bucket.episodeIds += episode.id
                }

            MemoryTemporalLevel.entries.forEach { level ->
                val cap = level.maxActiveBuckets ?: return@forEach
                val parent = level.parent ?: return@forEach
                val active = byLevel.getValue(level)
                if (active.size <= cap) return@forEach
                val parentBuckets = byLevel.getValue(parent)

                // Group once by parent window and process oldest parent groups first. Keep this in
                // common-Kotlin collection APIs so the same implementation compiles on JVM, JS,
                // and Wasm. The previous implementation repeatedly rescanned the entire active map
                // for every rollup, which became quadratic for sparse long-lived histories.
                val childrenByParent = active.values
                    .groupBy { child -> align(child.startEpochMillis, parent.durationMillis) }
                    .entries
                    .sortedBy { it.key }

                for ((parentStart, children) in childrenByParent) {
                    if (active.size <= cap) break
                    val rolled = parentBuckets.getOrPut(parentStart) {
                        MutableTemporalBucket(parent, parentStart)
                    }
                    children
                        .sortedBy(MutableTemporalBucket::startEpochMillis)
                        .forEach { child ->
                            rolled.episodeIds += child.episodeIds
                            rolled.sourceBucketIds += child.id
                            rolled.sourceBucketIds += child.sourceBucketIds
                            active.remove(child.startEpochMillis)
                        }
                }
            }

            return MemoryTemporalIndex(
                buckets = MemoryTemporalLevel.entries.flatMap { level ->
                    byLevel.getValue(level).values
                        .sortedBy(MutableTemporalBucket::startEpochMillis)
                        .map(MutableTemporalBucket::freeze)
                },
            )
        }

        private fun align(timestamp: Long, duration: Long): Long =
            (timestamp / duration) * duration
    }
}

private class MutableTemporalBucket(
    val level: MemoryTemporalLevel,
    val startEpochMillis: Long,
) {
    val id: String = "${level.name}:$startEpochMillis"
    val episodeIds: LinkedHashSet<MemoryEpisodeId> = linkedSetOf()
    val sourceBucketIds: LinkedHashSet<String> = linkedSetOf()

    fun freeze(): MemoryTemporalBucket = MemoryTemporalBucket(
        level = level,
        startEpochMillis = startEpochMillis,
        endEpochMillisExclusive = startEpochMillis + level.durationMillis,
        episodeIds = episodeIds.toSet(),
        sourceBucketIds = sourceBucketIds.toSet(),
    )
}
