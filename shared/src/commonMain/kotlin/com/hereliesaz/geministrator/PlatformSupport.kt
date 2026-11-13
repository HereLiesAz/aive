package com.hereliesaz.geministrator

/**
 * Runs [block] while holding [lock]'s monitor where the platform has threads (Android, desktop); where it has
 * none (the browser) it just runs [block]. Reentrant. Keep [block] short and never suspend inside it.
 */
internal expect fun <T> withPlatformLock(lock: Any, block: () -> T): T

/** [text] in Unicode normalization form C, so the same typed characters compare and hash the same on every platform. */
internal expect fun normalizeNfc(text: String): String
