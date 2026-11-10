package com.hereliesaz.geministrator.testing

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings

/** MapSettings that throws like java.util.prefs.Preferences: keys over 80 chars, string values over 8192 chars. */
internal class PreferencesLimitedSettings(
    private val delegate: MapSettings = MapSettings(),
) : Settings by delegate {
    override fun putString(key: String, value: String) {
        require(key.length <= MAX_KEY_LENGTH) { "Key too long (${key.length}): $key" }
        require(value.length <= MAX_VALUE_LENGTH) { "Value too long (${value.length}) for $key" }
        delegate.putString(key, value)
    }

    companion object {
        const val MAX_KEY_LENGTH = 80
        const val MAX_VALUE_LENGTH = 8192
    }
}
