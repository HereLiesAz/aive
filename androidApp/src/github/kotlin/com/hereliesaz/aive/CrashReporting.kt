package com.hereliesaz.aive

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.hereliesaz.aive.crash.CrashReport
import com.hereliesaz.aive.crash.CrashReportKeyValueStore
import com.hereliesaz.aive.crash.CrashReportPolicy
import com.hereliesaz.aive.crash.CrashSignatures
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Automatic crash/ANR reporting for the GitHub release flavor (opt-in: off until enabled in Settings).
 *
 * The app holds NO GitHub credential. Reports go to the HereLiesAz/workflows gateway Worker,
 * which files/deduplicates issues on HereLiesAz/aive with its own server-side GitHub App token.
 * [RELAY_KEY] ships in the APK and is only a spam filter the relay checks, not a secret.
 *
 * Crashes: the uncaught-exception handler only writes the report to disk (no network in a dying
 * process), then chains to the previous handler. Pending reports are sent on the next launch.
 * ANRs: on API 30+, [ActivityManager.getHistoricalProcessExitReasons] yields system-recorded ANR
 * traces from previous runs. Pre-30 devices (minSdk 26) get crash reporting only.
 */
internal object CrashReporting {
    const val isSupported: Boolean = true

    private const val RELAY_URL = "https://workflows.hereliesaz.workers.dev/crash-report/aive"
    private const val RELAY_KEY = "aive-crash-reports-v1"
    private const val PREFERENCES = "haive.crash-reporting"
    private const val PENDING_DIR = "crash-reports-pending"

    @Volatile private var installed = false

    fun isEnabled(context: Context): Boolean = policy(context).enabled

    fun setEnabled(context: Context, enabled: Boolean) {
        policy(context).enabled = enabled
        if (!enabled) pendingDir(context).listFiles()?.forEach(File::delete)
    }

    /**
     * Installs the crash handler and, in the background, scans for ANRs and sends pending
     * reports. [onFirstReportSent] runs on the main thread once, the first time any report is sent.
     */
    fun install(context: Context, onFirstReportSent: () -> Unit) {
        val app = context.applicationContext
        if (!installed) {
            installed = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { if (policy(app).enabled) persist(app, crashReport(app, error)) }
                previous?.uncaughtException(thread, error)
            }
        }
        Thread({
            runCatching {
                val policy = policy(app)
                if (!policy.enabled) return@runCatching
                collectAnrs(app, policy)
                if (sendPending(app, policy) && !policy.firstReportNoticeShown) {
                    Handler(Looper.getMainLooper()).post(onFirstReportSent)
                }
            }
        }, "aive-crash-reporter").apply { isDaemon = true }.start()
    }

    fun markFirstReportNoticeShown(context: Context) = policy(context).markFirstReportNoticeShown()

    private fun crashReport(context: Context, error: Throwable): CrashReport {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return report(context, CrashReport.Kind.CRASH, CrashSignatures.of(error), trace, System.currentTimeMillis())
    }

    private fun collectAnrs(context: Context, policy: CrashReportPolicy) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val cursor = policy.lastExitTimestamp
        val exits = manager.getHistoricalProcessExitReasons(null, 0, 16)
            .filter { it.timestamp > cursor }
        exits.maxOfOrNull { it.timestamp }?.let { policy.lastExitTimestamp = it }
        if (cursor == 0L) return // first run with reporting: don't backfill historic ANRs
        exits.filter { it.reason == ApplicationExitInfo.REASON_ANR }.forEach { exit ->
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val frames = CrashSignatures.mainThreadFrames(trace)
            val signature = CrashSignatures.ofAnr(exit.description, frames)
            val body = buildString {
                append("ApplicationNotResponding: ").append(exit.description ?: "ANR").append('\n')
                frames.forEach { append("\tat ").append(it).append('\n') }
                if (trace.isNotBlank()) append("\n--- full system ANR trace ---\n").append(trace)
            }
            persist(context, report(context, CrashReport.Kind.ANR, signature, body, exit.timestamp))
        }
    }

    /** Sends queued reports; returns true if at least one was accepted by the relay. */
    private fun sendPending(context: Context, policy: CrashReportPolicy): Boolean {
        var sentAny = false
        val files = pendingDir(context).listFiles()?.sortedBy(File::getName).orEmpty()
        for (file in files) {
            val (signature, version) = file.name.removeSuffix(".json").split('_').let {
                it.getOrNull(1).orEmpty() to it.drop(2).joinToString("_")
            }
            if (policy.alreadySent(signature, version)) {
                file.delete()
                continue
            }
            when (post(file.readText())) {
                Outcome.Sent -> {
                    policy.recordSent(signature, version)
                    file.delete()
                    sentAny = true
                }
                Outcome.Rejected -> file.delete()
                Outcome.Failed -> if (file.lastModified() < System.currentTimeMillis() - 7L * 86_400_000) file.delete()
            }
        }
        return sentAny
    }

    private enum class Outcome { Sent, Rejected, Failed }

    /** One attempt plus one retry; never throws. */
    private fun post(json: String): Outcome {
        repeat(2) {
            val status = runCatching {
                val connection = URL(RELAY_URL).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val basic = Base64.encodeToString("acra:$RELAY_KEY".toByteArray(), Base64.NO_WRAP)
                    connection.setRequestProperty("Authorization", "Basic $basic")
                    connection.outputStream.use { it.write(json.toByteArray()) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(-1)
            if (status in 200..299) return Outcome.Sent
            if (status in 400..499 && status != 429) return Outcome.Rejected
        }
        return Outcome.Failed
    }

    private fun persist(context: Context, report: CrashReport) {
        val policy = policy(context)
        if (policy.alreadySent(report.signature, report.appVersionName)) return
        val dir = pendingDir(context).apply { mkdirs() }
        val name = "${System.currentTimeMillis()}_${report.signature}_${report.appVersionName}.json"
        if (dir.listFiles()?.any { it.name.contains("_${report.signature}_") } == true) return
        File(dir, name).writeText(report.toRelayJson())
    }

    private fun report(context: Context, kind: CrashReport.Kind, signature: String, trace: String, at: Long): CrashReport {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(at))
        return CrashReport(
            kind = kind,
            signature = signature,
            stackTrace = trace.take(CrashReport.MAX_STACK_CHARS),
            appVersionName = (info.versionName ?: "unknown").replace('_', '-'),
            appVersionCode = code,
            packageName = context.packageName,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            occurredAtIso = iso,
        )
    }

    private fun pendingDir(context: Context) = File(context.applicationContext.filesDir, PENDING_DIR)

    private fun policy(context: Context) = CrashReportPolicy(
        SharedPreferencesStore(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
    )

    private class SharedPreferencesStore(private val prefs: SharedPreferences) : CrashReportKeyValueStore {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).commit() }
        override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
        override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
    }
}
