package com.hereliesaz.geministrator.providers.lfm

import com.hereliesaz.geministrator.azphalt.AzphaltAssetEntry
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.hereliesaz.geministrator.providers.LiteRtLmEngineLoader
import com.hereliesaz.geministrator.providers.OnDeviceResourceProbe
import com.russhwolf.settings.Settings
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How to talk to one installed on-device model, read from its package's `params`: the chat format it was trained on, the
 * tool format, whether it thinks, the context it was exported with, and the memory it needs free to load.
 */
@Serializable
data class LfmModelProfile(
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    val thinking: Boolean = true,
    val minRamMegabytes: Int = DEFAULT_MIN_RAM_MEGABYTES,
    val imageInput: Boolean = false,
) {
    companion object {
        const val CHAT_FORMAT = "lfm3"
        const val TOOL_FORMAT = "lfm3-xml"
        const val DEFAULT_CONTEXT_TOKENS = 2_048
        const val DEFAULT_MIN_RAM_MEGABYTES = 3_072

        /** The profile a package declares, or null when it is not an LFM3 model in the XML tool format. */
        fun fromAsset(asset: AzphaltAssetEntry): LfmModelProfile? {
            val params = runCatching { asset.params?.jsonObject }.getOrNull() ?: return null
            if (params.text("chatFormat") != CHAT_FORMAT || params.text("toolFormat") != TOOL_FORMAT) return null
            val requirements = runCatching { asset.requirements?.jsonObject }.getOrNull()
            return LfmModelProfile(
                contextTokens = (params["contextTokens"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONTEXT_TOKENS).coerceIn(512, 32_768),
                thinking = params["thinking"]?.jsonPrimitive?.booleanOrNull ?: true,
                minRamMegabytes = (requirements?.get("minRamMB")?.jsonPrimitive?.intOrNull ?: DEFAULT_MIN_RAM_MEGABYTES).coerceIn(256, 65_536),
                imageInput = params["imageInput"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}

/** An on-device LFM3 model installed from the Azphalt store, ready to be a creature's mind. */
@Serializable
data class InstalledLocalLlm(
    val packageId: String,
    val name: String,
    /** The `.litertlm` file on this device. */
    val artifactPath: String,
    val profile: LfmModelProfile = LfmModelProfile(),
) {
    val providerId: String get() = providerIdFor(packageId)

    companion object {
        /** Prefixed so an installed model can never take a built-in provider's id, and never reads as a store (`azphalt:`) endpoint. */
        fun providerIdFor(packageId: String): String = "local:$packageId"
    }
}

/**
 * The on-device LFM3 models installed from the store, persisted in [Settings]. The provider list reads [all] on every access,
 * so an install or a removal shows up in provider selection without other wiring.
 */
object InstalledLocalLlms {
    const val STORAGE_KEY: String = "aive.azphalt.installed-local-llms.v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(InstalledLocalLlm.serializer())
    private var settingsOverride: Settings? = null
    private val defaultSettings: Settings? by lazy { runCatching { Settings() }.getOrNull() }
    private val settings: Settings? get() = settingsOverride ?: defaultSettings
    private val state: MutableStateFlow<List<InstalledLocalLlm>> by lazy { MutableStateFlow(read()) }

    val installed: StateFlow<List<InstalledLocalLlm>> get() = state

    fun all(): List<InstalledLocalLlm> = state.value

    fun byProviderId(providerId: String): InstalledLocalLlm? = all().firstOrNull { it.providerId == providerId }

    fun put(value: InstalledLocalLlm) {
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

    private fun read(): List<InstalledLocalLlm> {
        val store = runCatching { settings }.getOrNull() ?: return emptyList()
        return store.readQuarantining(STORAGE_KEY) { json.decodeFromString(serializer, it) } ?: emptyList()
    }

    private fun write(values: List<InstalledLocalLlm>) {
        settings?.refuseIfUnreadable(STORAGE_KEY) { json.decodeFromString(serializer, it) }
        settings?.putString(STORAGE_KEY, json.encodeToString(serializer, values))
    }
}

/**
 * What the platform lends the local models: the native engine loader, a free-memory probe and a scope for idle release. Set
 * once at startup where the platform has a LiteRT-LM runtime (Android); until then an installed model is listed but cannot
 * run, and says so.
 */
class LocalLiteRtLmRuntime(
    val loader: LiteRtLmEngineLoader,
    val resources: OnDeviceResourceProbe,
    val scope: CoroutineScope,
)

object LocalLiteRtLmHost {
    @Volatile
    var runtime: LocalLiteRtLmRuntime? = null
}
