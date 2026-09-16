package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.inference.InferenceResourceSample
import kotlin.math.roundToLong

/** Measured provider history used only for planning, never for workflow correctness. */
data class InferenceProviderResourceProfile(
    val providerId: AgentProviderId,
    val costSampleCount: Int,
    val meanCostUsd: Double?,
    val latencySampleCount: Int,
    val meanLatencyMillis: Long?,
)

/**
 * Resolves explicitly authorized resource-aware inference into an implemented topology.
 *
 * The resolver is deliberately conservative: insufficient telemetry, unavailable optional MoA
 * participants, a task below the configured complexity threshold, or a budget overrun all resolve
 * to [CompoundInferencePolicy.Single]. It never invents roles or expands permissions.
 */
object ResourceAwareCompoundInferencePolicyResolver {
    suspend fun resolve(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition>,
        providerRegistry: AgentProviderRegistry,
        repository: RepositoryRef? = null,
    ): WorkflowDefinition {
        if (definition.tasks.none { it.compoundInferencePolicy is CompoundInferencePolicy.ResourceAware }) {
            return definition
        }

        val activeRoles = roles.filter(RoleDefinition::enabled)
        val rolesById = activeRoles.associateBy(RoleDefinition::id)
        val profileCache = mutableMapOf<AgentProviderId, InferenceProviderResourceProfile>()

        val tasks = buildList {
            for (task in definition.tasks) {
                val policy = task.compoundInferencePolicy
                if (policy !is CompoundInferencePolicy.ResourceAware) {
                    add(task)
                    continue
                }

                val resolved = resolveTask(
                    task = task,
                    policy = policy,
                    activeRoles = activeRoles,
                    rolesById = rolesById,
                    providerRegistry = providerRegistry,
                    repository = repository,
                    profileCache = profileCache,
                )
                add(task.copy(compoundInferencePolicy = resolved))
            }
        }
        return definition.copy(tasks = tasks)
    }

    private suspend fun resolveTask(
        task: TaskDefinition,
        policy: CompoundInferencePolicy.ResourceAware,
        activeRoles: List<RoleDefinition>,
        rolesById: Map<RoleDefinitionId, RoleDefinition>,
        providerRegistry: AgentProviderRegistry,
        repository: RepositoryRef?,
        profileCache: MutableMap<AgentProviderId, InferenceProviderResourceProfile>,
    ): CompoundInferencePolicy {
        require(task.effectiveExecutor() is TaskExecutor.RoleAgent) {
            "Resource-aware compound inference is only supported for role-agent tasks (${task.id.value})"
        }

        val complexityScore = task.dependsOn.size * DEPENDENCY_WEIGHT +
            task.acceptanceCriteria.size +
            task.requiredArtifacts.size
        if (complexityScore < policy.minimumComplexityScore) {
            return CompoundInferencePolicy.Single
        }

        val proposerRoles = policy.proposerRoleIds.map { roleId ->
            requireNotNull(rolesById[roleId]) {
                "Resource-aware MoA proposer role ${roleId.value} is not registered and enabled"
            }
        }
        proposerRoles.forEach { role ->
            require(RoleAuthority.Implement !in role.authorities) {
                "Resource-aware MoA proposer ${role.name} may not hold implementation authority"
            }
            require(AgentCapability.RepositoryWrite !in role.capabilitiesRequired) {
                "Resource-aware MoA proposer ${role.name} may not require repository-write capability"
            }
        }
        val aggregatorRole = requireNotNull(rolesById[policy.aggregatorRoleId]) {
            "Resource-aware MoA aggregator role ${policy.aggregatorRoleId.value} is not registered and enabled"
        }
        val verifierRole = task.verifierRole(activeRoles, rolesById)

        val proposerProviders = mutableListOf<AgentProviderId>()
        for (role in proposerRoles) {
            val providerId = providerRegistry.trySelectProviderId(
                role = role,
                constraints = ProviderConstraints.None,
                repository = repository,
            ) ?: return CompoundInferencePolicy.Single
            proposerProviders += providerId
        }
        val aggregatorProvider = providerRegistry.trySelectProviderId(
            role = aggregatorRole,
            constraints = task.providerConstraints,
            repository = repository,
        ) ?: return CompoundInferencePolicy.Single
        val verifierProvider = providerRegistry.trySelectProviderId(
            role = verifierRole,
            constraints = ProviderConstraints.None,
            repository = repository,
        ) ?: return CompoundInferencePolicy.Single

        val providerIds = (proposerProviders + aggregatorProvider + verifierProvider).toSet()
        for (providerId in providerIds) {
            if (providerId !in profileCache) {
                profileCache[providerId] = providerRegistry.resourceProfile(providerId)
            }
        }

        if (!fitsCostBudget(
                policy = policy,
                proposerProviders = proposerProviders,
                aggregatorProvider = aggregatorProvider,
                verifierProvider = verifierProvider,
                profiles = profileCache,
            )
        ) {
            return CompoundInferencePolicy.Single
        }
        if (!fitsLatencyBudget(
                policy = policy,
                proposerProviders = proposerProviders,
                aggregatorProvider = aggregatorProvider,
                verifierProvider = verifierProvider,
                profiles = profileCache,
            )
        ) {
            return CompoundInferencePolicy.Single
        }

        return CompoundInferencePolicy.CentralizedMixtureOfAgents(
            proposerRoleIds = policy.proposerRoleIds,
            aggregatorRoleId = policy.aggregatorRoleId,
        )
    }

