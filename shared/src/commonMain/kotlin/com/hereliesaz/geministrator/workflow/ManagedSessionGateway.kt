package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.providers.ProviderPlanDraft

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

/** What to do with a task whose provider failed (ProviderFailover.kt). */
sealed interface FailoverAdvice {
    /** Restart the task on another provider; [note] tells the keeper, in plain words. */
    data class Restart(val note: String) : FailoverAdvice

    /** Do not switch: [message] says who failed and what to do (every provider failed, or the creature is pinned). */
    data class Stop(val message: String) : FailoverAdvice

    /** Not a failover case (a failure that would repeat anywhere, or only one provider is connected). */
    data object NotApplicable : FailoverAdvice
}

interface ManagedSessionGateway {
    /**
     * Where a task goes after its provider [failedProviderId] failed with [reason], given the
     * [selection] it was dispatched with. The default does not fail over.
     */
    suspend fun adviseFailover(
        failedProviderId: AgentProviderId,
        reason: String?,
        selection: ProviderSelectionRequest,
    ): FailoverAdvice = FailoverAdvice.NotApplicable

    suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId

    suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle

    /**
     * The plan [providerId] drafts for [request] before any run starts, or null when that provider
     * plans inside its own run. See [com.hereliesaz.geministrator.providers.AgentProvider.draftPlan].
     */
    suspend fun draftPlan(providerId: AgentProviderId, request: AgentTaskRequest): String? = null

    /** [draftPlan] with what drafting it spent; the default has none to report. */
    suspend fun draftPlanWithUsage(providerId: AgentProviderId, request: AgentTaskRequest): ProviderPlanDraft? =
        draftPlan(providerId, request)?.let { ProviderPlanDraft(it) }

    suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
    ) = Unit

    suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
        request: AgentTaskRequest,
        providerPlan: String? = null,
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

    /**
     * The runtime is done with [handle] (its task finished, failed, or was stopped, and what the
     * session reported has been read): a gateway forgets everything it kept for the session, so a long
     * process does not hold every finished session's text. The default keeps nothing, so forgets nothing.
     */
    suspend fun release(handle: ManagedSessionHandle) = Unit

    /**
     * Pending usage metrics from the last provider report, or null if not available. Reading drains
     * them: the engine polls every cycle and records each report it gets as an event.
     */
    suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? = null

    /**
     * How many new reports (progress, thinking, messages, artifacts) the session has made so far, or null when
     * this gateway cannot tell. The queen watches whether it changes, so she never reads a creature that has
     * said nothing at all as silent: only one that spoke and then stopped.
     */
    suspend fun progressCount(handle: ManagedSessionHandle): Long? = null

    /**
     * The question the session's agent is waiting on the keeper for (docs/Agent-UI.md), or null when
     * this gateway does not know (the task keeps what it already holds).
     */
    suspend fun keeperQuestion(handle: ManagedSessionHandle): KeeperQuestionState? = null

    /**
     * Sends the keeper's [answer] to the question whose `AgentUi.questionKey` is [questionKey], through
     * the session's ordinary messaging path, and marks that question answered once it is accepted.
     */
    suspend fun answerKeeperQuestion(
        handle: ManagedSessionHandle,
        questionKey: String,
        answer: String,
    ): ProviderActionResult = message(handle, answer)
}

/**
 * What a session knows about its agent's question to the keeper: the [pending] question's message,
 * if any, and the key of the last question answered in this process ([answeredKey]).
 */
data class KeeperQuestionState(
    val pending: String? = null,
    val answeredKey: String? = null,
)

data class ManagedSessionUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val latencyMillis: Long? = null,
    /**
     * The provider spent tokens it did not price: no cost was reported and no price is known for the
     * model (or the reported cost was not a usable number). A dollar cap cannot count this usage, so it
     * says so instead of counting it as free ([SpendCaps]).
     */
    val costUnknown: Boolean = false,
)

/**
 * [this] with every number that cannot be a measurement dropped: a negative or missing token count, a
 * negative, NaN or infinite cost (which would poison every total it is added to), a latency below zero.
 * A cost that was reported but cannot be used leaves the usage's cost unknown, not free.
 */
internal fun ManagedSessionUsage.sanitized(): ManagedSessionUsage {
    val unusableCost = costUsd != null && (!costUsd.isFinite() || costUsd < 0.0)
    return copy(
        inputTokens = inputTokens?.takeIf { it >= 0L },
        outputTokens = outputTokens?.takeIf { it >= 0L },
        costUsd = if (unusableCost) null else costUsd,
        costUnknown = costUnknown || unusableCost,
        cacheHitFraction = cacheHitFraction?.takeIf { it in 0f..1f },
        latencyMillis = latencyMillis?.takeIf { it >= 0L },
    )
}

/** Two reports of the same session added: tokens and cost are summed; the later cache fraction and latency win. */
internal fun ManagedSessionUsage?.plusReport(next: ManagedSessionUsage): ManagedSessionUsage {
    val base = this ?: return next
    fun sum(first: Long?, second: Long?): Long? = if (first == null && second == null) null else (first ?: 0L) + (second ?: 0L)
    return ManagedSessionUsage(
        inputTokens = sum(base.inputTokens, next.inputTokens),
        outputTokens = sum(base.outputTokens, next.outputTokens),
        costUsd = if (base.costUsd == null && next.costUsd == null) null else (base.costUsd ?: 0.0) + (next.costUsd ?: 0.0),
        cacheHitFraction = next.cacheHitFraction ?: base.cacheHitFraction,
        latencyMillis = next.latencyMillis ?: base.latencyMillis,
        costUnknown = base.costUnknown || next.costUnknown,
    )
}

/** Adds one drained report to a task's running total. A report with no token or cost data changes nothing. */
internal fun com.hereliesaz.geministrator.domain.TaskUsage?.plus(
    report: ManagedSessionUsage,
): com.hereliesaz.geministrator.domain.TaskUsage? {
    val clean = report.sanitized()
    if (clean.inputTokens == null && clean.outputTokens == null && clean.costUsd == null && !clean.costUnknown) return this
    val base = this ?: com.hereliesaz.geministrator.domain.TaskUsage()
    return base.copy(
        inputTokens = base.inputTokens + (clean.inputTokens ?: 0),
        outputTokens = base.outputTokens + (clean.outputTokens ?: 0),
        costUsd = when {
            clean.costUsd == null -> base.costUsd
            else -> (base.costUsd ?: 0.0) + clean.costUsd
        },
        costUnknown = base.costUnknown || clean.costUnknown,
    )
}

/**
 * Asks the provider to stop [handle]'s session. Null when it did; otherwise why it did not (the provider
 * rejected the request, or the call threw): a session that is not stopped keeps billing, so a caller
 * records that rather than treating "asked" as "stopped".
 */
internal suspend fun ManagedSessionGateway.cancelFailure(handle: ManagedSessionHandle): String? =
    try {
        when (val result = cancel(handle)) {
            ProviderActionResult.Accepted -> null
            is ProviderActionResult.Rejected -> result.reason.ifBlank { "The provider rejected the cancellation" }
        }
    } catch (failure: Exception) {
        // Including a CancellationException the provider itself threw: callers run this NonCancellable.
        failure.message?.takeIf(String::isNotBlank) ?: "The cancellation failed"
    }
