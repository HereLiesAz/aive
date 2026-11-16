package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun normalizeNfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

internal actual fun deviceUtcOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

internal actual fun isDesktopClassDevice(): Boolean {
    val c = android.content.res.Resources.getSystem().configuration
    // A Chromebook runs the app under ChromeOS's Android container, whatever its screen size.
    val chromeOs = android.os.Build.DEVICE.orEmpty().let { it.endsWith("_cheets") || it.contains("cros", ignoreCase = true) }
    // A tablet (the shortest side of the screen at least 720dp), a freeform desktop window on one included, is desktop-class.
    return chromeOs || c.smallestScreenWidthDp >= 720
}
