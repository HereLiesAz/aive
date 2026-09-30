package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog

/**
 * The linked cloud LLM used for workflow planning: the first configured of Gemini, OpenAI, Claude,
 * Grok, then the hosted providers in catalog order (keyless ones last). Null when none is linked.
 */
fun configuredPlanningApi(credentials: Map<String, String>): TextGenerationApi? {
    fun key(id: String): LlmApiKeyProvider? =
        credentials[id]?.trim()?.takeIf(String::isNotEmpty)?.let { value -> LlmApiKeyProvider { value } }

    key(ProviderCatalog.GEMINI_ID)?.let { return GeminiGenerateContentApi(it) }
    key(ProviderCatalog.OPENAI_ID)?.let { return OpenAiResponsesApi(it) }
    key(ProviderCatalog.ANTHROPIC_ID)?.let { return AnthropicMessagesApi(it) }
    key(ProviderCatalog.XAI_ID)?.let { return XaiResponsesApi(it) }
    return HostedLlmProviders.entries.firstNotNullOfOrNull { spec ->
        credentials[spec.id]?.trim()?.takeIf(String::isNotEmpty)?.let { credential ->
            runCatching { HostedLlmProviders.textApi(spec, credential) }.getOrNull()
        }
    }
}

/**
 * The text API a hosted memory stage should use: [providerId] (a ProviderCatalog id) with an optional
 * [model] override, or the planning default when [providerId] is null. Null when that provider is
 * not configured (keyless hosted providers need no credential).
 */
fun memoryTextApi(credentials: Map<String, String>, providerId: String?, model: String?): TextGenerationApi? {
    if (providerId == null) return configuredPlanningApi(credentials)
    val credential = credentials[providerId]?.trim()?.takeIf(String::isNotEmpty)
    fun key() = credential?.let { value -> LlmApiKeyProvider { value } }
    return when (providerId) {
        ProviderCatalog.GEMINI_ID -> key()?.let { if (model != null) GeminiGenerateContentApi(it, model = model) else GeminiGenerateContentApi(it) }
        ProviderCatalog.OPENAI_ID -> key()?.let { if (model != null) OpenAiResponsesApi(it, model = model) else OpenAiResponsesApi(it) }
        ProviderCatalog.ANTHROPIC_ID -> key()?.let { if (model != null) AnthropicMessagesApi(it, model = model) else AnthropicMessagesApi(it) }
        ProviderCatalog.XAI_ID -> key()?.let { if (model != null) XaiResponsesApi(it, model = model) else XaiResponsesApi(it) }
        else -> HostedLlmProviders.entries.firstOrNull { it.id == providerId }?.let { spec ->
            val usable = credential ?: if (spec.keyOptional) ProviderCatalog.ANONYMOUS_CREDENTIAL else return null
            runCatching { HostedLlmProviders.textApi(spec, usable, model ?: spec.defaultModel) }.getOrNull()
        }
    }
}
