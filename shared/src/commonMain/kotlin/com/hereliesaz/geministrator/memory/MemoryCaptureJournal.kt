package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.log.AppLog
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
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
 * journal past [maxChars] drops that session's older entry rather than leaving it to be replayed stale, so the
 * journal cannot grow without bound. The value is chunked (desktop's Preferences reject a value over 8,192
 * characters) and a plain value an older build wrote under [key] is still read and then replaced.
 */
class SettingsMemoryCaptureJournal(
    settings: Settings,
    private val key: String = DEFAULT_KEY,
    private val maxChars: Int = MAX_CHARS,
) : MemoryCaptureJournal {
    private val settings: Settings = ChunkedStringSettings(settings, setOf(key, corruptKey(key)), onlyWhenLarge = true)
    private val mutex = Mutex()
    private val codec = MapSerializer(String.serializer(), MemorySessionEnvelope.serializer())

    override suspend fun record(key: String, unsent: MemorySessionEnvelope?) = mutex.withLock {
        val entries = read().toMutableMap()
        if (unsent == null) entries.remove(key) else entries[key] = unsent
        if (entries.isEmpty()) {
            settings.remove(this.key)
            return@withLock
        }
        var encoded = json.encodeToString(codec, entries)
        if (unsent != null && encoded.length > maxChars) {
            // Too big to keep: the older envelope for this session would replay as if it were current, so it goes too.
            entries.remove(key)
            AppLog.record("memory", "A session's memory capture was too large for the crash journal and was dropped from it.")
            if (entries.isEmpty()) {
                settings.remove(this.key)
                return@withLock
            }
            encoded = json.encodeToString(codec, entries)
        }
        settings.putString(this.key, encoded)
    }

    override suspend fun pending(): Map<String, MemorySessionEnvelope> = mutex.withLock { read() }

    /** The kept entries; a journal that cannot be read is moved aside (not overwritten) and read as empty. */
    private fun read(): Map<String, MemorySessionEnvelope> {
        val raw = try {
            settings.getStringOrNull(key)
        } catch (error: Exception) {
            AppLog.record("memory", "The memory crash journal could not be read: ${error.message ?: error::class.simpleName}")
            return emptyMap()
        } ?: return emptyMap()
        return try {
            json.decodeFromString(codec, raw)
        } catch (error: SerializationException) {
            keepAside(raw, error)
            emptyMap()
        } catch (error: IllegalArgumentException) {
            keepAside(raw, error)
            emptyMap()
        }
    }

    private fun keepAside(raw: String, error: Exception) {
        AppLog.record(
            "memory",
            "The memory crash journal was unreadable and was kept aside under ${corruptKey(key)}: ${error.message ?: error::class.simpleName}",
        )
        runCatching {
            settings.putString(corruptKey(key), raw)
            settings.remove(key)
        }.onFailure {
            // Leave the unreadable value where it is: the next write then replaces it, but nothing else is lost silently.
            AppLog.record("memory", "The unreadable memory crash journal could not be kept aside: ${it.message ?: it::class.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_KEY: String = "aive.memory.capture-journal.v1"
        const val MAX_CHARS: Int = 400_000
        internal fun corruptKey(key: String): String = "$key.corrupt"
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
