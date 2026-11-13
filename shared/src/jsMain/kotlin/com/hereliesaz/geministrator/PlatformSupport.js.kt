package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = block()

internal actual fun normalizeNfc(text: String): String = text.asDynamic().normalize("NFC") as String

internal actual fun deviceUtcOffsetMinutes(): Int = (-kotlin.js.Date().getTimezoneOffset()).toInt()
