package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun normalizeNfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