    private fun fitsCostBudget(
        policy: CompoundInferencePolicy.ResourceAware,
        proposerProviders: List<AgentProviderId>,
        aggregatorProvider: AgentProviderId,
        verifierProvider: AgentProviderId,
        profiles: Map<AgentProviderId, InferenceProviderResourceProfile>,
    ): Boolean {
        val ceiling = policy.maxEstimatedCostUsd ?: return true
        var estimated = 0.0
        for (providerId in proposerProviders + aggregatorProvider + verifierProvider) {
            val profile = profiles.getValue(providerId)
            if (profile.costSampleCount < policy.minimumHistoricalSamplesPerProvider) return false
            estimated += profile.meanCostUsd ?: return false
        }
        return estimated <= ceiling
    }

    private fun fitsLatencyBudget(
        policy: CompoundInferencePolicy.ResourceAware,
        proposerProviders: List<AgentProviderId>,
        aggregatorProvider: AgentProviderId,
        verifierProvider: AgentProviderId,
        profiles: Map<AgentProviderId, InferenceProviderResourceProfile>,
    ): Boolean {
        val ceiling = policy.maxEstimatedLatencyMillis ?: return true
        val involved = (proposerProviders + aggregatorProvider + verifierProvider).toSet()
        if (involved.any { providerId ->
                val profile = profiles.getValue(providerId)
                profile.latencySampleCount < policy.minimumHistoricalSamplesPerProvider || profile.meanLatencyMillis == null
            }
        ) {
            return false
        }
        val proposerLatency = proposerProviders.maxOf { requireNotNull(profiles.getValue(it).meanLatencyMillis) }
        val aggregatorLatency = requireNotNull(profiles.getValue(aggregatorProvider).meanLatencyMillis)
        val verifierLatency = requireNotNull(profiles.getValue(verifierProvider).meanLatencyMillis)
        return proposerLatency + aggregatorLatency + verifierLatency <= ceiling
    }

    private const val DEPENDENCY_WEIGHT: Int = 2
}

private suspend fun AgentProviderRegistry.trySelectProviderId(
    role: RoleDefinition,
    constraints: ProviderConstraints,
    repository: RepositoryRef?,
): AgentProviderId? = try {
    select(
        ProviderSelectionRequest(
            preferredProviderId = role.preferredProviderId,
            requiredCapabilities = role.capabilitiesRequired,
            constraints = constraints,
            repository = repository,
        ),
    ).id
} catch (_: IllegalStateException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun AgentProviderRegistry.resourceProfile(
    providerId: AgentProviderId,
): InferenceProviderResourceProfile {
    val samples = mutableListOf<InferenceResourceSample>()
    genealogyGovernance.graph.all().forEach { node ->
        samples += inferenceFabric.resourceTelemetry.samples(node.invocationId)
            .filter { it.providerId == providerId }
    }
    val costs = samples.mapNotNull(InferenceResourceSample::costUsd)
    val latencies = samples.mapNotNull(InferenceResourceSample::latencyMillis)
    return InferenceProviderResourceProfile(
        providerId = providerId,
        costSampleCount = costs.size,
        meanCostUsd = if (costs.isEmpty()) null else costs.average(),
        latencySampleCount = latencies.size,
        meanLatencyMillis = if (latencies.isEmpty()) null else latencies.map(Long::toDouble).average().roundToLong(),
    )
}

private fun TaskDefinition.verifierRole(
    activeRoles: Collection<RoleDefinition>,
    rolesById: Map<RoleDefinitionId, RoleDefinition>,
): RoleDefinition {
    val explicitVerifier = (verificationPolicy as? VerificationPolicy.Required)?.verifierRoleId
    if (explicitVerifier != null) {
        val role = requireNotNull(rolesById[explicitVerifier]) {
            "Resource-aware MoA verifier role ${explicitVerifier.value} is not registered and enabled"
        }
        require(RoleAuthority.Verify in role.authorities) {
            "Resource-aware MoA verifier ${role.name} does not have Verify authority"
        }
        return role
    }

    val eligible = activeRoles.filter { RoleAuthority.Verify in it.authorities }
    return eligible.firstOrNull { it.id == BuiltInRoles.QaEngineer.id }
        ?: eligible.firstOrNull()
        ?: error("Resource-aware MoA requires an enabled role with Verify authority")
}
