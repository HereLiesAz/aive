package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class McpConsentDecision {
    /** Allow this server's tools for the rest of this run. */
    AllowForRun,

    /** Allow this server's tools from now on (its "Always allow" setting). */
    AllowAlways,
    Deny,
}

/** The first tool call a run makes to a server the keeper has not always allowed. */
data class McpConsentRequest(
    val serverId: String,
    val serverName: String,
    val serverLocation: String,
    val toolName: String,
    /** The call's arguments, shortened: what would leave the device. */
    val argumentsPreview: String,
    /** The workflow run (or, outside one, the task run) the consent covers. */
    val runKey: String,
)

/** Asks the keeper whether a server's tools may run. Reachability or a listing is never consent. */
fun interface McpConsentGate {
    suspend fun decide(request: McpConsentRequest): McpConsentDecision

    companion object {
        /** No keeper to ask: every call to a server not always allowed is refused. */
        val DenyUnasked: McpConsentGate = McpConsentGate { McpConsentDecision.Deny }
    }
}

/**
 * The approval gate the app shows: one question at a time in [pending], answered with [answer].
 * Unanswered questions are refused after [timeoutMillis], so a run never waits forever on a
 * window nobody is looking at.
 */
class PromptingMcpConsentGate(
    private val timeoutMillis: Long = 10 * 60_000L,
) : McpConsentGate {
    private val queue = Mutex()
    private val current = MutableStateFlow<Pair<McpConsentRequest, CompletableDeferred<McpConsentDecision>>?>(null)
    private val _pending = MutableStateFlow<McpConsentRequest?>(null)

    /** The question waiting for the keeper, or null. */
    val pending: StateFlow<McpConsentRequest?> = _pending.asStateFlow()

    override suspend fun decide(request: McpConsentRequest): McpConsentDecision = queue.withLock {
        val answer = CompletableDeferred<McpConsentDecision>()
        current.value = request to answer
        _pending.value = request
        try {
            withTimeoutOrNull(timeoutMillis) { answer.await() } ?: McpConsentDecision.Deny
        } finally {
            current.value = null
            _pending.value = null
        }
    }

    /** The keeper's answer to [pending]; ignored when nothing is waiting. */
    fun answer(decision: McpConsentDecision) {
        current.value?.second?.complete(decision)
    }
}
