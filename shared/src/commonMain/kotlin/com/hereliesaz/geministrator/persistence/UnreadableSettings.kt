package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.Settings

/**
 * Settings-backed JSON stores used to treat an unreadable value as empty and then overwrite it on the
 * next write, destroying whatever was recoverable. These helpers keep the raw text under `<key>.corrupt`
 * and make writers refuse while the live value is still unreadable.
 */
internal const val CORRUPT_SUFFIX = ".corrupt"

/** The decoded value, or null when absent or unreadable (the unreadable raw text is kept under `<key>.corrupt`). */
internal fun <T : Any> Settings.readQuarantining(key: String, decode: (String) -> T): T? {
    val raw = getStringOrNull(key) ?: return null
    return try {
        decode(raw)
    } catch (failure: Exception) {
        keepCorruptCopy(key, raw)
        null
    }
}

/** Throws [PersistenceCorruptionException] when the stored value at [key] exists but cannot be decoded. */
internal fun Settings.refuseIfUnreadable(key: String, decode: (String) -> Unit) {
    val raw = getStringOrNull(key) ?: return
    try {
        decode(raw)
    } catch (failure: Exception) {
        keepCorruptCopy(key, raw)
        throw PersistenceCorruptionException(
            "Stored value '$key' is unreadable; it was kept under '$key$CORRUPT_SUFFIX' and was not overwritten.",
            failure,
        )
    }
}

private fun Settings.keepCorruptCopy(key: String, raw: String) {
    // Keep the first unreadable copy: later reads of the same bytes add nothing, and a changed value deserves its own.
    val backup = key + CORRUPT_SUFFIX
    if (getStringOrNull(backup) != raw) runCatching { putString(backup, raw) }
}
