package com.hereliesaz.aive

import android.content.Context

/** Play builds never enable consumer-app accessibility automation. */
internal object InstalledGeminiPreference {
    fun isEnabled(context: Context): Boolean = false
    fun setEnabled(context: Context, enabled: Boolean) = Unit
    fun requestEnable(context: Context) = Unit
}
