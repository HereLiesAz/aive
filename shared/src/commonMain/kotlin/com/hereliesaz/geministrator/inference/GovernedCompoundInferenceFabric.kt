package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentTaskRequest

/**
 * Decorates the shared inference fabric so every prepared concrete invocation enters genealogy
 * governance before provider execution.
 *
 * Governance registration is structural and advisory. It does not alter prompts, provider output,
 * workflow state, or memory contents.
 */
class GovernedCompoundInferenceFabric(
    private val delegate: CompoundInferenceFabric,
    val governance: InferenceGenealogyGovernanceRuntime,
) : CompoundInferenceFabric by delegate {
    override suspend fun prepareDispatch(
        request: AgentTaskRequest,
        providerId: AgentProviderId,
        capabilities: AgentCapabilities,
    ): PreparedInferenceDispatch {
        val prepared = delegate.prepareDispatch(request, providerId, capabilities)
        governance.registerInvocation(prepared.request.compoundInference.genealogy)
        return prepared
    }
}
