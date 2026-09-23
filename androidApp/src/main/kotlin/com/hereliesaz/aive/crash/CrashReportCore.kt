package com.hereliesaz.aive.crash

import java.security.MessageDigest

/**
 * Platform-free crash-reporting logic (signatures, dedup, settings defaults, payloads) so it can be
 * unit-tested on the JVM. Android wiring lives in the `github` flavor's `CrashReporting`.
 */

/** Minimal key-value store; production uses SharedPreferences, tests use a map. */
interface CrashReportKeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

object CrashSignatures {
    const val TOP_FRAMES = 5

    /** Stable id of a crash: root-cause class + top frames (class.method, no line numbers). */
    fun of(throwable: Throwable): String {
        var root = throwable
        val seen = HashSet<Throwable>()
        while (root.cause != null && seen.add(root)) root = root.cause!!
        val frames = root.stackTrace.take(TOP_FRAMES).map { "${it.className}.${it.methodName}" }
        return hash(listOf("crash", root.javaClass.name) + frames)
    }

    /** Stable id of an ANR: main-thread top frames from the system trace, or the description. */
    fun ofAnr(description: String?, mainThreadFrames: List<String>): String {
        val frames = mainThreadFrames.take(TOP_FRAMES).map { it.replace(Regex(":\\d+\\)"), ")").trim() }
        val basis = if (frames.isNotEmpty()) frames else listOf(description.orEmpty().replace(Regex("\\d+"), "#"))
        return hash(listOf("anr") + basis)
    }

    /** Pulls the "main" thread's `at ...` frames out of an ART ANR trace dump. */
    fun mainThreadFrames(trace: String): List<String> {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { it.isNotBlank() }
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .map { it.removePrefix("at ") }
    }

    private fun hash(parts: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}

/**
 * Opt-out setting, first-report notice flag, and per-app-version signature dedup.
 * A signature is sent at most once per app version (crash loops file one report, not hundreds);
 * the relay adds server-side dedup across versions/devices by commenting on the open issue.
 */
class CrashReportPolicy(private val store: CrashReportKeyValueStore, private val maxRemembered: Int = 200) {
    var enabled: Boolean
        get() = store.getBoolean(KEY_ENABLED, true)
        set(value) = store.putBoolean(KEY_ENABLED, value)

    val firstReportNoticeShown: Boolean get() = store.getBoolean(KEY_NOTICE_SHOWN, false)
    fun markFirstReportNoticeShown() = store.putBoolean(KEY_NOTICE_SHOWN, true)

    /** Timestamp of the newest system exit record already processed (ANR scan cursor). */
    var lastExitTimestamp: Long
        get() = store.getLong(KEY_LAST_EXIT, 0L)
        set(value) = store.putLong(KEY_LAST_EXIT, value)

    fun alreadySent(signature: String, appVersion: String): Boolean =
        signature in sentSignatures(appVersion)

    fun recordSent(signature: String, appVersion: String) {
        val current = sentSignatures(appVersion)
        val updated = (current - signature + signature).takeLast(maxRemembered)
        store.putString(KEY_SENT_VERSION, appVersion)
        store.putString(KEY_SENT, updated.joinToString(","))
    }

    private fun sentSignatures(appVersion: String): List<String> {
        if (store.getString(KEY_SENT_VERSION) != appVersion) return emptyList()
        return store.getString(KEY_SENT).orEmpty().split(',').filter(String::isNotBlank)
    }

    companion object {
        const val KEY_ENABLED = "auto-report-enabled"
        const val KEY_NOTICE_SHOWN = "first-report-notice-shown"
        const val KEY_SENT = "sent-signatures"
        const val KEY_SENT_VERSION = "sent-signatures-version"
        const val KEY_LAST_EXIT = "last-exit-timestamp"
    }
}

data class CrashReport(
    val kind: Kind,
    val signature: String,
    val stackTrace: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val androidVersion: String,
    val brand: String,
    val model: String,
    val occurredAtIso: String,
) {
    enum class Kind { CRASH, ANR }

    /**
     * ACRA-shaped JSON understood by the workflows gateway `/crash-report/aive` relay.
     * The relay classifies ANRs by "ApplicationNotResponding" in the first stack-trace line.
     */
    fun toRelayJson(): String {
        val fields = linkedMapOf(
            "PACKAGE_NAME" to packageName,
            "APP_VERSION_NAME" to appVersionName,
            "APP_VERSION_CODE" to appVersionCode.toString(),
            "ANDROID_VERSION" to androidVersion,
            "BRAND" to brand,
            "PHONE_MODEL" to model,
            "USER_CRASH_DATE" to occurredAtIso,
            "STACK_TRACE" to stackTrace,
            "CUSTOM_DATA" to "kind=${kind.name.lowercase()}\nclient-signature=$signature",
        )
        return fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${jsonString(v)}" }
    }

    companion object {
        const val MAX_STACK_CHARS = 38_000

        fun jsonString(value: String): String = buildString {
            append('"')
            for (c in value) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
