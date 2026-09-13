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
            description = "General reasoning, planning, architecture, and review tasks.",
        ),
        ProviderCatalogEntry(
            id = ANTHROPIC_ID,
            displayName = "Claude",
            apiKeyUrl = "https://platform.claude.com/settings/keys",
            credentialLabel = "Anthropic API key",
            description = "General reasoning, planning, architecture, and review tasks.",
        ),
        ProviderCatalogEntry(
            id = GEMINI_ID,
            displayName = "Gemini",
            apiKeyUrl = "https://aistudio.google.com/apikey",
            credentialLabel = "Gemini API key",
            description = "General reasoning, planning, architecture, and review tasks.",
        ),
        ProviderCatalogEntry(
            id = XAI_ID,
            displayName = "Grok",
            apiKeyUrl = "https://console.x.ai/team/default/api-keys",
            credentialLabel = "xAI API key",
            description = "General reasoning, planning, architecture, and review tasks.",
        ),
    )

    fun entry(id: String): ProviderCatalogEntry? = entries.firstOrNull { it.id == id }
}
