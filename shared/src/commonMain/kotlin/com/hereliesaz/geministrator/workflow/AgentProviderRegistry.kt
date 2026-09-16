package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.inference.BlueprintCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.CompoundInferenceFabric
import com.hereliesaz.geministrator.inference.GovernedCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime
import com.hereliesaz.geministrator.providers.AgentProvider

data class ProviderSelectionRequest(
    val preferredProviderId: AgentProviderId? = null,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val constraints: ProviderConstraints = ProviderConstraints.None,
    val repository: RepositoryRef? = null,
)

class AgentProviderRegistry(
    providers: Collection<AgentProvider>,
    inferenceFabric: CompoundInferenceFabric = BlueprintCompoundInferenceFabric(),
    val genealogyGovernance: InferenceGenealogyGovernanceRuntime = InferenceGenealogyGovernanceRuntime(),
) {
    val inferenceFabric: CompoundInferenceFabric = GovernedCompoundInferenceFabric(
        delegate = inferenceFabric,
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

        suspend fun eligible(provider: AgentProvider): Boolean =
            provider.capabilities().supported.containsAll(required) &&
                (request.repository == null || provider.supportsRepository(request.repository))

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

        for (provider in ordered) {
            if (eligible(provider)) return provider
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
