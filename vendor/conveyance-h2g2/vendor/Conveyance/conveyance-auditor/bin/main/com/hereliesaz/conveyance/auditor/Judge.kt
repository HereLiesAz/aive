package com.hereliesaz.conveyance.auditor

/**
 * Something that can look at a screen and say what it thinks.
 *
 * The framework does not care which model this is, and deliberately so. What the audit needs is a
 * viewer that has never seen the product — that is a property of what it is *shown*, not of who
 * made it. Any model that can look at an image and answer in words can do this job, and pinning the
 * framework to one vendor would have been a decision with no design behind it.
 */
interface Judge {

    /** A name for the record, because which judge answered changes what the answer is worth. */
    val name: String

    /**
     * @param png the rendered surface, or null when only text is being weighed.
     * @param prompt what to consider.
     */
    fun look(png: ByteArray?, prompt: String): String
}

/**
 * Finding a judge without being told where to look.
 *
 * The order matters and is not about quality. A key that has been deliberately provided is a
 * statement of intent and is used first; the local model is what runs when nobody has said
 * anything, so that the audit works out of the box rather than being a feature you have to go and
 * buy before you can try it.
 */
object Judges {

    /**
     * Providers that speak the OpenAI chat-completions shape, which is most of them.
     *
     * Anthropic is absent on purpose: it has a real SDK in this project and using a compatibility
     * shim to reach it would be worse code for no reason.
     */
    private val compatible = listOf(
        Provider("OPENAI_API_KEY", "OpenAI", "https://api.openai.com/v1", "gpt-5"),
        Provider("GEMINI_API_KEY", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-pro"),
        Provider("XAI_API_KEY", "xAI", "https://api.x.ai/v1", "grok-4"),
        Provider("GROQ_API_KEY", "Groq", "https://api.groq.com/openai/v1", "llama-4-scout-17b-16e-instruct"),
        Provider("MISTRAL_API_KEY", "Mistral", "https://api.mistral.ai/v1", "pixtral-large-latest"),
        Provider("DEEPSEEK_API_KEY", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("OPENROUTER_API_KEY", "OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-opus-4.5"),
    )

    private data class Provider(
        val key: String,
        val label: String,
        val baseUrl: String,
        val model: String,
    )

    /**
     * Whichever judge this machine can actually reach.
     *
     * Every choice is overridable: `CONVEYANCE_JUDGE_URL` and `CONVEYANCE_JUDGE_MODEL` point the
     * compatible client anywhere at all, including a provider not listed here and a local server on
     * a different port.
     */
    fun detect(): Judge {
        env("CONVEYANCE_JUDGE_URL")?.let { url ->
            return OpenAiCompatibleJudge(
                label = "custom",
                baseUrl = url,
                model = env("CONVEYANCE_JUDGE_MODEL") ?: DEFAULT_LOCAL_MODEL,
                apiKey = env("CONVEYANCE_JUDGE_KEY"),
            )
        }

        if (!env("ANTHROPIC_API_KEY").isNullOrBlank()) return AnthropicJudge()

        compatible.forEach { provider ->
            val key = env(provider.key)
            if (!key.isNullOrBlank()) {
                return OpenAiCompatibleJudge(
                    label = provider.label,
                    baseUrl = provider.baseUrl,
                    model = env("CONVEYANCE_JUDGE_MODEL") ?: provider.model,
                    apiKey = key,
                )
            }
        }

        // Nobody said anything, so look for a model running on this machine. No key, no account,
        // no bill -- the audit should be something you can try, not something you have to buy.
        return OpenAiCompatibleJudge(
            label = "local",
            baseUrl = env("OLLAMA_HOST")?.trimEnd('/')?.plus("/v1") ?: DEFAULT_LOCAL_URL,
            model = env("CONVEYANCE_JUDGE_MODEL") ?: DEFAULT_LOCAL_MODEL,
            apiKey = null,
        )
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    const val DEFAULT_LOCAL_URL = "http://localhost:11434/v1"

    /** A small vision model, because the judge has to be able to see the screen. */
    const val DEFAULT_LOCAL_MODEL = "llama3.2-vision"
}
