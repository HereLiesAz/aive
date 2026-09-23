package com.hereliesaz.geministrator

internal actual fun platformDebugLog(tag: String, message: String) {
    println("D/$tag: $message")
}
