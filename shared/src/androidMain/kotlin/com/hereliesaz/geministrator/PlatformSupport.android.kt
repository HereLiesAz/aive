package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun normalizeNfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

internal actual fun deviceUtcOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

internal actual fun isDesktopClassDevice(): Boolean {
    val c = android.content.res.Resources.getSystem().configuration
    val desktopMode = (c.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_DESKTOP
    // A tablet (the shortest side of the screen at least 720dp) is desktop-class, with or without a keyboard.
    return desktopMode || c.smallestScreenWidthDp >= 720
}
