package com.hereliesaz.geministrator

/** Debug-level diagnostic log line (Logcat on Android, stdout elsewhere). */
internal expect fun platformDebugLog(tag: String, message: String)
