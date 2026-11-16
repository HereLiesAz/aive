package com.hereliesaz.geministrator

internal actual fun <T> withPlatformLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }

internal actual fun normalizeNfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

internal actual fun deviceUtcOffsetMinutes(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

internal actual fun isDesktopClassDevice(): Boolean {
    val c = android.content.res.Resources.getSystem().configuration
    val desktopMode = (c.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_DESKTOP
    val keyboardTablet = c.smallestScreenWidthDp >= 600 &&
        c.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
        c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    return desktopMode || keyboardTablet
}
