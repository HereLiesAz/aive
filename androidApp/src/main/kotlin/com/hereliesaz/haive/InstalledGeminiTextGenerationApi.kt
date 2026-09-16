package com.hereliesaz.haive

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Uses the signed-in consumer Gemini app as a Haive text-generation transport.
 *
 * Haive launches the real Gemini activity in a bounded window when Android supports it. The
 * accessibility service only participates while [InstalledGeminiBridge.waiting] is true and only
 * for the resolved Gemini package. If [fallback] exists, transport/setup failures fall back to the
 * ordinary Gemini API rather than failing the governed task.
 */
internal class InstalledGeminiTextGenerationApi(
    private val context: Context,
    private val fallback: TextGenerationApi? = null,
) : TextGenerationApi {
    override suspend fun generate(prompt: String): TextGenerationResult = BRIDGE_MUTEX.withLock {
        try {
            generateInstalled(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fallback?.generate(prompt) ?: throw failure
        }
    }

    private suspend fun generateInstalled(prompt: String): TextGenerationResult {
        require(prompt.isNotBlank()) { "Gemini prompt must not be blank" }
        check(isAccessibilityServiceEnabled(context)) {
            "Haive's installed-Gemini accessibility service is not enabled"
        }

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        val packageName = resolveGeminiPackage(context, share)
            ?: error("No installed Gemini-capable activity can accept this request")
        share.setPackage(packageName)

        InstalledGeminiBridge.reset()
        InstalledGeminiBridge.pendingPrompt = prompt
        InstalledGeminiBridge.targetPackage = packageName
        InstalledGeminiBridge.phase = InstalledGeminiBridge.Phase.Input
        InstalledGeminiBridge.waiting = true

        val response = try {
            withContext(Dispatchers.Main) {
                InstalledGeminiWindowHost.launch(context, share)
            }
            withTimeout(RESPONSE_TIMEOUT_MS) { InstalledGeminiBridge.responses.receive() }
        } finally {
            InstalledGeminiBridge.waiting = false
            InstalledGeminiBridge.phase = InstalledGeminiBridge.Phase.Idle
            withContext(NonCancellable + Dispatchers.Main) {
                InstalledGeminiWindowHost.returnToHaive(context)
            }
        }

        InstalledGeminiBridge.reset()
        return TextGenerationResult(text = response)
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 120_000L
        private val BRIDGE_MUTEX = Mutex()
        private val GEMINI_PACKAGES = listOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox",
        )

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val expected = ComponentName(context, InstalledGeminiAccessibilityService::class.java)
                .flattenToString()
            return enabled.split(':').any { component ->
                component.equals(expected, ignoreCase = true) ||
                    component.endsWith("/${InstalledGeminiAccessibilityService::class.java.name}", ignoreCase = true)
            }
        }

        fun isGeminiInstalled(context: Context): Boolean {
            val probe = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Haive availability check")
            }
            return resolveGeminiPackage(context, probe) != null
        }

        fun isAvailable(context: Context): Boolean =
            isAccessibilityServiceEnabled(context) && isGeminiInstalled(context)

        fun resolveGeminiPackage(context: Context, shareIntent: Intent): String? {
            val packageManager = context.packageManager
            return GEMINI_PACKAGES.firstOrNull { packageName ->
                Intent(shareIntent).setPackage(packageName).resolveActivity(packageManager) != null
            }
        }
    }
}

private object InstalledGeminiWindowHost {
    fun launch(context: Context, targetIntent: Intent) {
        val intent = Intent(targetIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bounds = preferredBounds(context)
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        runCatching { context.startActivity(intent, options.toBundle()) }
            .getOrElse { context.startActivity(intent) }
    }

    fun returnToHaive(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    private fun preferredBounds(context: Context): Rect {
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val screen = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            width = screen.width()
            height = screen.height()
        } else {
            val metrics = context.resources.displayMetrics
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val windowWidth = (width * 0.86f).toInt().coerceAtLeast(1)
        val windowHeight = (height * 0.82f).toInt().coerceAtLeast(1)
        val left = ((width - windowWidth) / 2).coerceAtLeast(0)
        val top = ((height - windowHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, left + windowWidth, top + windowHeight)
    }
}
