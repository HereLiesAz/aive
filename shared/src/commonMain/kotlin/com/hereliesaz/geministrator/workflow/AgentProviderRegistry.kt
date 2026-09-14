package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.providers.AgentProvider

data class ProviderSelectionRequest(
    val preferredProviderId: AgentProviderId? = null,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val constraints: ProviderConstraints = ProviderConstraints.None,
    val repository: RepositoryRef? = null,
)

class AgentProviderRegistry(
    providers: Collection<AgentProvider>,
) {
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
                (!repositoryAccessRequired || provider.supportsRepository(request.repository))

        when (val constraints = request.constraints) {
            is ProviderConstraints.RequireProvider -> {
                val provider = providersById[constraints.providerId]
                    ?: error("Required provider ${constraints.providerId.value} is not registered")
                if (!provider.capabilities().supported.containsAll(required)) {
                    error("Provider ${constraints.providerId.value} does not satisfy required capabilities $required")
                }
                if (repositoryAccessRequired && !provider.supportsRepository(request.repository)) {
                    val source = request.repository?.source?.displayName() ?: "repository"
                    error("Provider ${constraints.providerId.value} cannot operate on the linked $source repository")
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

        val repositorySuffix = if (repositoryAccessRequired && request.repository != null) {
            " for linked ${request.repository.source.displayName()} repository"
        } else {
            ""
        }
        error("No agent provider satisfies required capabilities $required$repositorySuffix")
    }
}
