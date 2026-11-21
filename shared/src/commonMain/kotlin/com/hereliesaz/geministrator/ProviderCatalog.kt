package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aAgentConnection
import com.hereliesaz.geministrator.a2a.A2aAgentConnections
import com.hereliesaz.geministrator.azphalt.InstalledStoreLlm
import com.hereliesaz.geministrator.azphalt.StoreLlmProviders
import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs

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
    const val AI_HORDE_ID = "aihorde"
    const val FAL_ID = "fal"
    const val ANTHROPIC_ID = "anthropic"
    const val GEMINI_ID = "gemini"
    const val XAI_ID = "xai"
    const val DEEPSEEK_ID = "deepseek"
    const val GROQ_ID = "groq"
    const val CEREBRAS_ID = "cerebras"
    const val MISTRAL_ID = "mistral"
    const val HUGGING_FACE_ID = "huggingface"
    const val POLLINATIONS_ID = "pollinations"
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
    const val BLOCKRUN_ID = "blockrun"

    /** Gemini in Google Search through a browser view on the device (providers/llm GoogleAiMode); not a built-in entry: see [browserEntries]. */
    const val GOOGLE_AI_MODE_ID = "google-ai-mode"

    /** Set once at start-up by a platform that provides a browser view; the Source is listed only then. */
    @kotlin.concurrent.Volatile
    var googleAiModeAvailable: Boolean = false

    /** Stored for a [ProviderCatalogEntry.keyOptional] provider linked without a key. */
    const val ANONYMOUS_CREDENTIAL = "anonymous"

    /** Built-in providers that make pictures, not text: never the first thing a new keeper is given to think with. */
    private val IMAGE_ONLY_IDS = setOf(AI_HORDE_ID, FAL_ID)

    /** Most generous free limits first (Kilo: 200 requests an hour; LLM7: tight; OVHcloud: 2 a minute). */
    private val FREE_RELIABILITY_ORDER = listOf(KILO_ID, LLM7_ID, OVHCLOUD_ID, BLOCKRUN_ID)

    /** True for a built-in Source that makes pictures, not text. */
    fun isImageOnly(id: String): Boolean = id in IMAGE_ONLY_IDS

    /**
     * The image-only Sources [credentials] has linked (a key, or [ANONYMOUS_CREDENTIAL] for a keyless one). An image generator is run by an
     * executor, not a provider, so it never appears among the providers a platform builds: this is how the Sources screen learns it is
     * connected (without it, AI Horde and fal stayed on "Connect" however often a key was saved).
     */
    fun linkedImageProviderIds(credentials: Map<String, String>): Set<String> =
        IMAGE_ONLY_IDS.filterTo(linkedSetOf()) { credentials[it]?.isNotBlank() == true }

    /** Position of [id] among the free gateways, best limits first; the rest sort after. */
    fun freeReliabilityRank(id: String): Int = FREE_RELIABILITY_ORDER.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * The request limits the catalog text states for a keyless Source: at most [requests] in [windowMillis] per
     * IP address. Sources whose text gives no number (LLM7: "tight") have none here, and are paced only by the
     * rate-limit answers they send back.
     */
    data class KeylessRateLimit(val requests: Int, val windowMillis: Long)

    fun keylessRateLimit(id: String): KeylessRateLimit? = when (id) {
        KILO_ID -> KeylessRateLimit(requests = 200, windowMillis = 60 * 60_000L)
        OVHCLOUD_ID -> KeylessRateLimit(requests = 2, windowMillis = 60_000L)
        else -> null
    }

    /**
     * The free gateways a first hive offers in one tap: keyless, able to write text, best limits first,
     * at most three. (The Keeper's room lists the rest.)
     */
    fun firstRunFreeEntries(): List<ProviderCatalogEntry> =
        builtInEntries
            .filter { it.keyOptional && it.id !in IMAGE_ONLY_IDS }
            .sortedBy { freeReliabilityRank(it.id) }
            .take(3)

    /** The providers built into Aive. */
    val builtInEntries: List<ProviderCatalogEntry> = listOf(
        ProviderCatalogEntry(
            id = AI_HORDE_ID,
            displayName = "AI Horde Images",
            apiKeyUrl = "https://aihorde.net/",
            credentialLabel = "AI Horde key (optional)",
            description = "Community-hosted text-to-image generation. Anonymous requests need no key, have lowest queue priority, and are shared with volunteer workers. No guaranteed editing or transparency.",
            keyOptional = true,
        ),
        ProviderCatalogEntry(
            id = FAL_ID,
            displayName = "fal Images",
            apiKeyUrl = "https://fal.ai/dashboard/keys",
            credentialLabel = "fal API key",
            description = "Hosted FLUX image generation. Requires an API key; usage can incur charges. The Schnell model does not promise image editing or transparency.",
        ),
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
            id = POLLINATIONS_ID,
            displayName = "Pollinations",
            apiKeyUrl = "https://enter.pollinations.ai/",
            credentialLabel = "Pollinations secret key",
            description = "Hosted multimodal models. Requires a scoped user-authorized key. Account sign-in is available when Aive's public Pollinations App Key is configured. The approved expiry and spending budget are enforced by Pollinations.",
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
            description = "One account for hundreds of models from many sources. Needs an API key.",
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
            description = "Large open models (DeepSeek, Kimi, GLM, gpt-oss) hosted by Ollama. Needs an API key. A free plan has starter usage with limits; from 2026-11-09 gemma4:31b, gpt-oss:120b, gpt-oss:20b, nemotron-3-super and nemotron-3-ultra need credits added to the account.",
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
                "sources that log prompts, so keep confidential data out.",
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
        ProviderCatalogEntry(
            id = BLOCKRUN_ID,
            displayName = "BlockRun",
            apiKeyUrl = "https://blockrun.ai/",
            credentialLabel = "BlockRun key (optional)",
            description = "Free Nvidia, Poolside and Cohere models with long context. No key needed, limited per IP address with no number stated. " +
                "It may answer with a different model than the one named, and says nothing about logging, so keep confidential data out. " +
                "Desktop and Android only: a browser cannot call it.",
            keyOptional = true,
        ),
    )

    /**
     * Built-in providers, then the language models installed from the Azphalt store, which come last
     * so that planning, which takes the first linked provider, prefers a built-in one, then the
     * remote A2A agents connected by Agent Card (docs/A2A.md), which are never chosen automatically.
     */
    val entries: List<ProviderCatalogEntry>
        get() = builtInEntries + StoreLlmProviders.all().map(::storeEntry) + A2aAgentConnections.all().map(::a2aEntry) + browserEntries()

    /** True for a remote A2A agent's id ([A2aAgentConnection.providerId]). */
    fun isA2aAgent(id: String): Boolean = id.startsWith(A2aAgentConnection.PROVIDER_ID_PREFIX)

    fun entry(id: String): ProviderCatalogEntry? =
        entries.firstOrNull { it.id == id } ?: CliAgentSpecs.byId(id)?.let(::cliEntry)

    /** True for a vendor CLI the app drives on this computer ([CliAgentSpecs]). */
    fun isCliAgent(id: String): Boolean = CliAgentSpecs.byId(id) != null

    /**
     * Gemini in Google Search, through a browser view on this device: no key, no login. Only where the platform provides the view,
     * never connected by default and never chosen automatically (docs/Keepers-Room.md): the keeper adds it on purpose.
     */
    private fun browserEntries(): List<ProviderCatalogEntry> =
        if (!googleAiModeAvailable) emptyList() else listOf(
            ProviderCatalogEntry(
                id = GOOGLE_AI_MODE_ID,
                displayName = "Google AI Mode",
                apiKeyUrl = "https://www.google.com/search?udm=50",
                credentialLabel = "",
                description = "Gemini in Google Search, asked through a hidden browser on this device. No key and no login. " +
                    "It answers one question at a time: no instructions, no tools, no structured output, so it suits a role that " +
                    "researches or explains, not one that must return a form. Google sees the question and this device's address, " +
                    "and may ask for a human check. Used only by roles that prefer it.",
                keyOptional = true,
            ),
        )

    private fun cliEntry(spec: CliAgentSpec) = ProviderCatalogEntry(
        id = spec.id,
        displayName = spec.displayName + " (CLI)",
        apiKeyUrl = "",
        credentialLabel = "",
        description = "The ${spec.displayName} command-line tool, run headless on this computer under your own login. Used only by roles that prefer it.",
        keyOptional = true,
    )

    private fun a2aEntry(agent: A2aAgentConnection) = ProviderCatalogEntry(
        id = agent.providerId,
        displayName = agent.name,
        apiKeyUrl = agent.cardUrl,
        credentialLabel = "Bearer token (optional)",
        description = "A remote agent spoken to over A2A (Agent Card ${agent.cardUrl}). It receives the task, " +
            "its role instructions and approved context. Used only by roles that prefer it.",
        keyOptional = true,
    )

    private fun storeEntry(llm: InstalledStoreLlm) = ProviderCatalogEntry(
        id = llm.providerId,
        displayName = llm.name,
        apiKeyUrl = llm.terms?.takeIf(String::isNotBlank) ?: llm.repositoryUrl,
        credentialLabel = llm.keyLabel?.takeIf(String::isNotBlank)
            ?: if (llm.keyOptional) "API key (optional)" else "API key",
        description = listOfNotNull(
            llm.description?.takeIf(String::isNotBlank),
            llm.promptHandling,
            "Installed from the Shop (${llm.packageId} ${llm.version}).",
        ).joinToString(" "),
        keyOptional = llm.keyOptional,
    )
}
