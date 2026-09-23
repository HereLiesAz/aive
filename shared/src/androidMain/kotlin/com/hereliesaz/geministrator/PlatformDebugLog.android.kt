package com.hereliesaz.geministrator

import android.util.Log

internal actual fun platformDebugLog(tag: String, message: String) {
    Log.d(tag, message)
}
