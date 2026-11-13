package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.Settings

/**
 * A platform that can keep some settings in a file of its own sets [separate], before anything reads them. Android
 * does, so its backup rules can leave exactly these out of backup and device transfer: the MCP server list and the
 * trace exporter's OTLP headers (which may carry a token). Elsewhere [forKeys] is the platform's general settings.
 */
object PrivateSettings {
    @Volatile
    var separate: Settings? = null

    /**
     * The settings that hold [keys]. With a separate file, each key (and its `.corrupt` copy) found in the general
     * settings is moved across once, unless the separate file already has it.
     */
    fun forKeys(vararg keys: String): Settings {
        val own = separate ?: return Settings()
        val general = Settings()
        for (base in keys) {
            for (key in listOf(base, base + CORRUPT_SUFFIX)) {
                val old = runCatching { general.getStringOrNull(key) }.getOrNull() ?: continue
                if (own.getStringOrNull(key) == null) own.putString(key, old)
                runCatching { general.remove(key) }
            }
        }
        return own
    }
}
