package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.Settings

private const val CURRENT_KEY_PREFIX = "aive."

// Legacy input prefix: keys older builds wrote under the app's retired name. Only ever read, then removed.
private const val LEGACY_KEY_PREFIX = "haive."

// Marker the chunked-settings decorator appends to a logical key for its manifest and chunk entries.
private const val CHUNK_MARKER = ".__chunk"

/** The legacy (retired-name) spelling of a current `aive.`-prefixed Settings key, or null for other keys. */
internal fun legacySettingsKey(key: String): String? =
    if (key.startsWith(CURRENT_KEY_PREFIX)) LEGACY_KEY_PREFIX + key.removePrefix(CURRENT_KEY_PREFIX) else null

/**
 * One-time read-side migration of a single logical key: when the legacy spelling of [key] holds a value, the
 * value is copied to [key] unless [key] already holds a real value, and the legacy key is removed. A [key] that
 * holds only an empty default (blank text, `{}`, `[]`, `null`, written by a build that opened before the migration
 * ran) does not count as a real value: the legacy value replaces it, so the keeper's setting is not lost to a default.
 * Safe to call on every store open; it does nothing once the legacy key is gone. Unreadable legacy values are left untouched.
 */
internal fun Settings.migrateLegacyKey(key: String) {
    val legacy = legacySettingsKey(key) ?: return
    if (!hasKey(legacy)) return
    val existing = if (hasKey(key)) runCatching { getStringOrNull(key) }.getOrNull() else null
    val newKeyHoldsRealValue = hasKey(key) && !(existing != null && isEmptyDefault(existing))
    if (!newKeyHoldsRealValue) {
        val text = runCatching { getStringOrNull(legacy) }.getOrNull()
        if (text != null) {
            putString(key, text)
        } else {
            val number = runCatching { getIntOrNull(legacy) }.getOrNull() ?: return
            putInt(key, number)
        }
    }
    remove(legacy)
}

private fun isEmptyDefault(value: String): Boolean = value.trim().let { it.isEmpty() || it == "{}" || it == "[]" || it == "null" }

/** [migrateLegacyKey] for [prefix] itself and every key beneath it (derived journal, bank and layout keys). */
internal fun Settings.migrateLegacyKeysWithPrefix(prefix: String) {
    val legacyPrefix = legacySettingsKey(prefix) ?: return
    val legacyKeys = runCatching { keys }.getOrNull().orEmpty()
        .filter { it.startsWith(legacyPrefix) }
        .map { it.substringBefore(CHUNK_MARKER) }
        .distinct()
    legacyKeys.forEach { legacy -> migrateLegacyKey(CURRENT_KEY_PREFIX + legacy.removePrefix(LEGACY_KEY_PREFIX)) }
}

/** [keys] plus the legacy spelling of each, so a chunked decorator can still read and retire legacy values. */
internal fun chunkedKeysWithLegacy(vararg keys: String): Set<String> =
    keys.flatMap { listOfNotNull(it, legacySettingsKey(it)) }.toSet()
