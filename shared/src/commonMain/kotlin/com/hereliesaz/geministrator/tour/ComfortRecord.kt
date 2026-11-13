package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.DurableUiStateStore
import com.hereliesaz.geministrator.control.ControlRedaction

/** How the keeper said a concept felt. Never a grade. */
enum class Comfort { Comfortable, Shaky }

data class ComfortEntry(val comfort: Comfort, val count: Int, val atMillis: Long)

/**
 * Per concept (a registry target id), whether it felt comfortable or shaky, a counter and a time. It holds no text:
 * keys are closed registry ids and values are an enum and two numbers, so a note the keeper typed cannot reach it.
 * At most one entry per id.
 */
data class ComfortRecord(val entries: Map<String, ComfortEntry> = emptyMap()) {
    fun mark(id: String, comfort: Comfort, nowMillis: Long): ComfortRecord {
        val old = entries[id]
        val count = if (old != null && old.comfort == comfort) old.count + 1 else 1
        return copy(entries = entries + (id to ComfortEntry(comfort, count, nowMillis)))
    }

    fun shakyIds(): List<String> = entries.filter { it.value.comfort == Comfort.Shaky }.keys.sorted()
    fun comfortableIds(): List<String> = entries.filter { it.value.comfort == Comfort.Comfortable }.keys.sorted()
}

/** Encodes and decodes the record as lines `ID|C or S|count|millis`, keeping only ids in [allowedIds]. */
object ComfortCodec {
    fun encode(record: ComfortRecord): String = record.entries.entries.sortedBy { it.key }.joinToString("\n") { (id, e) ->
        "$id|${if (e.comfort == Comfort.Comfortable) "C" else "S"}|${e.count}|${e.atMillis}"
    }

    fun decode(text: String?, allowedIds: Set<String>): ComfortRecord {
        if (text.isNullOrBlank()) return ComfortRecord()
        val map = mutableMapOf<String, ComfortEntry>()
        for (line in text.lines()) {
            val p = line.split('|')
            if (p.size != 4 || p[0] !in allowedIds) continue
            val comfort = when (p[1]) {
                "C" -> Comfort.Comfortable
                "S" -> Comfort.Shaky
                else -> continue
            }
            val count = p[2].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: continue
            val at = p[3].toLongOrNull() ?: continue
            map[p[0]] = ComfortEntry(comfort, count, at)
        }
        return ComfortRecord(map)
    }
}

/** Device-local storage in the existing ui-state store, under one key. Cleared by "Clear remembered text" and Delete everything. */
internal class ComfortStore(private val store: DurableUiStateStore, private val allowedIds: Set<String>) {
    fun load(): ComfortRecord = ComfortCodec.decode(store.getString(KEY), allowedIds)

    fun save(record: ComfortRecord) {
        store.putString(KEY, ComfortCodec.encode(record))
    }

    fun clear() = store.remove(KEY)

    companion object {
        const val KEY = "aive.tour.comfort.v1"
    }
}

/** Cleans the one free-text line the keeper may type after "Lost me": untrusted, so stripped, cut and scrubbed. */
object KeeperNote {
    fun clean(raw: String): String {
        val plain = raw.filter { !it.isISOControl() && it != '<' && it != '>' && it != '`' }
        return ControlRedaction.scrub(plain).trim().take(TeachingLimits.CHECKIN_NOTE_MAX_CHARS)
    }
}
