package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.InstalledStoreLlm
import com.hereliesaz.geministrator.azphalt.StoreLlmProviders

data class ProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val apiKeyUrl: String,
    val credentialLabel: String,
    val description: String,
    /** Serves free models without a key; the credential field may be left blank. */
    val keyOptional: Boolean = false,
)

object ProviderCatalog {
    const val OPENAI_ID = "openai"
    const val ANTHROPIC_ID = "anthropic"
    const val GEMINI_ID = "gemini"
    const val XAI_ID = "xai"
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
    const val OLLAMA_CLOUD_ID = "ollama-cloud"
    const val ZAI_ID = "zai"
    const val CLOUDFLARE_ID = "cloudflare"
    const val KILO_ID = "kilo"
    const val LLM7_ID = "llm7"
    const val OVHCLOUD_ID = "ovhcloud"

    /**
     * Providers the app no longer supports. Credential stores delete what they still hold for these
     * so a removed integration leaves no key behind.
     */
    val RETIRED_PROVIDER_IDS: Set<String> = setOf("jules")

    /** Stored for a [ProviderCatalogEntry.keyOptional] provider linked without a key. */
    const val ANONYMOUS_CREDENTIAL = "anonymous"

    /** The providers built into Aive. */
    val builtInEntries: List<ProviderCatalogEntry> = listOf(
        ProviderCatalogEntry(
            id = OPENAI_ID,
            displayName = "OpenAI / Codex",
            apiKeyUrl = "https://platform.openai.com/api-keys",
            credentialLabel = "OpenAI API key",
            description = "OpenAI's GPT models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = ANTHROPIC_ID,
            displayName = "Claude",
            apiKeyUrl = "https://platform.claude.com/settings/keys",
            credentialLabel = "Anthropic API key",
            description = "Anthropic's Claude models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = GEMINI_ID,
            displayName = "Gemini",
            apiKeyUrl = "https://aistudio.google.com/apikey",
            credentialLabel = "Gemini API key",
            description = "Google's Gemini models. Needs an API key. On Android you can also use the Gemini app if it is installed.",
        ),
        ProviderCatalogEntry(
            id = XAI_ID,
            displayName = "Grok",
            apiKeyUrl = "https://console.x.ai/team/default/api-keys",
            credentialLabel = "xAI API key",
            description = "xAI's Grok models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = DEEPSEEK_ID,
            displayName = "DeepSeek",
            apiKeyUrl = "https://platform.deepseek.com/api_keys",
            credentialLabel = "DeepSeek API key",
            description = "DeepSeek's reasoning and coding models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = GROQ_ID,
            displayName = "Groq",
            apiKeyUrl = "https://console.groq.com/keys",
            credentialLabel = "Groq API key",
            description = "Fast hosted models from Groq. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = CEREBRAS_ID,
            displayName = "Cerebras",
            apiKeyUrl = "https://cloud.cerebras.ai/",
            credentialLabel = "Cerebras API key",
            description = "Fast hosted open models from Cerebras. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = MISTRAL_ID,
            displayName = "Mistral",
            apiKeyUrl = "https://console.mistral.ai/api-keys",
            credentialLabel = "Mistral API key",
            description = "Mistral's models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = HUGGING_FACE_ID,
            displayName = "Hugging Face Inference",
            apiKeyUrl = "https://huggingface.co/settings/tokens",
            credentialLabel = "Hugging Face token",
            description = "Hosted open models through Hugging Face. Needs an access token.",
        ),
        ProviderCatalogEntry(
            id = OPENROUTER_ID,
            displayName = "OpenRouter",
            apiKeyUrl = "https://openrouter.ai/settings/keys",
            credentialLabel = "OpenRouter API key",
            description = "One account for hundreds of models from many providers. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = TOGETHER_ID,
            displayName = "Together AI",
            apiKeyUrl = "https://api.together.ai/settings/api-keys",
            credentialLabel = "Together API key",
            description = "Hosted open models from Together AI. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = FIREWORKS_ID,
            displayName = "Fireworks AI",
            apiKeyUrl = "https://app.fireworks.ai/settings/users/api-keys",
            credentialLabel = "Fireworks API key",
            description = "Hosted reasoning and coding models from Fireworks. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = PERPLEXITY_ID,
            displayName = "Perplexity",
            apiKeyUrl = "https://www.perplexity.ai/account/api/keys",
            credentialLabel = "Perplexity API key",
            description = "Perplexity's Sonar models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = COHERE_ID,
            displayName = "Cohere",
            apiKeyUrl = "https://dashboard.cohere.com/api-keys",
            credentialLabel = "Cohere API key",
            description = "Cohere's Command models. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = NVIDIA_ID,
            displayName = "NVIDIA NIM",
            apiKeyUrl = "https://build.nvidia.com/settings/api-keys",
            credentialLabel = "NVIDIA API key",
            description = "Models hosted by NVIDIA. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = SAMBANOVA_ID,
            displayName = "SambaNova",
            apiKeyUrl = "https://cloud.sambanova.ai/apis",
            credentialLabel = "SambaNova API key",
            description = "Models hosted by SambaNova. Needs an API key.",
        ),
        ProviderCatalogEntry(
            id = OLLAMA_CLOUD_ID,
            displayName = "Ollama Cloud",
            apiKeyUrl = "https://ollama.com/settings/keys",
            credentialLabel = "Ollama API key",
            description = "Large open models (DeepSeek, Kimi, GLM, gpt-oss) hosted by Ollama. Needs an API key. Free tier with weekly limits.",
        ),
        ProviderCatalogEntry(
            id = ZAI_ID,
            displayName = "Z.ai",
            apiKeyUrl = "https://z.ai/manage-apikey/apikey-list",
            credentialLabel = "Z.ai API key",
            description = "GLM models. Needs an API key. GLM-4.7-Flash is free, one request at a time.",
        ),
        ProviderCatalogEntry(
            id = CLOUDFLARE_ID,
            displayName = "Cloudflare Workers AI",
            apiKeyUrl = "https://dash.cloudflare.com/profile/api-tokens",
            credentialLabel = "Cloudflare account ID and API token (ACCOUNT_ID:API_TOKEN)",
            description = "Open models run by Cloudflare. Needs your account ID (on the Cloudflare dashboard home) and a Workers AI " +
                "API token, joined by a colon. Free up to 10,000 neurons a day (Cloudflare's usage unit).",
        ),
        ProviderCatalogEntry(
            id = KILO_ID,
            displayName = "Kilo Gateway",
            apiKeyUrl = "https://app.kilo.ai/profile",
            credentialLabel = "Kilo API key (optional)",
            description = "Free models. No key needed. Limited to 200 requests an hour per IP address. Free routing may use " +
                "providers that log prompts, so keep confidential data out.",
            keyOptional = true,
        ),
        ProviderCatalogEntry(
            id = LLM7_ID,
            displayName = "LLM7",
            apiKeyUrl = "https://token.llm7.io/",
            credentialLabel = "LLM7 token (optional)",
            description = "Free models. No key needed, but the limits are tight; a free token raises them.",
            keyOptional = true,
        ),
        ProviderCatalogEntry(
            id = OVHCLOUD_ID,
            displayName = "OVHcloud AI Endpoints",
            apiKeyUrl = "https://www.ovhcloud.com/en/public-cloud/ai-endpoints/",
            credentialLabel = "OVHcloud AI token (optional)",
            description = "Qwen, Llama, Mistral and gpt-oss models. No key needed, but limited to 2 requests a minute per IP address.",
            keyOptional = true,
        ),
    )

    /**
     * Built-in providers, then the language models installed from the Azphalt store, which come last
     * so that planning, which takes the first linked provider, prefers a built-in one.
     */
    val entries: List<ProviderCatalogEntry>
        get() = builtInEntries + StoreLlmProviders.all().map(::storeEntry)

    fun entry(id: String): ProviderCatalogEntry? = entries.firstOrNull { it.id == id }

    private fun storeEntry(llm: InstalledStoreLlm) = ProviderCatalogEntry(
        id = llm.providerId,
        displayName = llm.name,
        apiKeyUrl = llm.terms?.takeIf(String::isNotBlank) ?: llm.repositoryUrl,
        credentialLabel = llm.keyLabel?.takeIf(String::isNotBlank)
            ?: if (llm.keyOptional) "API key (optional)" else "API key",
        description = listOfNotNull(
            llm.description?.takeIf(String::isNotBlank),
            llm.promptHandling,
            "Installed from the Azphalt Store (${llm.packageId} ${llm.version}).",
        ).joinToString(" "),
        keyOptional = llm.keyOptional,
    )
}
