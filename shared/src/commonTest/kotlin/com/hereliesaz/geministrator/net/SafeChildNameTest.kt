package com.hereliesaz.geministrator.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeChildNameTest {
    @Test
    fun plainNamesPass() {
        listOf("README.md", "src", ".git", "a b", "name..with..dots", "é.txt").forEach { assertTrue(isSafeChildName(it), it) }
    }

    @Test
    fun pathsAndControlNamesAreRefused() {
        listOf("", ".", "..", "../x", "a/b", "a\\b", "/etc/passwd", "a\u0000b", "a\nb").forEach { assertFalse(isSafeChildName(it), it) }
    }
}
