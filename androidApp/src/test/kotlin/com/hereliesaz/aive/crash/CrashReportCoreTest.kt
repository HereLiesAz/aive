package com.hereliesaz.aive.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrashReportCoreTest {
    private class MapStore : CrashReportKeyValueStore {
        val values = mutableMapOf<String, Any?>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { values[key] = value }
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun putLong(key: String, value: Long) { values[key] = value }
    }

    private fun throwAt(line: Int, message: String): Throwable =
        IllegalStateException(message).apply {
            stackTrace = arrayOf(
                StackTraceElement("com.x.Foo", "bar", "Foo.kt", line),
                StackTraceElement("com.x.Main", "run", "Main.kt", 10),
            )
        }

    @Test
    fun signatureIgnoresMessageAndLineNumbers() {
        assertEquals(CrashSignatures.of(throwAt(1, "a")), CrashSignatures.of(throwAt(99, "b")))
        assertEquals(16, CrashSignatures.of(throwAt(1, "a")).length)
    }

    @Test
    fun signatureDiffersByExceptionTypeAndFrames() {
        val base = throwAt(1, "a")
        val otherType = IllegalArgumentException("a").apply { stackTrace = base.stackTrace }
        val otherFrame = IllegalStateException("a").apply {
            stackTrace = arrayOf(StackTraceElement("com.x.Foo", "baz", "Foo.kt", 1))
        }
        assertNotEquals(CrashSignatures.of(base), CrashSignatures.of(otherType))
        assertNotEquals(CrashSignatures.of(base), CrashSignatures.of(otherFrame))
    }

    @Test
    fun signatureUsesRootCause() {
        val cause = throwAt(1, "root")
        assertEquals(CrashSignatures.of(cause), CrashSignatures.of(RuntimeException("wrap", cause)))
    }

    @Test
    fun anrSignatureFromMainThreadFramesIgnoresLineNumbers() {
        val trace = { line: Int ->
            """
            "Signal Catcher" daemon prio=10
              at other.Thread.run(Thread.java:1)

            "main" prio=5 tid=1 Blocked
              | group="main"
              at com.x.Foo.bar(Foo.kt:$line)
              - waiting to lock <0x1>
              at android.os.Looper.loop(Looper.java:288)

            "worker" prio=5
              at com.x.W.run(W.kt:1)
            """.trimIndent()
        }
        val frames = CrashSignatures.mainThreadFrames(trace(12))
        assertEquals(listOf("com.x.Foo.bar(Foo.kt:12)", "android.os.Looper.loop(Looper.java:288)"), frames)
        assertEquals(
            CrashSignatures.ofAnr("x", frames),
            CrashSignatures.ofAnr("y", CrashSignatures.mainThreadFrames(trace(40))),
        )
        assertNotEquals(CrashSignatures.ofAnr("x", frames), CrashSignatures.of(throwAt(1, "a")))
    }

    @Test
    fun reportingFollowsPreReleaseDefaultAndCanBeToggled() {
        val store = MapStore()
        val policy = CrashReportPolicy(store)
        assertEquals(CrashReportPolicy.DEFAULT_ENABLED, policy.enabled)
        policy.enabled = false
        assertFalse(CrashReportPolicy(store).enabled)
        policy.enabled = true
        assertTrue(CrashReportPolicy(store).enabled)
    }

    @Test
    fun firstReportNoticeShownOnce() {
        val store = MapStore()
        assertFalse(CrashReportPolicy(store).firstReportNoticeShown)
        CrashReportPolicy(store).markFirstReportNoticeShown()
        assertTrue(CrashReportPolicy(store).firstReportNoticeShown)
    }

    @Test
    fun dedupsSignaturePerAppVersion() {
        val policy = CrashReportPolicy(MapStore())
        assertFalse(policy.alreadySent("abc", "1.0"))
        policy.recordSent("abc", "1.0")
        assertTrue(policy.alreadySent("abc", "1.0"))
        assertFalse(policy.alreadySent("def", "1.0"))
        assertFalse(policy.alreadySent("abc", "1.1"), "a new app version re-reports")
        policy.recordSent("def", "1.1")
        assertFalse(policy.alreadySent("abc", "1.0") && policy.alreadySent("abc", "1.1"))
    }

    @Test
    fun dedupHistoryIsBounded() {
        val policy = CrashReportPolicy(MapStore(), maxRemembered = 3)
        listOf("a", "b", "c", "d").forEach { policy.recordSent(it, "1") }
        assertFalse(policy.alreadySent("a", "1"))
        assertTrue(policy.alreadySent("d", "1"))
    }

    @Test
    fun relayJsonEscapesAndMarksAnr() {
        val json = CrashReport(
            CrashReport.Kind.ANR, "0123456789abcdef", "ApplicationNotResponding: \"x\"\n\tat a.B(C)",
            "1.0", 3, "com.hereliesaz.haive", "14 (API 34)", "g", "p", "2026-01-01T00:00:00Z",
        ).toRelayJson()
        assertTrue(json.contains("\"STACK_TRACE\":\"ApplicationNotResponding: \\\"x\\\"\\n\\tat a.B(C)\""))
        assertTrue(json.contains("\"PACKAGE_NAME\":\"com.hereliesaz.haive\""))
        assertEquals("\"\\u0001\"", CrashReport.jsonString("\u0001"))
    }
}
