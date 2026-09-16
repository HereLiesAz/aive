package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentProvider

/**
 * A hosted provider that can be reached through the common OpenAI Chat Completions wire format.
 *
 * Keep provider-specific behavior out of this registry. Providers with materially different
 * semantics (OpenAI Responses, Anthropic Messages, Gemini GenerateContent, xAI Responses) retain
 * their native adapters.
 */
data class HostedLlmProviderSpec(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val extraHeaders: Map<String, String> = emptyMap(),
)

object HostedLlmProviders {
    const val DEEPSEEK_ID = "deepseek"
    const val GROQ_ID = "groq"
    const val CEREBRAS_ID = "cerebras"
    const val MISTRAL_ID = "mistral"
    const val HUGGING_FACE_ID = "huggingface"
    const val OPENROUTER_ID = "openrouter"
    const val TOGETHER_ID = "together"
    const val FIREWORKS_ID = "fireworks"
    const val PERPLEXITY_ID = "perplexity"
    const val COHERE_ID = "cohere"
    const val NVIDIA_ID = "nvidia"
    const val SAMBANOVA_ID = "sambanova"

    val entries: List<HostedLlmProviderSpec> = listOf(
        HostedLlmProviderSpec(
            id = DEEPSEEK_ID,
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-flash",
        ),
        HostedLlmProviderSpec(
            id = GROQ_ID,
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "openai/gpt-oss-20b",
        ),
        HostedLlmProviderSpec(
            id = CEREBRAS_ID,
            displayName = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            defaultModel = "gpt-oss-120b",
        ),
        HostedLlmProviderSpec(
            id = MISTRAL_ID,
            displayName = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
        ),
        HostedLlmProviderSpec(
            id = HUGGING_FACE_ID,
            displayName = "Hugging Face Inference",
            baseUrl = "https://router.huggingface.co/v1",
            defaultModel = "openai/gpt-oss-120b",
        ),
        HostedLlmProviderSpec(
            id = OPENROUTER_ID,
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openrouter/free",
            extraHeaders = mapOf("X-Title" to "Haive"),
        ),
        HostedLlmProviderSpec(
            id = TOGETHER_ID,
            displayName = "Together AI",
            baseUrl = "https://api.together.ai/v1",
            defaultModel = "openai/gpt-oss-20b",
        ),
        HostedLlmProviderSpec(
            id = FIREWORKS_ID,
            displayName = "Fireworks AI",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            defaultModel = "accounts/fireworks/models/deepseek-v3p1",
        ),
        HostedLlmProviderSpec(
            id = PERPLEXITY_ID,
            displayName = "Perplexity",
            baseUrl = "https://api.perplexity.ai",
            defaultModel = "sonar-pro",
        ),
        HostedLlmProviderSpec(
            id = COHERE_ID,
            displayName = "Cohere",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            defaultModel = "command-a-plus-05-2026",
        ),
        HostedLlmProviderSpec(
            id = NVIDIA_ID,
            displayName = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            defaultModel = "meta/llama-3.3-70b-instruct",
        ),
        HostedLlmProviderSpec(
            id = SAMBANOVA_ID,
            displayName = "SambaNova",
            baseUrl = "https://api.sambanova.ai/v1",
            defaultModel = "Meta-Llama-3.3-70B-Instruct",
        ),
    )

    private val byId = entries.associateBy(HostedLlmProviderSpec::id)

    fun entry(id: String): HostedLlmProviderSpec? = byId[id]

    fun create(
        spec: HostedLlmProviderSpec,
        apiKeyProvider: LlmApiKeyProvider,
        model: String = spec.defaultModel,
    ): AgentProvider = TextLlmProvider(
        id = AgentProviderId(spec.id),
        displayName = spec.displayName,
        api = OpenAiCompatibleChatApi(
            apiKeyProvider = apiKeyProvider,
            model = model,
            baseUrl = spec.baseUrl,
            extraHeaders = spec.extraHeaders,
        ),
    )

    /** Build every hosted provider whose credential is currently configured. */
    fun configured(credentials: Map<String, String>): List<AgentProvider> = entries.mapNotNull { spec ->
        val key = credentials[spec.id]?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        create(spec, LlmApiKeyProvider { key })
    }
}
