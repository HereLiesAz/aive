package com.hereliesaz.aive

import android.content.Context

/** Play builds do not ship the automatic GitHub crash reporter. */
internal object CrashReporting {
    const val isSupported: Boolean = false

    fun isEnabled(context: Context): Boolean = false
    fun setEnabled(context: Context, enabled: Boolean) = Unit
    fun install(context: Context, onFirstReportSent: () -> Unit) = Unit
    fun markFirstReportNoticeShown(context: Context) = Unit
}
