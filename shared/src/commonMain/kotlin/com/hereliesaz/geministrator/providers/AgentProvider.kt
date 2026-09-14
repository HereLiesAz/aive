package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlinx.coroutines.flow.Flow

enum class PromptCacheMode {
    Unsupported,
    ImplicitPrefix,
    ExplicitReusableContext,
    ExplicitBreakpoints,
    SessionScoped,
}

data class PromptCacheCapabilities(
    val modes: Set<PromptCacheMode> = setOf(PromptCacheMode.Unsupported),
    val reportsCacheUsage: Boolean = false,
)

data class AgentCapabilities(
    val supported: Set<AgentCapability>,
    val promptCaching: PromptCacheCapabilities = PromptCacheCapabilities(),
    val requiresEnvironmentPlanning: Boolean = false,
)

data class PromptContextBlock(
    val label: String,
    val content: String,
)

data class PromptContext(
    val stablePrefix: List<PromptContextBlock> = emptyList(),
    val dynamicContext: List<PromptContextBlock> = emptyList(),
    val reusePolicy: PromptReusePolicy = PromptReusePolicy.ProviderDefault,
    val cacheNamespace: String? = null,
)

data class AgentTaskRequest(
    val taskRunId: TaskRunId,
    val objective: String,
    val roleInstructions: String,
    val acceptanceCriteria: List<AcceptanceCriterion>,
    val contextArtifacts: List<ArtifactRef> = emptyList(),
    val repository: RepositoryRef? = null,
    val isolationHint: IsolationHint = IsolationHint.ProviderDefault,
    val requirePlanApproval: Boolean = false,
    val promptContext: PromptContext = PromptContext(),
)

enum class IsolationHint {
    ProviderDefault,
    DedicatedBranch,
    DedicatedWorkspace,
    Repoless,
}

data class AgentRunHandle(
    val providerRunId: ProviderRunId,
)

data class ProviderArtifact(
    val kind: ArtifactKind,
    val label: String,
    val uri: String? = null,
    val textContent: String? = null,
    val mediaType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

sealed interface ProviderActionResult {
    data object Accepted : ProviderActionResult
    data class Rejected(val reason: String) : ProviderActionResult
}

sealed interface AgentEvent {
    val runId: ProviderRunId

    data class PlanGenerated(
        override val runId: ProviderRunId,
        val summary: String,
    ) : AgentEvent

    data class PlanApproved(
        override val runId: ProviderRunId,
    ) : AgentEvent

    data class Message(
        override val runId: ProviderRunId,
        val content: String,
    ) : AgentEvent

    /**
     * Provider-reported execution progress. [fraction] is normalized 0f..1f when the provider
     * exposes a real numeric value. Providers that only expose qualitative progress should leave
     * it null and still report [message].
     */
    data class Progress(
        override val runId: ProviderRunId,
        val message: String,
        val fraction: Float? = null,
    ) : AgentEvent {
        init {
            require(fraction == null || fraction in 0f..1f) {
                "Progress fraction must be normalized 0f..1f"
            }
        }
    }

    data class ArtifactProduced(
        override val runId: ProviderRunId,
        val artifact: ProviderArtifact,
    ) : AgentEvent

    data class Completed(
        override val runId: ProviderRunId,
    ) : AgentEvent

    data class Failed(
        override val runId: ProviderRunId,
        val reason: String,
    ) : AgentEvent

    /**
     * Optional telemetry from providers that expose token/cost/cache data. Never affects workflow
     * correctness — emit opportunistically, handle lossily.
     */
    data class UsageReported(
        override val runId: ProviderRunId,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val costUsd: Double? = null,
        val cacheHitFraction: Float? = null,
        val latencyMillis: Long? = null,
    ) : AgentEvent {
        init {
            require(cacheHitFraction == null || cacheHitFraction in 0f..1f) {
                "Cache hit fraction must be normalized 0f..1f"
            }
        }
    }
}

interface AgentProvider {
    val id: AgentProviderId

    suspend fun capabilities(): AgentCapabilities

    /**
     * Whether repository-capable work can be executed against this linked repository.
     * Providers whose repository support is source-specific should override this boundary.
     */
    suspend fun supportsRepository(repository: RepositoryRef?): Boolean = true

    suspend fun start(request: AgentTaskRequest): AgentRunHandle

    fun observe(runId: ProviderRunId): Flow<AgentEvent>

    suspend fun sendMessage(
        runId: ProviderRunId,
        message: String,
    ): ProviderActionResult

    suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult

    suspend fun cancel(runId: ProviderRunId): ProviderActionResult
}
