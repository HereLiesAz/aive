package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What a running session has captured but not yet sent to memory, kept against a crash. Each entry is
 * the envelope the session would send next (its parts after the capture cursor); the entry goes when
 * that envelope is in the queue, and at the next start whatever is left is sent then. Nothing here is
 * memory: it is a holding place, read once and emptied.
 */
interface MemoryCaptureJournal {
    /** Keeps [unsent] as what session [key] has not sent yet; null clears it. */
    suspend fun record(key: String, unsent: MemorySessionEnvelope?)

    /** Everything kept and not cleared, by session key. */
    suspend fun pending(): Map<String, MemorySessionEnvelope>
}

/**
 * The journal in a platform [Settings], as one JSON map under [key]. A write that would take the whole
 * journal past [maxChars] is skipped (the previous entry stays), so the journal cannot grow without bound.
 */
class SettingsMemoryCaptureJournal(
    private val settings: Settings,
    private val key: String = DEFAULT_KEY,
    private val maxChars: Int = MAX_CHARS,
) : MemoryCaptureJournal {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), MemorySessionEnvelope.serializer())

    override suspend fun record(key: String, unsent: MemorySessionEnvelope?) = mutex.withLock {
        val entries = read().toMutableMap()
        if (unsent == null) entries.remove(key) else entries[key] = unsent
        if (entries.isEmpty()) {
            settings.remove(this.key)
            return@withLock
        }
        val encoded = json.encodeToString(serializer, entries)
        if (unsent != null && encoded.length > maxChars) return@withLock
        settings.putString(this.key, encoded)
    }

    override suspend fun pending(): Map<String, MemorySessionEnvelope> = mutex.withLock { read() }

    private fun read(): Map<String, MemorySessionEnvelope> {
        val raw = settings.getStringOrNull(key) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    companion object {
        const val DEFAULT_KEY: String = "aive.memory.capture-journal.v1"
        const val MAX_CHARS: Int = 400_000
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
