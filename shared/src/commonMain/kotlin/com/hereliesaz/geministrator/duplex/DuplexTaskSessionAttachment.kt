package com.hereliesaz.geministrator.duplex

import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.NoOpMemorySessionObserver
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.workflow.ManagedSessionGateway
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional lifecycle hook for a provider-backed task. A message delivered through this boundary
 * still goes through ManagedSessionGateway.message; no peer obtains workflow or provider authority.
 * Implementations must not throw ordinary transport errors into the task engine.
 */
interface DuplexSessionAttachment {
    suspend fun onSessionStarted(handle: ManagedSessionHandle, gateway: ManagedSessionGateway)
    suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent)
    suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus)
}

/**
 * Explicitly pairs ONE task run to ONE MCP peer. New sessions and reconnections for that task
 * attach automatically, with independent inbound and outbound workers. Other tasks stay isolated.
 * The caller owns the peer and must close it when the user's pairing is removed.
 *
 * Inbound messages have at-least-once delivery until acknowledged. Provider events are buffered
 * in memory (max 128); overflows are counted, never silently advertised as successful delivery.
 * This attachment is opt-in; constructing it neither starts a workflow nor grants tool access.
 */
class DuplexTaskSessionAttachment(
    private val peer: DuplexMcpPeer,
    private val taskRunId: TaskRunId,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 250L,
) : DuplexSessionAttachment {
    init { require(pollMillis > 0L) }

    private val mutex = Mutex()
    private var active: ManagedSessionHandle? = null
    private var inbound: Job? = null
    private var outbound: Job? = null
    private var outboundEvents: Channel<AgentEvent>? = null
    private var droppedEvents: Long = 0L

    suspend fun droppedEventCount(): Long = mutex.withLock { droppedEvents }

    override suspend fun onSessionStarted(handle: ManagedSessionHandle, gateway: ManagedSessionGateway) {
        if (handle.taskRunId != taskRunId) return
        mutex.withLock {
            if (active == handle || active != null) return@withLock
            active = handle
            val stream = Channel<AgentEvent>(capacity = 128)
            outboundEvents = stream
            val publisher = DuplexSessionObserver(NoOpMemorySessionObserver, peer, handle)
            outbound = scope.launch {
                for (event in stream) publisher.onSessionEvent(handle, event)
            }
            inbound = scope.launch {
                val receiver = DuplexManagedSessionBridge(peer, gateway, handle)
                while (isActive) {
                    try {
                        receiver.drain()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A severed transport cannot fail the provider. Retain unacknowledged
                        // messages on the broker; a later drain will retry.
                    }
                    delay(pollMillis)
                }
            }
        }
    }

    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
        mutex.withLock {
            if (active != handle) return@withLock
            if (outboundEvents?.trySend(event)?.isSuccess != true) droppedEvents++
        }
    }

    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
        mutex.withLock {
            if (active != handle) return@withLock
            active = null
            inbound?.cancel()
            inbound = null
            // Closing the sender lets queued provider events flush in sequence.
            outboundEvents?.close()
            outboundEvents = null
            outbound = null
        }
    }
}
