package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog
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
    /** Serves free models without a key; a stored key only raises the quota. */
    val keyOptional: Boolean = false,
    /**
     * [baseUrl] contains `{accountId}` and the credential is `ACCOUNT_ID:API_TOKEN`
     * (Cloudflare Workers AI scopes its endpoint to an account).
     */
    val accountScoped: Boolean = false,
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
    const val OLLAMA_CLOUD_ID = ProviderCatalog.OLLAMA_CLOUD_ID
    const val ZAI_ID = ProviderCatalog.ZAI_ID
    const val CLOUDFLARE_ID = ProviderCatalog.CLOUDFLARE_ID
    const val KILO_ID = ProviderCatalog.KILO_ID
    const val LLM7_ID = ProviderCatalog.LLM7_ID
    const val OVHCLOUD_ID = ProviderCatalog.OVHCLOUD_ID

    private const val ACCOUNT_PLACEHOLDER = "{accountId}"

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
            defaultModel = "openai/gpt-oss-120b:fastest",
        ),
        HostedLlmProviderSpec(
            id = OPENROUTER_ID,
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openrouter/free",
            extraHeaders = mapOf("X-Title" to "Aive"),
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
            defaultModel = "accounts/fireworks/models/kimi-k2p6",
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
            defaultModel = "nvidia/llama-3.3-nemotron-super-49b-v1.5",
        ),
        HostedLlmProviderSpec(
            id = SAMBANOVA_ID,
            displayName = "SambaNova",
            baseUrl = "https://api.sambanova.ai/v1",
            defaultModel = "Meta-Llama-3.3-70B-Instruct",
        ),
        HostedLlmProviderSpec(
            id = OLLAMA_CLOUD_ID,
            displayName = "Ollama Cloud",
            baseUrl = "https://ollama.com/v1",
            defaultModel = "gpt-oss:120b",
        ),
        HostedLlmProviderSpec(
            id = ZAI_ID,
            displayName = "Z.ai",
            baseUrl = "https://api.z.ai/api/paas/v4",
            defaultModel = "glm-4.7-flash",
        ),
        HostedLlmProviderSpec(
            id = CLOUDFLARE_ID,
            displayName = "Cloudflare Workers AI",
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT_PLACEHOLDER/ai/v1",
            defaultModel = "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            accountScoped = true,
        ),
        // Keyless entries stay last: planning picks the first linked provider, and these are the
        // most rate-limited.
        HostedLlmProviderSpec(
            id = KILO_ID,
            displayName = "Kilo Gateway",
            baseUrl = "https://api.kilo.ai/api/gateway",
            defaultModel = "kilo-auto/free",
            keyOptional = true,
        ),
        HostedLlmProviderSpec(
            id = LLM7_ID,
            displayName = "LLM7",
            baseUrl = "https://api.llm7.io/v1",
            defaultModel = "GLM-5.3-Flash",
            keyOptional = true,
        ),
        HostedLlmProviderSpec(
            id = OVHCLOUD_ID,
            displayName = "OVHcloud AI Endpoints",
            baseUrl = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
            defaultModel = "Qwen3-Coder-30B-A3B-Instruct",
            keyOptional = true,
        ),
    )

    private val byId = entries.associateBy(HostedLlmProviderSpec::id)

    fun entry(id: String): HostedLlmProviderSpec? = byId[id]

    fun create(
        spec: HostedLlmProviderSpec,
        credential: String,
        model: String = spec.defaultModel,
    ): AgentProvider = TextLlmProvider(
        id = AgentProviderId(spec.id),
        displayName = spec.displayName,
        api = textApi(spec, credential, model),
    )

    /**
     * The raw chat API behind a hosted provider, for callers that need text generation only.
     * [credential] is the stored value: an API key, `ACCOUNT_ID:API_TOKEN` for account-scoped
     * providers, or [ProviderCatalog.ANONYMOUS_CREDENTIAL] for keyless use.
     */
    fun textApi(
        spec: HostedLlmProviderSpec,
        credential: String,
        model: String = spec.defaultModel,
    ): TextGenerationApi {
        val clean = credential.trim()
        val anonymous = clean.isEmpty() || clean == ProviderCatalog.ANONYMOUS_CREDENTIAL
        require(!anonymous || spec.keyOptional) { "${spec.displayName} requires an API key" }
        val (baseUrl, key) = if (spec.accountScoped) {
            val accountId = clean.substringBefore(':', missingDelimiterValue = "").trim()
            val token = clean.substringAfter(':', missingDelimiterValue = "").trim()
            require(accountId.isNotEmpty() && token.isNotEmpty()) {
                "${spec.displayName} credential must be ACCOUNT_ID:API_TOKEN"
            }
            spec.baseUrl.replace(ACCOUNT_PLACEHOLDER, accountId) to token
        } else {
            spec.baseUrl to if (anonymous) "" else clean
        }
        return OpenAiCompatibleChatApi(
            apiKeyProvider = LlmApiKeyProvider { key },
            model = model,
            baseUrl = baseUrl,
            extraHeaders = spec.extraHeaders,
            requireApiKey = !spec.keyOptional,
        )
    }

    /** Build every hosted provider whose credential is currently configured. */
    fun configured(credentials: Map<String, String>): List<AgentProvider> = entries.mapNotNull { spec ->
        val credential = credentials[spec.id]?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        runCatching { create(spec, credential) }.getOrNull()
    }
}
