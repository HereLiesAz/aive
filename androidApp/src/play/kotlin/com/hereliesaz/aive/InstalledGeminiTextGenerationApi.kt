package com.hereliesaz.aive

import android.content.Context
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationResult

/**
 * Play-distribution stub. Consumer-app UI automation is intentionally unavailable in this flavor.
 * Gemini remains available through the official API-key provider.
 */
internal class InstalledGeminiTextGenerationApi(
    private val context: Context,
    private val fallback: TextGenerationApi? = null,
) : TextGenerationApi {
    override suspend fun generate(prompt: String): TextGenerationResult =
        fallback?.generate(prompt)
            ?: error("Installed Gemini transport is not available in the Google Play build")

    companion object {
        fun isAccessibilityServiceEnabled(context: Context): Boolean = false
        fun isGeminiInstalled(context: Context): Boolean = false
        fun isAvailable(context: Context): Boolean = false
    }
}
