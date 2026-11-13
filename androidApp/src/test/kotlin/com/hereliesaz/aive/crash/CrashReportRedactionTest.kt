package com.hereliesaz.aive.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportRedactionTest {
    @Test
    fun credentialsUrlsAndAddressesAreRemoved() {
        val trace = """
            java.io.IOException: HTTP 401 from https://user:hunter2@api.example.com/v1/x?api_key=abcd1234efgh&b=1
            Authorization: Bearer abcdefghijklmnop12345
            token=ghp_abcdefghijklmnopqrstuvwx
            contact me@example.com about aive1:AbCdEfGhIjKlMnOpQrStUvWx
        """.trimIndent()
        val redacted = redactCrashTrace(trace)
        listOf("hunter2", "abcd1234efgh", "abcdefghijklmnop12345", "ghp_abcdefghijklmnopqrstuvwx", "me@example.com", "AbCdEfGhIjKlMnOpQrStUvWx")
            .forEach { assertFalse(it in redacted, "still contains $it") }
        assertTrue("java.io.IOException: HTTP 401" in redacted)
    }

    @Test
    fun pathsLongNumbersAndLongMessagesAreReduced() {
        val trace = "java.io.FileNotFoundException: /home/alice/notes/plan.txt id 1234567890123 hash 0123456789abcdef0123 " + "x".repeat(500)
        val redacted = redactCrashTrace(trace)
        assertFalse("alice" in redacted)
        assertFalse("1234567890123" in redacted)
        assertFalse("0123456789abcdef0123" in redacted)
        assertTrue(redacted.length <= 300)
        assertFalse("alice" in redactCrashTrace("C:\\Users\\alice\\x.txt"))
    }

    @Test
    fun anOrdinaryStackFrameIsUnchanged() {
        val frame = "at com.hereliesaz.aive.MainActivity.onCreate(MainActivity.kt:154)"
        assertEquals(frame, redactCrashTrace(frame))
    }
}
