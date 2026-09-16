package com.hereliesaz.geministrator

data class ProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val apiKeyUrl: String,
    val credentialLabel: String,
    val description: String,
)

object ProviderCatalog {
    const val JULES_ID = "jules"
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

    val entries: List<ProviderCatalogEntry> = listOf(
        ProviderCatalogEntry(
            id = JULES_ID,
            displayName = "Jules",
            apiKeyUrl = "https://jules.google.com/settings#api",
            credentialLabel = "Jules API key",
            description = "Repository coding agent with remote sessions and pull-request output.",
        ),
        ProviderCatalogEntry(
            id = OPENAI_ID,
            displayName = "OpenAI / Codex",
            apiKeyUrl = "https://platform.openai.com/api-keys",
            credentialLabel = "OpenAI API key",
            description = "OpenAI Responses API for reasoning, planning, architecture, and review.",
        ),
        ProviderCatalogEntry(
            id = ANTHROPIC_ID,
            displayName = "Claude",
            apiKeyUrl = "https://platform.claude.com/settings/keys",
            credentialLabel = "Anthropic API key",
            description = "Anthropic Messages API for reasoning, planning, architecture, and review.",
        ),
        ProviderCatalogEntry(
            id = GEMINI_ID,
            displayName = "Gemini",
            apiKeyUrl = "https://aistudio.google.com/apikey",
            credentialLabel = "Gemini API key",
            description = "Google Gemini GenerateContent API; Android can also expose the installed-app transport.",
        ),
        ProviderCatalogEntry(
            id = XAI_ID,
            displayName = "Grok",
            apiKeyUrl = "https://console.x.ai/team/default/api-keys",
            credentialLabel = "xAI API key",
            description = "xAI Responses API for general reasoning and review work.",
        ),
        ProviderCatalogEntry(
            id = DEEPSEEK_ID,
            displayName = "DeepSeek",
            apiKeyUrl = "https://platform.deepseek.com/api_keys",
            credentialLabel = "DeepSeek API key",
            description = "DeepSeek hosted reasoning and coding models through its OpenAI-compatible API.",
        ),
        ProviderCatalogEntry(
            id = GROQ_ID,
            displayName = "Groq",
            apiKeyUrl = "https://console.groq.com/keys",
            credentialLabel = "Groq API key",
            description = "Low-latency hosted models through Groq's OpenAI-compatible API.",
        ),
        ProviderCatalogEntry(
            id = CEREBRAS_ID,
            displayName = "Cerebras",
            apiKeyUrl = "https://cloud.cerebras.ai/",
            credentialLabel = "Cerebras API key",
            description = "High-throughput hosted open models through Cerebras Inference.",
        ),
        ProviderCatalogEntry(
            id = MISTRAL_ID,
            displayName = "Mistral",
            apiKeyUrl = "https://console.mistral.ai/api-keys",
            credentialLabel = "Mistral API key",
            description = "Mistral models through the OpenAI-compatible Chat Completions API.",
        ),
        ProviderCatalogEntry(
            id = HUGGING_FACE_ID,
            displayName = "Hugging Face Inference",
            apiKeyUrl = "https://huggingface.co/settings/tokens",
            credentialLabel = "Hugging Face token",
            description = "Hugging Face Inference Providers router for hosted open models.",
        ),
        ProviderCatalogEntry(
            id = OPENROUTER_ID,
            displayName = "OpenRouter",
            apiKeyUrl = "https://openrouter.ai/settings/keys",
            credentialLabel = "OpenRouter API key",
            description = "One OpenAI-compatible endpoint routing across hundreds of models and providers.",
        ),
        ProviderCatalogEntry(
            id = TOGETHER_ID,
            displayName = "Together AI",
            apiKeyUrl = "https://api.together.ai/settings/api-keys",
            credentialLabel = "Together API key",
            description = "Hosted open models through Together's OpenAI-compatible inference API.",
        ),
        ProviderCatalogEntry(
            id = FIREWORKS_ID,
            displayName = "Fireworks AI",
            apiKeyUrl = "https://app.fireworks.ai/settings/users/api-keys",
            credentialLabel = "Fireworks API key",
            description = "Hosted reasoning and coding models through Fireworks' OpenAI-compatible API.",
        ),
        ProviderCatalogEntry(
            id = PERPLEXITY_ID,
            displayName = "Perplexity",
            apiKeyUrl = "https://www.perplexity.ai/account/api/keys",
            credentialLabel = "Perplexity API key",
            description = "Perplexity Sonar through its OpenAI-compatible Chat Completions interface.",
        ),
        ProviderCatalogEntry(
            id = COHERE_ID,
            displayName = "Cohere",
            apiKeyUrl = "https://dashboard.cohere.com/api-keys",
            credentialLabel = "Cohere API key",
            description = "Cohere Command models through the OpenAI Compatibility API.",
        ),
        ProviderCatalogEntry(
            id = NVIDIA_ID,
            displayName = "NVIDIA NIM",
            apiKeyUrl = "https://build.nvidia.com/settings/api-keys",
            credentialLabel = "NVIDIA API key",
            description = "NVIDIA-hosted NIM models through the OpenAI-compatible inference API.",
        ),
        ProviderCatalogEntry(
            id = SAMBANOVA_ID,
            displayName = "SambaNova",
            apiKeyUrl = "https://cloud.sambanova.ai/apis",
            credentialLabel = "SambaNova API key",
            description = "SambaCloud models through its OpenAI-compatible Chat Completions API.",
        ),
    )

    fun entry(id: String): ProviderCatalogEntry? = entries.firstOrNull { it.id == id }
}
