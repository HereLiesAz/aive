package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Explicit user opt-in for using the installed Gemini consumer app as a Haive transport. */
internal object InstalledGeminiPreference {
    const val isSupported: Boolean = true

    private const val PREFERENCES = "haive.external-ai"
    private const val KEY_ENABLED = "installed-gemini-enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
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
