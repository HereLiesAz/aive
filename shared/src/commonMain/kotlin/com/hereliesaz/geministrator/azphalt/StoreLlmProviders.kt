package com.hereliesaz.geministrator.azphalt

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A hosted language model installed from an Azphalt `kind: "llm"` package. Aive calls its
 * OpenAI-compatible endpoint directly (azphalt spec/llm.md § Direct use): the package's setup script
 * never runs. The key, when one is linked, lives in the platform credential store under [providerId],
 * like any other provider's.
 */
@Serializable
data class InstalledStoreLlm(
    val packageId: String,
    val version: String,
    val repositoryUrl: String,
    val name: String,
    val description: String? = null,
    val baseUrl: String,
    val defaultModel: String,
    /** `none`, `optional-bearer` or `required-bearer`. */
    val auth: String,
    val keyLabel: String? = null,
    /** `not-retained`, `logged`, `may-train` or `unknown`. */
    val prompts: String = "unknown",
    val modelPinned: Boolean = false,
    val operator: String? = null,
    val terms: String? = null,
    val installedAtEpochMillis: Long = 0,
) {
    val providerId: String get() = providerIdFor(packageId)

    /** Linkable without a key: the endpoint serves anonymous requests. */
    val keyOptional: Boolean get() = auth != "required-bearer"

    /** What the operator does with prompts, as shown before install and on the provider. */
    val promptHandling: String get() = describePromptHandling(operator, prompts, modelPinned)

    companion object {
        /** Prefixed so a store package can never take a built-in provider's id. */
        fun providerIdFor(packageId: String): String = "azphalt:$packageId"
    }
}

/** A package's `dataHandling`, in words: who receives prompts and what they do with them. */
fun describePromptHandling(operator: String?, prompts: String, modelPinned: Boolean): String =
    (operator?.takeIf(String::isNotBlank) ?: "The operator") + when (prompts) {
        "not-retained" -> " does not keep prompts."
        "logged" -> " logs prompts."
        "may-train" -> " may log prompts and train on them."
        else -> " has not said what it does with prompts."
    } + if (modelPinned) "" else " The model behind it can change."

/**
 * The store-installed language models, persisted in [Settings]. Provider catalogs read [all] on
 * every access, so an install or removal shows up in settings, credential stores and provider
 * selection without other wiring.
 */
object StoreLlmProviders {
    const val STORAGE_KEY: String = "aive.azphalt.installed-llms.v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(InstalledStoreLlm.serializer())
    private var settingsOverride: Settings? = null
    private val defaultSettings: Settings? by lazy { runCatching { Settings() }.getOrNull() }
    private val settings: Settings? get() = settingsOverride ?: defaultSettings
    private val state: MutableStateFlow<List<InstalledStoreLlm>> by lazy { MutableStateFlow(read()) }

    val installed: StateFlow<List<InstalledStoreLlm>> get() = state

    fun all(): List<InstalledStoreLlm> = state.value

    fun byProviderId(providerId: String): InstalledStoreLlm? = all().firstOrNull { it.providerId == providerId }

    fun put(value: InstalledStoreLlm) {
        state.update { current -> (current.filterNot { it.packageId == value.packageId } + value).sortedBy { it.packageId } }
        write(state.value)
    }

    fun remove(packageId: String) {
        state.update { current -> current.filterNot { it.packageId == packageId } }
        write(state.value)
    }

    /** Points the registry at [settings] and reloads from it; for tests. */
    fun useSettings(settings: Settings) {
        settingsOverride = settings
        state.value = read()
    }

    private fun read(): List<InstalledStoreLlm> {
        val raw = runCatching { settings?.getStringOrNull(STORAGE_KEY) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(values: List<InstalledStoreLlm>) {
        settings?.putString(STORAGE_KEY, json.encodeToString(serializer, values))
    }
}
