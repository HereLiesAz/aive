package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.log.AppLog

/** Debug-level diagnostic log line (Logcat on Android, stdout elsewhere). */
internal expect fun platformDebugLog(tag: String, message: String)

/**
 * The app's diagnostic line: printed where the platform prints, and kept in the [AppLog] the Log tab
 * shows (dated, secrets scrubbed, the newest 500, kept across restarts). Use this, not [platformDebugLog].
 */
internal fun appLog(tag: String, message: String) {
    platformDebugLog(tag, message)
    runCatching { AppLog.record(tag, message) }
}
