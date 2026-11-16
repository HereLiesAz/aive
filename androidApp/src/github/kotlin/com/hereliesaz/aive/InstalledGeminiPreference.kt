package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Explicit user opt-in for using the installed Gemini consumer app as a Aive transport. */
internal object InstalledGeminiPreference {
    const val isSupported: Boolean = true

    private const val PREFERENCES = "aive.external-ai"
    // Legacy input name: older builds kept the setting in this file; it is migrated on first open.
    private const val LEGACY_PREFERENCES = "haive.external-ai"
    private const val KEY_ENABLED = "installed-gemini-enabled"

    fun isEnabled(context: Context): Boolean =
        LegacyPreferences.open(context, PREFERENCES, LEGACY_PREFERENCES)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        LegacyPreferences.open(context, PREFERENCES, LEGACY_PREFERENCES)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun requestEnable(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
