package com.hereliesaz.aive

import android.content.Context
import android.content.SharedPreferences

/**
 * Read-side migration for plain preferences files that older builds named after the app's previous
 * name: the first time the current file is opened, every entry of the legacy file that the current
 * file lacks is copied across and the legacy file is deleted.
 */
internal object LegacyPreferences {
    /** The preferences file [currentName], after moving any entries from [legacyName] into it. */
    fun open(context: Context, currentName: String, legacyName: String): SharedPreferences {
        val app = context.applicationContext
        val current = app.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val entries = legacy.all
        if (entries.isEmpty()) return current
        val copied = runCatching {
            val editor = current.edit()
            entries.forEach { (key, value) ->
                if (current.contains(key)) return@forEach
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
            editor.commit()
        }.getOrDefault(false)
        if (copied) app.deleteSharedPreferences(legacyName)
        return current
    }
}
