package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact

data class ManagedSessionRequest(
    val providerSelection: ProviderSelectionRequest,
    val taskRequest: AgentTaskRequest,
)

data class ManagedSessionHandle(
    val taskRunId: TaskRunId,
    val providerId: AgentProviderId,
    val providerRunId: ProviderRunId,
    val inferenceInvocationId: String? = null,
) {
    // Invocation metadata is diagnostic/provenance state, not provider-session identity. Persisted
    // handles are reconstructed without it, so equality must remain stable across reconstruction.
    override fun equals(other: Any?): Boolean =
        other is ManagedSessionHandle &&
            taskRunId == other.taskRunId &&
            providerId == other.providerId &&
            providerRunId == other.providerRunId

    override fun hashCode(): Int {
        var result = taskRunId.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + providerRunId.hashCode()
        return result
    }
}

data class ManagedSessionProgress(
    val fraction: Float? = null,
    val message: String? = null,
) {
    init {
        require(fraction == null || fraction in 0f..1f) {
            "Managed session progress must be normalized 0f..1f"
        }
    }
}

enum class ManagedSessionStatus {
    Planning,
    AwaitingApproval,
    Running,
    Completed,
    Failed,
    Unknown,
}

sealed class ManagedSessionFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class ProviderUnavailable(message: String, cause: Throwable? = null) : ManagedSessionFailure(message, cause)
    class ProviderOperationFailed(message: String, cause: Throwable? = null) : ManagedSessionFailure(message, cause)
}

interface ManagedSessionGateway {
    suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId

    suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle

    suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
    ) = Unit

    suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
        request: AgentTaskRequest,
    ) {
        reconnect(handle, initialStatus)
    }

    suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus

    suspend fun progress(handle: ManagedSessionHandle): ManagedSessionProgress? = null

    suspend fun message(
        handle: ManagedSessionHandle,
        message: String,
    ): ProviderActionResult

    suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult

    suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult =
        ProviderActionResult.Rejected("Provider session cancellation is not supported")

    suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact>

    /** Pending usage metrics from the last provider report, or null if not available. */
    suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? = null
}

data class ManagedSessionUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val latencyMillis: Long? = null,
)
