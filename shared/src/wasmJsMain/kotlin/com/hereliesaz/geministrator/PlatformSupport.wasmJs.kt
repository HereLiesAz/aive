package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = block()

private fun jsNormalizeNfc(text: String): String = js("text.normalize('NFC')")

internal actual fun normalizeNfc(text: String): String = jsNormalizeNfc(text)

private fun jsDeviceOffsetMinutes(): Int = js("-new Date().getTimezoneOffset()")

internal actual fun deviceUtcOffsetMinutes(): Int = jsDeviceOffsetMinutes()
