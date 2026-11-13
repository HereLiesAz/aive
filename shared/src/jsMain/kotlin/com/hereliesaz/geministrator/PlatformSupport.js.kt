package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = block()

internal actual fun normalizeNfc(text: String): String = text.asDynamic().normalize("NFC") as String
