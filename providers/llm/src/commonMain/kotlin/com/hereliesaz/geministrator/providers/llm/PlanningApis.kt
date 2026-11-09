package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.orchestration.FailoverOrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.PlannerCandidate

/**
 * The linked cloud LLM used for workflow planning: the first configured of Gemini, OpenAI, Claude,
 * Grok, then the hosted providers in catalog order (keyless ones last). Null when none is linked.
 */
fun configuredPlanningApi(credentials: Map<String, String>): TextGenerationApi? {
    fun key(id: String): LlmApiKeyProvider? =
        credentials[id]?.trim()?.takeIf(String::isNotEmpty)?.let { value -> LlmApiKeyProvider { value } }

    key(ProviderCatalog.GEMINI_ID)?.let { return GeminiGenerateContentApi(it).metered(ProviderCatalog.GEMINI_ID) }
    key(ProviderCatalog.OPENAI_ID)?.let { return OpenAiResponsesApi(it).metered(ProviderCatalog.OPENAI_ID) }
    key(ProviderCatalog.ANTHROPIC_ID)?.let { return AnthropicMessagesApi(it).metered(ProviderCatalog.ANTHROPIC_ID) }
    key(ProviderCatalog.XAI_ID)?.let { return XaiResponsesApi(it).metered(ProviderCatalog.XAI_ID) }
    return HostedLlmProviders.entries.firstNotNullOfOrNull { spec ->
        credentials[spec.id]?.trim()?.takeIf(String::isNotEmpty)?.let { credential ->
            runCatching { HostedLlmProviders.textApi(spec, credential).metered(spec.id) }.getOrNull()
        }
    }
}

/** A linked LLM that can plan: its catalog id, its name for the keeper, and its text API. */
class PlanningApiEntry(val id: String, val name: String, val api: TextGenerationApi)

/**
 * Every linked cloud LLM that can plan, in the order [configuredPlanningApi] prefers them (keyless
 * last). The planner fails over down this list.
 */
fun configuredPlanningApis(linked: Map<String, String>): List<PlanningApiEntry> {
    fun key(id: String): LlmApiKeyProvider? =
        linked[id]?.trim()?.takeIf(String::isNotEmpty)?.let { value -> LlmApiKeyProvider { value } }
    fun name(id: String) = ProviderCatalog.entry(id)?.displayName ?: id
    return buildList {
        key(ProviderCatalog.GEMINI_ID)?.let { add(PlanningApiEntry(ProviderCatalog.GEMINI_ID, name(ProviderCatalog.GEMINI_ID), GeminiGenerateContentApi(it).metered(ProviderCatalog.GEMINI_ID))) }
        key(ProviderCatalog.OPENAI_ID)?.let { add(PlanningApiEntry(ProviderCatalog.OPENAI_ID, name(ProviderCatalog.OPENAI_ID), OpenAiResponsesApi(it).metered(ProviderCatalog.OPENAI_ID))) }
        key(ProviderCatalog.ANTHROPIC_ID)?.let { add(PlanningApiEntry(ProviderCatalog.ANTHROPIC_ID, name(ProviderCatalog.ANTHROPIC_ID), AnthropicMessagesApi(it).metered(ProviderCatalog.ANTHROPIC_ID))) }
        key(ProviderCatalog.XAI_ID)?.let { add(PlanningApiEntry(ProviderCatalog.XAI_ID, name(ProviderCatalog.XAI_ID), XaiResponsesApi(it).metered(ProviderCatalog.XAI_ID))) }
        HostedLlmProviders.entries.forEach { spec ->
            linked[spec.id]?.trim()?.takeIf(String::isNotEmpty)?.let { secret ->
                runCatching { HostedLlmProviders.textApi(spec, secret) }.getOrNull()
                    ?.let { add(PlanningApiEntry(spec.id, name(spec.id), it.metered(spec.id))) }
            }
        }
    }
}

/** The planner for the linked LLMs: fails over from one to the next; null when none is linked. */
fun configuredPlanningRuntime(linked: Map<String, String>): OrchestrationAgentRuntime? =
    configuredPlanningApis(linked)
        .map { PlannerCandidate(it.id, it.name, TextGenerationOrchestrationAgentRuntime(it.api)) }
        .takeIf { it.isNotEmpty() }
        ?.let(::FailoverOrchestrationAgentRuntime)

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
        ProviderCatalog.GEMINI_ID -> key()?.let { if (model != null) GeminiGenerateContentApi(it, model = model) else GeminiGenerateContentApi(it) }?.metered(providerId)
        ProviderCatalog.OPENAI_ID -> key()?.let { if (model != null) OpenAiResponsesApi(it, model = model) else OpenAiResponsesApi(it) }?.metered(providerId)
        ProviderCatalog.ANTHROPIC_ID -> key()?.let { if (model != null) AnthropicMessagesApi(it, model = model) else AnthropicMessagesApi(it) }?.metered(providerId)
        ProviderCatalog.XAI_ID -> key()?.let { if (model != null) XaiResponsesApi(it, model = model) else XaiResponsesApi(it) }?.metered(providerId)
        else -> HostedLlmProviders.entries.firstOrNull { it.id == providerId }?.let { spec ->
            val usable = credential ?: if (spec.keyOptional) ProviderCatalog.ANONYMOUS_CREDENTIAL else return null
            runCatching { HostedLlmProviders.textApi(spec, usable, model ?: spec.defaultModel).metered(providerId) }.getOrNull()
        }
    }
}

/**
 * [this] with its generations counted toward the keeper's hourly spend cap ([MeteredTextGenerationApi]): the
 * planner, the plan reviewer and the memory clerks work outside any run, where no role budget sees them.
 */
private fun TextGenerationApi.metered(providerId: String): TextGenerationApi = MeteredTextGenerationApi(this, providerId)
