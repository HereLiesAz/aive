package com.hereliesaz.geministrator.memory

/** Version metadata: earliest time (epoch millis) over every contributing source. */
const val MEMORY_TIME_FROM: String = "timeFrom"

/** Version metadata: latest time (epoch millis) over every contributing source. */
const val MEMORY_TIME_TO: String = "timeTo"

/**
 * Time on a memory. A raw memory keeps its exact time: the date its text states (ISO yyyy-mm-dd),
 * else its earliest source episode's time, else its record time. A consolidated current version
 * carries a time range instead, `[earliest, latest]` over all its contributors, and every further
 * consolidation takes the union of the ranges. Recall shows the range; exact times stay with the
 * history ([historyOf]).
 */
internal object MemoryTimeRange {
    fun of(snapshot: MemorySnapshot, node: MemoryNode): LongRange {
        val from = node.metadata[MEMORY_TIME_FROM]?.toLongOrNull()
        val to = node.metadata[MEMORY_TIME_TO]?.toLongOrNull()
        if (from != null && to != null) return from..to
        val exact = exactTime(snapshot, node)
        return exact..exact
    }

    fun exactTime(snapshot: MemorySnapshot, node: MemoryNode): Long {
        STATED_DATE.find(node.text)?.let { match ->
            val (y, m, d) = match.destructured
            return daysFromCivil(y.toInt(), m.toInt(), d.toInt()) * DAY_MILLIS
        }
        val episodes = snapshot.episodes.associateBy { it.id }
        return node.sourceEpisodeIds.mapNotNull { episodes[it]?.createdAtEpochMillis }.minOrNull() ?: node.createdAtEpochMillis
    }

    /** The union of [contributors]' ranges, as version metadata. */
    fun metadataFor(snapshot: MemorySnapshot, contributors: Collection<MemoryNodeId>): Map<String, String> {
        val ids = contributors.toSet()
        val ranges = snapshot.nodes.filter { it.id in ids }.map { of(snapshot, it) }
        if (ranges.isEmpty()) return emptyMap()
        return mapOf(MEMORY_TIME_FROM to ranges.minOf { it.first }.toString(), MEMORY_TIME_TO to ranges.maxOf { it.last }.toString())
    }

    /** `yyyy-mm-dd – yyyy-mm-dd` (or one date) for a version's range; null for a raw memory. */
    fun label(node: MemoryNode): String? {
        val from = node.metadata[MEMORY_TIME_FROM]?.toLongOrNull() ?: return null
        val to = node.metadata[MEMORY_TIME_TO]?.toLongOrNull() ?: return null
        val a = isoDate(from)
        val b = isoDate(to)
        return if (a == b) a else "$a – $b"
    }

    fun isoDate(epochMillis: Long): String {
        val (y, m, d) = civilFromDays(epochMillis.floorDiv(DAY_MILLIS))
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    // Howard Hinnant's days_from_civil / civil_from_days (proleptic Gregorian).
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }

    private const val DAY_MILLIS = 86_400_000L
    private val STATED_DATE = Regex("\\b((?:19|20)\\d\\d)-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b")
}
