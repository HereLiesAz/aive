package com.hereliesaz.geministrator.duplex

import com.hereliesaz.geministrator.memory.MemorySessionObserver
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ManagedSessionGateway
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import kotlinx.coroutines.CancellationException

/**
 * An opt-in session adapter: the peer may offer text to an existing provider-backed task, but it
 * does not gain permission to launch workflows, approve plans, invoke tools or touch repositories.
 * The same gateway path processes memory cues and outside messages. Rejected messages stay queued.
 * A request response reports *delivery acceptance*, not completion of the requested work.
 */
class DuplexManagedSessionBridge(
    private val peer: DuplexMcpPeer,
    private val gateway: ManagedSessionGateway,
    private val handle: ManagedSessionHandle,
) {
    private val delivered = mutableSetOf<String>()
    private val pendingReplies = mutableMapOf<String, String>()
    private val replySent = mutableSetOf<String>()

    /** Drain acknowledged messages in order. After transport failure, repeat to retry safely. */
    suspend fun drain(maxBatch: Int = 50): Int {
        val messages = peer.receive(limit = maxBatch)
        var accepted = 0
        for (entry in messages) {
            if (entry.id !in delivered) {
                when (entry.kind) {
                    DuplexKind.Message, DuplexKind.Request -> {
                        val result = gateway.message(handle, entry.body)
                        when (result) {
                            ProviderActionResult.Accepted -> {
                                delivered += entry.id
                                accepted++
                                if (entry.kind == DuplexKind.Request) {
                                    pendingReplies[entry.id] = "accepted by provider session"
                                }
                            }
                            is ProviderActionResult.Rejected -> {
                                if (entry.kind == DuplexKind.Request) {
                                    delivered += entry.id
                                    pendingReplies[entry.id] = "rejected: ${result.reason}"
                                } else {
                                    // Preserve ordering: a rejected message is not consumed.
                                    return accepted
                                }
                            }
                        }
                    }
                    // These are information, not permission to execute remote instructions.
                    DuplexKind.Event, DuplexKind.Response, DuplexKind.Cancel -> delivered += entry.id
                }
            }
            val reply = pendingReplies[entry.id]
            if (reply != null && entry.id !in replySent) {
                peer.send(DuplexKind.Response, reply, entry.id)
                replySent += entry.id
            }
            peer.acknowledge(entry.seq)
            delivered -= entry.id
            pendingReplies.remove(entry.id)
            replySent -= entry.id
        }
        return accepted
    }
}

/**
 * Compose with Aive's existing MemorySessionObserver, not instead of it. Only user-facing provider
 * events are forwarded; private thinking, memory recall messages and token telemetry stay private.
 * Both sides keep their own event and memory governance. Provider events are not tool invocations.
 */
class DuplexSessionObserver(
    private val memory: MemorySessionObserver,
    private val peer: DuplexMcpPeer,
) : MemorySessionObserver {
    override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) {
        memory.onSessionStarted(handle, request)
    }

    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
        memory.onSessionEvent(handle, event)
        publish(event)
    }

    override suspend fun onSessionEvent(
        handle: ManagedSessionHandle,
        event: AgentEvent,
        reply: suspend (String) -> ProviderActionResult,
    ) {
        memory.onSessionEvent(handle, event, reply)
        publish(event)
    }

    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
        memory.onSessionFinished(handle, status)
    }

    override suspend fun annotateUserMessage(handle: ManagedSessionHandle, text: String): String =
        memory.annotateUserMessage(handle, text)

    private suspend fun publish(event: AgentEvent) {
        val text = when (event) {
            is AgentEvent.Message -> event.content.takeUnless { it.startsWith("⟦memory⟧") }
            is AgentEvent.Progress -> event.message
            is AgentEvent.PlanGenerated -> event.summary
            is AgentEvent.ArtifactProduced -> event.artifact.label
            is AgentEvent.Failed -> event.reason
            // Thinking is intentionally not exported; approvals and usage are internal signals.
            is AgentEvent.Thinking, is AgentEvent.PlanApproved, is AgentEvent.Completed,
            is AgentEvent.UsageReported -> null
        }?.takeIf { it.isNotBlank() } ?: return
        try {
            peer.send(DuplexKind.Event, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // External peer downtime must not change a task's result or memory banking.
            // The session bridge is opt-in, and its inbox is not a durable audit log.
        }
    }
}
