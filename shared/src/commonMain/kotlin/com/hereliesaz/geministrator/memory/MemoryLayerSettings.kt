package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What performs one memory stage. */
@Serializable
enum class MemoryEngineKind {
    /** Deterministic Kotlin ([ProgrammaticMemoryClerks]); nothing to download. */
    Programmatic,

    /** An installed on-device model. */
    LocalModel,

    /** A provider configured in The Aive, through its text API. Not offered for embedding stages. */
    HostedModel,
}

@Serializable
data class MemoryStageEngine(
    val kind: MemoryEngineKind = MemoryEngineKind.Programmatic,
    /** Hosted only: provider id (ProviderCatalog); null uses the first configured provider. */
    val providerId: String? = null,
    /** Hosted only: model override; null uses the provider's default. */
    val model: String? = null,
)

/**
 * The user's memory-layer setup. Missing stages default to [MemoryEngineKind.Programmatic], so a
 * fresh install works with nothing downloaded.
 */
@Serializable
data class MemoryLayerSettings(
    /** Off: nothing is banked or recalled. Stored memory is kept. */
    val enabled: Boolean = true,
    /** Banking continues; consolidation waits. */
    val consolidationPaused: Boolean = false,
    val engines: Map<MemoryMicroAgentRole, MemoryStageEngine> = emptyMap(),
    val policy: MemoryConsolidationPolicy = MemoryConsolidationPolicy(),
) {
    fun engineFor(role: MemoryMicroAgentRole): MemoryStageEngine = engines[role] ?: MemoryStageEngine()

    fun withEngine(role: MemoryMicroAgentRole, engine: MemoryStageEngine): MemoryLayerSettings {
        require(engine.kind in supportedEngines(role)) { "${engine.kind} cannot run $role" }
        return copy(engines = engines + (role to engine))
    }

    companion object {
        /** Association is embedding similarity; there is no hosted text path for it. */
        fun supportedEngines(role: MemoryMicroAgentRole): Set<MemoryEngineKind> =
            if (role == MemoryMicroAgentRole.AssociationLinker) {
                setOf(MemoryEngineKind.Programmatic, MemoryEngineKind.LocalModel)
            } else {
                MemoryEngineKind.entries.toSet()
            }
    }
}

/** Persists [MemoryLayerSettings] as one JSON value and publishes changes. */
class MemoryLayerSettingsStore(
    private val settings: Settings,
    private val key: String = DEFAULT_KEY,
) {
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<MemoryLayerSettings> = mutable.asStateFlow()

    fun update(transform: (MemoryLayerSettings) -> MemoryLayerSettings): MemoryLayerSettings {
        val next = transform(mutable.value)
        settings.putString(key, json.encodeToString(MemoryLayerSettings.serializer(), next))
        mutable.value = next
        return next
    }

    /** Unreadable settings fall back to defaults rather than disabling memory. */
    private fun load(): MemoryLayerSettings =
        settings.getStringOrNull(key)
            ?.let { runCatching { json.decodeFromString(MemoryLayerSettings.serializer(), it) }.getOrNull() }
            ?: MemoryLayerSettings()

    companion object {
        const val DEFAULT_KEY: String = "aive.memory.layer-settings.v1"

        fun createDefault(): MemoryLayerSettingsStore = MemoryLayerSettingsStore(Settings())
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** Supplies the non-programmatic engines a platform can run. Return null when unavailable. */
interface MemoryEngineProvider {
    fun localAgent(role: MemoryMicroAgentRole): MemoryMicroAgent? = null

    fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine): MemoryMicroAgent? = null
}

/** The agents built from settings, and what each stage actually runs (after any fallback). */
data class MemoryEngineAssembly(
    val agents: List<MemoryMicroAgent>,
    val resolved: Map<MemoryMicroAgentRole, MemoryEngineKind>,
    /** Stages that fell back to programmatic, with the reason. */
    val fallbacks: Map<MemoryMicroAgentRole, String>,
)

fun MemoryLayerSettings.assembleAgents(
    provider: MemoryEngineProvider,
    nowEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): MemoryEngineAssembly {
    val resolved = linkedMapOf<MemoryMicroAgentRole, MemoryEngineKind>()
    val fallbacks = linkedMapOf<MemoryMicroAgentRole, String>()
    val agents = MemoryMicroAgentRouter.REQUIRED_ROLES.map { role ->
        val engine = engineFor(role)
        val chosen = when (engine.kind) {
            MemoryEngineKind.Programmatic -> null
            MemoryEngineKind.LocalModel -> provider.localAgent(role)
                ?: null.also { fallbacks[role] = "No local model available for $role on this platform" }
            MemoryEngineKind.HostedModel -> if (role == MemoryMicroAgentRole.AssociationLinker) {
                null.also { fallbacks[role] = "Hosted models cannot run $role" }
            } else {
                provider.hostedAgent(role, engine)
                    ?: null.also { fallbacks[role] = "No hosted provider available for $role" }
            }
        }
        resolved[role] = if (chosen == null) MemoryEngineKind.Programmatic else engine.kind
        chosen ?: ProgrammaticMemoryClerks.forRole(role, nowEpochMillis)
    }
    return MemoryEngineAssembly(agents, resolved, fallbacks)
}

/**
 * A text-generation provider as a memory clerk runtime: [StructuredMemoryMicroAgent] renders the
 * packet and validates the answer exactly as for an on-device model.
 */
class HostedMemoryGenerativeRuntime(
    override val platform: MemoryMicroAgentPlatform,
    private val generate: suspend (prompt: String) -> String,
) : MemoryGenerativeInferenceRuntime {
    override val computePreference: MemoryComputePreference = MemoryComputePreference.AUTO
    override val capabilityDetector: HardwareCapabilityDetector = object : HardwareCapabilityDetector {
        override suspend fun discover(): List<MemoryComputeDevice> = emptyList()
    }

    override suspend fun isAvailable(model: MemoryMicroAgentModelSpec, artifact: MemoryMicroAgentArtifact): Boolean = true

    override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult =
        MemoryGenerativeInferenceResult(generate(request.prompt))

    companion object {
        /** A hosted clerk for [role]; its answers leave the device, so the spec is not local-only. */
        fun agent(
            role: MemoryMicroAgentRole,
            engine: MemoryStageEngine,
            platform: MemoryMicroAgentPlatform,
            generate: suspend (prompt: String) -> String,
        ): MemoryMicroAgent {
            require(role != MemoryMicroAgentRole.AssociationLinker)
            val spec = MemoryMicroAgentModelSpec(
                modelId = "hosted/${engine.providerId ?: "default"}/${engine.model ?: "default"}/${role.name}",
                localOnly = false,
            )
            return StructuredMemoryMicroAgent(role, spec, HostedMemoryGenerativeRuntime(platform, generate))
        }
    }
}

/**
 * Engines for platforms without on-device memory models: hosted stages call [hostedTextGenerator],
 * which the app sets from its current provider credentials; local stages fall back to programmatic.
 */
class HostedMemoryEngineProvider(private val platform: MemoryMicroAgentPlatform) : MemoryEngineProvider {
    /** (providerId or null for the default, model or null, prompt) to text. */
    var hostedTextGenerator: (suspend (providerId: String?, model: String?, prompt: String) -> String)? = null

    override fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine): MemoryMicroAgent =
        HostedMemoryGenerativeRuntime.agent(role, engine, platform) { prompt ->
            val generate = hostedTextGenerator ?: error("No hosted provider is configured for memory")
            generate(engine.providerId, engine.model, prompt)
        }
}
