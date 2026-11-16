package com.hereliesaz.geministrator

/**
 * Runs [block] while holding [lock]'s monitor where the platform has threads (Android, desktop); where it has
 * none (the browser) it just runs [block]. Reentrant. Keep [block] short and never suspend inside it.
 */
internal expect fun <T> withPlatformLock(lock: Any, block: () -> T): T

/** [text] in Unicode normalization form C, so the same typed characters compare and hash the same on every platform. */
internal expect fun normalizeNfc(text: String): String

/** This device's current offset from UTC in minutes (east is positive), read from its own clock settings. */
internal expect fun deviceUtcOffsetMinutes(): Int

/**
 * True where this device is desktop-class: desktop itself, Android in desktop mode or on a tablet (the shortest side of the
 * screen at least 720dp), and the web app (PWA) on a desktop or a tablet (the shortest side of the screen at least 720 px). Such
 * a device gets the wide layouts from [ControlRoomBreakpoints.DesktopWide] up; a phone, from [ControlRoomBreakpoints.PhoneWide].
 */
internal expect fun isDesktopClassDevice(): Boolean
