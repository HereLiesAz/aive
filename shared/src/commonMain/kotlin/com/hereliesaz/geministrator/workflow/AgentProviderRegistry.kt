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
import com.russhwolf.settings.Settings

data class ProviderSelectionRequest(
    val preferredProviderId: AgentProviderId? = null,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val constraints: ProviderConstraints = ProviderConstraints.None,
    val repository: RepositoryRef? = null,
)

class AgentProviderRegistry(
    providers: Collection<AgentProvider>,
    inferenceFabric: CompoundInferenceFabric = SettingsCompoundInferenceFabric(
        SettingsInferenceStateStore(durableInferenceSettings()),
    ),
    val genealogyGovernance: InferenceGenealogyGovernanceRuntime = InferenceGenealogyGovernanceRuntime(
        graph = SettingsInferenceGenealogyGraph(durableInferenceSettings()),
    ),
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    localModelLibrary: LocalModelLibrary = MemoryEpoch8LocalModelLibrary.library,
) {
    val inferenceFabric: CompoundInferenceFabric = GovernedCompoundInferenceFabric(
        delegate = inferenceFabric.withLocalModelLibrary(localModelLibrary),
        governance = genealogyGovernance,
    )
    private val providersById = providers.associateBy { it.id }

    init {
        require(providersById.size == providers.size) { "Provider IDs must be unique" }
    }

    val providerIds: Set<AgentProviderId> get() = providersById.keys

    fun provider(id: AgentProviderId): AgentProvider? = providersById[id]

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
                if (request.repository != null && !provider.supportsRepository(request.repository)) {
                    error(
                        "Provider ${constraints.providerId.value} cannot operate in the context of the linked " +
                            "${request.repository.source.displayName()} repository",
                    )
                }
                return provider
            }
            else -> Unit
        }

        val ordered = buildList {
            request.preferredProviderId?.let { providersById[it] }?.let(::add)
            providersById.values.forEach { provider -> if (provider !in this) add(provider) }
        }

        val eligibleProviders = mutableListOf<Pair<AgentProvider, Set<AgentCapability>>>()
        for (provider in ordered) {
            val capabilities = provider.capabilities().supported
            if (
                capabilities.containsAll(required) &&
                (request.repository == null || provider.supportsRepository(request.repository))
            ) {
                eligibleProviders += provider to capabilities
            }
        }

        if (eligibleProviders.isNotEmpty()) {
            val route = orchestrationUtilities.routeAgent(
                AgentRoutingInput(
                    requiredCapabilities = required.mapTo(linkedSetOf()) { it.name },
                    requiredContextTokens = 0,
                    candidates = eligibleProviders.mapIndexed { index, (provider, capabilities) ->
                        AgentRouteCandidate(
                            id = provider.id.value,
                            capabilities = capabilities.mapTo(linkedSetOf()) { it.name },
                            // Preserve current preferred/registration ordering unless a specialist router overrides it.
                            preferenceRank = index,
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
        error("No agent provider satisfies required capabilities $required$repositorySuffix")
    }
}

private fun durableInferenceSettings(): Settings = ChunkedStringSettings(
    delegate = Settings(),
    chunkedKeys = setOf(
        SettingsInferenceStateStore.DEFAULT_STORAGE_KEY,
        SettingsInferenceGenealogyGraph.DEFAULT_STORAGE_KEY,
    ),
)
