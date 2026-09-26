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
