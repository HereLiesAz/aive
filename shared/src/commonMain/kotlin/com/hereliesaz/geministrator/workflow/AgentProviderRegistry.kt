package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.inference.CompoundInferenceFabric
import com.hereliesaz.geministrator.inference.GovernedCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.SettingsCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.SettingsInferenceGenealogyGraph
import com.hereliesaz.geministrator.inference.SettingsInferenceStateStore
import com.hereliesaz.geministrator.inference.withLocalModelLibrary
import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import com.hereliesaz.geministrator.orchestration.AgentRouteCandidate
import com.hereliesaz.geministrator.orchestration.AgentRouteDecision
import com.hereliesaz.geministrator.orchestration.AgentRoutingInput
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.withLock

data class ProviderSelectionRequest(
    val preferredProviderId: AgentProviderId? = null,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val constraints: ProviderConstraints = ProviderConstraints.None,
    val repository: RepositoryRef? = null,
    /**
     * Rough size of the task's input ([OnDeviceTextAgentProvider.estimateTokens]); null when unknown.
     * Only a known size under the on-device provider's limit lets a task go to the device.
     */
    val estimatedInputTokens: Int? = null,
)

class AgentProviderRegistry(
    providers: Collection<AgentProvider>,
    inferenceFabric: CompoundInferenceFabric? = null,
    genealogyGovernance: InferenceGenealogyGovernanceRuntime? = null,
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    localModelLibrary: LocalModelLibrary = MemoryEpoch8LocalModelLibrary.library,
    /**
     * Backing store for the default fabric and genealogy graph when those are not given; see
     * [com.hereliesaz.geministrator.persistence.WorkflowPersistence.inferenceSettings]. Null means
     * the platform's durable store, opened only if a default is actually needed.
     */
    inferenceSettings: Settings? = null,
) {
    private val defaultSettings: Settings by lazy { inferenceSettings ?: durableInferenceSettings() }

    val genealogyGovernance: InferenceGenealogyGovernanceRuntime = genealogyGovernance
        ?: InferenceGenealogyGovernanceRuntime(graph = SettingsInferenceGenealogyGraph(defaultSettings))
    private val defaultedFabric: CompoundInferenceFabric = inferenceFabric
        ?: SettingsCompoundInferenceFabric(SettingsInferenceStateStore(defaultSettings))

    val inferenceFabric: CompoundInferenceFabric = GovernedCompoundInferenceFabric(
        delegate = defaultedFabric.withLocalModelLibrary(localModelLibrary),
        governance = this.genealogyGovernance,
    )
    private val providersById = providers.associateBy { it.id }

    /** Per-provider outcomes this process has seen: failures in a row and the last runs' results. */
    private data class ProviderHealth(val consecutiveFailures: Int = 0, val recent: List<Boolean> = emptyList())

    private val healthMutex = kotlinx.coroutines.sync.Mutex()
    private val health = HashMap<AgentProviderId, ProviderHealth>()

    /** Records how a provider run ended; feeds the router's circuit breaker and reliability. */
    suspend fun recordOutcome(id: AgentProviderId, success: Boolean) = healthMutex.withLock {
        val current = health[id] ?: ProviderHealth()
        health[id] = ProviderHealth(
            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
            recent = (current.recent + success).takeLast(HEALTH_WINDOW),
        )
    }

    private suspend fun healthOf(id: AgentProviderId): ProviderHealth = healthMutex.withLock { health[id] ?: ProviderHealth() }

    init {
        require(providersById.size == providers.size) { "Provider IDs must be unique" }
    }

    val providerIds: Set<AgentProviderId> get() = providersById.keys

    fun provider(id: AgentProviderId): AgentProvider? = providersById[id]

    /**
     * Whether a provider [select] may choose on its own supports every capability in [required]. The
     * on-device provider does not count: it takes only small tasks, so it staffs no role by itself.
     */
    suspend fun canStaff(required: Set<AgentCapability>, repository: RepositoryRef? = null): Boolean =
        providersById.values
            .filterNot { it.explicitOnly || it is OnDeviceTextAgentProvider }
            .any { it.capabilities().supported.containsAll(required) && it.supportsRepository(repository) }

    suspend fun select(request: ProviderSelectionRequest): AgentProvider {
        val required = buildSet {
            addAll(request.requiredCapabilities)
            val constraints = request.constraints
            if (constraints is ProviderConstraints.RequireCapabilities) {
                addAll(constraints.capabilities)
            }
        }
        val repositoryAccessRequired =
            AgentCapability.RepositoryRead in required || AgentCapability.RepositoryWrite in required

        when (val constraints = request.constraints) {
            is ProviderConstraints.RequireProvider -> {
                val provider = providersById[constraints.providerId]
                    ?: error("Required provider ${constraints.providerId.value} is not registered")
                if (!provider.capabilities().supported.containsAll(required)) {
                    error("Provider ${constraints.providerId.value} does not satisfy required capabilities $required")
                }
                if (!provider.supportsRepository(request.repository)) {
                    error(
                        if (request.repository == null) {
                            "Provider ${constraints.providerId.value} works only inside a linked repository"
                        } else {
                            "Provider ${constraints.providerId.value} cannot operate in the context of the linked " +
                                "${request.repository.source.displayName()} repository"
                        },
                    )
                }
                return provider
            }
            else -> Unit
        }

        // The on-device provider is a candidate only for a small task that needs no repository access
        // (docs/Orchestration_layer.md, Agent Router); then it goes ahead of every provider but the
        // role's own preferred one. Otherwise it is left out, even as the role's preferred provider.
        fun onDeviceFits(provider: AgentProvider): Boolean {
            if (provider !is OnDeviceTextAgentProvider) return true
            val estimate = request.estimatedInputTokens ?: return false
            return !repositoryAccessRequired && estimate <= provider.maxInputTokens && provider.isAvailable()
        }

        // Explicit-only providers join the candidates only as the role's preferred provider.
        val ordered = buildList {
            request.preferredProviderId?.let { providersById[it] }?.takeIf(::onDeviceFits)?.let(::add)
            providersById.values.forEach { provider ->
                if (provider is OnDeviceTextAgentProvider && provider !in this && onDeviceFits(provider)) add(provider)
            }
            providersById.values.forEach { provider ->
                if (provider !in this && !provider.explicitOnly && provider !is OnDeviceTextAgentProvider) add(provider)
            }
        }

        val eligibleProviders = mutableListOf<Pair<AgentProvider, Set<AgentCapability>>>()
        for (provider in ordered) {
            val capabilities = provider.capabilities().supported
            // Asked even without a repository: an agent that only works inside one (OpenCode on
            // GitHub, a local or GitLab workspace) says no to repository-less work.
            if (capabilities.containsAll(required) && provider.supportsRepository(request.repository)) {
                eligibleProviders += provider to capabilities
            }
        }

        if (eligibleProviders.isNotEmpty()) {
            val route = orchestrationUtilities.routeAgent(
                AgentRoutingInput(
                    requiredCapabilities = required.mapTo(linkedSetOf()) { it.name },
                    requiredContextTokens = request.estimatedInputTokens?.coerceAtLeast(0) ?: 0,
                    candidates = eligibleProviders.mapIndexed { index, (provider, capabilities) ->
                        val health = healthOf(provider.id)
                        AgentRouteCandidate(
                            id = provider.id.value,
                            capabilities = capabilities.mapTo(linkedSetOf()) { it.name },
                            contextLimitTokens = (provider as? OnDeviceTextAgentProvider)?.maxInputTokens ?: Int.MAX_VALUE,
                            // Preserve current preferred/registration ordering unless a specialist router overrides it.
                            preferenceRank = index,
                            consecutiveFailures = health.consecutiveFailures,
                            successRate = health.recent.takeIf { it.isNotEmpty() }?.let { runs -> runs.count { it }.toDouble() / runs.size },
                        )
                    },
                    requiredContextType = if (request.repository == null) "task" else "repository-task",
                ),
            )
            if (route.decision == AgentRouteDecision.Local) {
                val selected = route.selectedAgent
                    ?: error("Local agent routing returned no selected provider")
                return eligibleProviders.firstOrNull { (provider, _) -> provider.id.value == selected }?.first
                    ?: error("Local agent routing selected unavailable provider $selected")
            }
        }

        val repositorySuffix = if (request.repository != null) {
            if (repositoryAccessRequired) {
                " for linked ${request.repository.source.displayName()} repository"
            } else {
                " in the context of linked ${request.repository.source.displayName()} repository"
            }
        } else {
            ""
        }
        val explicitOnlyHint = providersById.values
            .filter { it.explicitOnly && it !in ordered }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = ". Not chosen automatically: ", postfix = " (assign to a role to use)") { it.id.value }
            .orEmpty()
        error("No agent provider satisfies required capabilities $required$repositorySuffix$explicitOnlyHint")
    }
}

private const val HEALTH_WINDOW = 20

/** The app's durable inference store (platform default [Settings]). */
internal fun durableInferenceSettings(): Settings = chunkedInferenceSettings(Settings())

internal fun chunkedInferenceSettings(delegate: Settings): Settings = ChunkedStringSettings(
    delegate = delegate,
    chunkedKeys = setOf(
        SettingsInferenceStateStore.DEFAULT_STORAGE_KEY,
        SettingsInferenceGenealogyGraph.DEFAULT_STORAGE_KEY,
    ),
)
