package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.DistributedTaskEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RelayPeer {
    suspend fun send(message: ComputeRelayServerMessage)
}

class RelayPool(
    private val poolId: String,
    private val nowEpochMillis: () -> Long,
) {
    private data class NodeSession(
        val descriptor: ComputeNodeDescriptor,
        val peer: RelayPeer,
    )

    private data class LeaseRecord(
        val envelope: DistributedTaskEnvelope,
        val originNodeId: String,
        val workerNodeId: String? = null,
        val expiresAtEpochMillis: Long? = null,
        val progress: com.hereliesaz.geministrator.distributed.DistributedExecutionProgress? = null,
        val result: com.hereliesaz.geministrator.distributed.DistributedExecutionResult? = null,
        val completedAtEpochMillis: Long? = null,
    )

    private val mutex = Mutex()
    private val nodes = linkedMapOf<String, NodeSession>()
    private val leases = linkedMapOf<String, LeaseRecord>()

    suspend fun register(node: ComputeNodeDescriptor, peer: RelayPeer): List<ComputeNodeDescriptor> {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        var displacedPeer: RelayPeer? = null
        val online = mutex.withLock {
            val previous = nodes.put(node.nodeId, NodeSession(node, peer))
            val message = if (previous == null) {
                ComputeRelayServerMessage.NodeJoined(node)
            } else {
                // A different connection is replacing an existing registration for the same node ID.
                // Notify the displaced peer so it knows its connection is no longer active.
                displacedPeer = previous.peer
                ComputeRelayServerMessage.NodeUpdated(node)
            }
            nodes.values
                .filter { it.descriptor.nodeId != node.nodeId }
                .forEach { outbound += it.peer to message }
            nodes.values.map(NodeSession::descriptor)
        }
        displacedPeer?.let {
            runCatching {
                it.send(
                    ComputeRelayServerMessage.Error(
                        code = "displaced",
                        message = "This node has been re-registered by a new connection; this connection is no longer active",
                    ),
                )
            }
        }
        sendAll(outbound)
        return online
    }

    suspend fun updateNode(nodeId: String, updated: ComputeNodeDescriptor) {
        require(nodeId == updated.nodeId) { "nodeId cannot change during a relay session" }
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val current = nodes[nodeId] ?: return
            nodes[nodeId] = current.copy(descriptor = updated)
            nodes.values
                .filter { it.descriptor.nodeId != nodeId }
                .forEach { outbound += it.peer to ComputeRelayServerMessage.NodeUpdated(updated) }
        }
        sendAll(outbound)
        offerPendingLeases()
    }

    suspend fun unregister(nodeId: String, peer: RelayPeer) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        val reoffer = mutableListOf<String>()
        mutex.withLock {
            val current = nodes[nodeId] ?: return
            // An older connection can finish after a replacement has already registered. Only the
            // currently registered peer owns the right to remove this logical node.
            if (current.peer !== peer) return
            val removed = nodes.remove(nodeId) ?: return
            nodes.values.forEach { outbound += it.peer to ComputeRelayServerMessage.NodeLeft(nodeId) }

            // Origin-owned leases intentionally survive origin disconnects. Their worker keeps
            // running, and terminal state is retained so the same logical node can resume later.
            val workerLeases = leases.values.filter {
                it.workerNodeId == removed.descriptor.nodeId && it.result == null
            }
            workerLeases.forEach { lease ->
                leases[lease.envelope.leaseId] = lease.copy(
                    workerNodeId = null,
                    expiresAtEpochMillis = null,
                )
                nodes[lease.originNodeId]?.let { origin ->
                    outbound += origin.peer to ComputeRelayServerMessage.LeaseRequeued(
                        leaseId = lease.envelope.leaseId,
                        reason = "Worker disconnected",
                    )
                }
                reoffer += lease.envelope.leaseId
            }
        }
        sendAll(outbound)
        reoffer.forEach { offerLease(it) }
    }

    suspend fun replayOriginLeases(nodeId: String, peer: RelayPeer) {
        val replay = mutex.withLock {
            val current = nodes[nodeId] ?: return
            if (current.peer !== peer) return
            leases.values
                .filter { it.originNodeId == nodeId }
                .flatMap(::replayMessages)
                .map { peer to it }
        }
        sendAll(replay)
    }

    suspend fun publish(originNodeId: String, envelope: DistributedTaskEnvelope) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val origin = nodes[originNodeId]
                ?: throw IllegalStateException("Origin node is not registered")
            require(envelope.originNodeId == originNodeId) { "Lease origin does not match connected node" }
            val existing = leases[envelope.leaseId]
            if (existing != null) {
                require(existing.originNodeId == originNodeId) {
                    "Lease ID is already owned by another origin"
                }
                replayMessages(existing).forEach { outbound += origin.peer to it }
                return@withLock
            }
            leases[envelope.leaseId] = LeaseRecord(
                envelope = envelope,
                originNodeId = originNodeId,
            )
            outbound += origin.peer to ComputeRelayServerMessage.LeaseAccepted(envelope.leaseId)
        }
        sendAll(outbound)
        offerLease(envelope.leaseId)
    }

    suspend fun claim(workerNodeId: String, leaseId: String) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val worker = nodes[workerNodeId] ?: return
            val lease = leases[leaseId] ?: return
            if (lease.result != null) {
                outbound += worker.peer to ComputeRelayServerMessage.Error(
                    code = "lease-terminal",
                    message = "Lease is already complete",
                    leaseId = leaseId,
                )
                return@withLock
            }
            if (lease.workerNodeId != null) {
                outbound += worker.peer to ComputeRelayServerMessage.Error(
                    code = "lease-already-claimed",
                    message = "Lease is already claimed",
                    leaseId = leaseId,
                )
                return@withLock
            }
            if (workerNodeId == lease.originNodeId ||
                !worker.descriptor.canRun(lease.envelope.requirements, lease.envelope.delegatedExecutor)
            ) {
                outbound += worker.peer to ComputeRelayServerMessage.Error(
                    code = "worker-ineligible",
                    message = "This node is not eligible for the lease",
                    leaseId = leaseId,
                )
                return@withLock
            }
            val activeCount = leases.values.count { it.result == null && it.workerNodeId == workerNodeId }
            if (activeCount >= worker.descriptor.maxParallelLeases) {
                outbound += worker.peer to ComputeRelayServerMessage.Error(
                    code = "worker-capacity",
                    message = "This node is at its advertised lease capacity",
                    leaseId = leaseId,
                )
                return@withLock
            }

            val expires = nowEpochMillis() + lease.envelope.leaseDurationMillis
            leases[leaseId] = lease.copy(workerNodeId = workerNodeId, expiresAtEpochMillis = expires)
            val claimed = ComputeRelayServerMessage.LeaseClaimed(
                leaseId = leaseId,
                workerNodeId = workerNodeId,
                expiresAtEpochMillis = expires,
            )
            outbound += worker.peer to claimed
            nodes[lease.originNodeId]?.let { origin -> outbound += origin.peer to claimed }
        }
        sendAll(outbound)
    }

    suspend fun heartbeat(workerNodeId: String, leaseId: String) {
        mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.result != null || lease.workerNodeId != workerNodeId) return
            leases[leaseId] = lease.copy(
                expiresAtEpochMillis = nowEpochMillis() + lease.envelope.leaseDurationMillis,
            )
        }
    }

    suspend fun progress(
        workerNodeId: String,
        leaseId: String,
        progress: com.hereliesaz.geministrator.distributed.DistributedExecutionProgress,
    ) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.result != null || lease.workerNodeId != workerNodeId) return
            leases[leaseId] = lease.copy(progress = progress)
            nodes[lease.originNodeId]?.let { origin ->
                outbound += origin.peer to ComputeRelayServerMessage.LeaseProgress(
                    leaseId = leaseId,
                    workerNodeId = workerNodeId,
                    progress = progress,
                )
            }
        }
        sendAll(outbound)
    }

    suspend fun complete(
        workerNodeId: String,
        leaseId: String,
        result: com.hereliesaz.geministrator.distributed.DistributedExecutionResult,
    ) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.result != null || lease.workerNodeId != workerNodeId) return
            leases[leaseId] = lease.copy(
                expiresAtEpochMillis = null,
                result = result,
                completedAtEpochMillis = nowEpochMillis(),
            )
            val completed = ComputeRelayServerMessage.LeaseCompleted(
                leaseId = leaseId,
                workerNodeId = workerNodeId,
                result = result,
            )
            nodes[lease.originNodeId]?.let { origin -> outbound += origin.peer to completed }
            nodes[workerNodeId]?.let { worker -> outbound += worker.peer to completed }
        }
        sendAll(outbound)
        offerPendingLeases()
    }

    suspend fun cancel(originNodeId: String, leaseId: String, reason: String?) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.originNodeId != originNodeId) return
            leases.remove(leaseId)
            val cancelled = ComputeRelayServerMessage.LeaseCancelled(leaseId, reason)
            nodes[lease.originNodeId]?.let { origin -> outbound += origin.peer to cancelled }
            lease.workerNodeId?.let(nodes::get)?.let { worker -> outbound += worker.peer to cancelled }
        }
        sendAll(outbound)
        offerPendingLeases()
    }

    suspend fun sweepExpired() {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        val reoffer = mutableListOf<String>()
        val now = nowEpochMillis()
        mutex.withLock {
            leases.entries.removeAll { (_, lease) ->
                lease.result != null &&
                    (lease.completedAtEpochMillis ?: Long.MAX_VALUE) + TERMINAL_RETENTION_MILLIS <= now
            }
            leases.values
                .filter {
                    it.result == null &&
                        it.workerNodeId != null &&
                        (it.expiresAtEpochMillis ?: Long.MAX_VALUE) <= now
                }
                .forEach { lease ->
                    val oldWorker = lease.workerNodeId
                    leases[lease.envelope.leaseId] = lease.copy(
                        workerNodeId = null,
                        expiresAtEpochMillis = null,
                    )
                    nodes[lease.originNodeId]?.let { origin ->
                        outbound += origin.peer to ComputeRelayServerMessage.LeaseRequeued(
                            leaseId = lease.envelope.leaseId,
                            reason = "Worker lease expired",
                        )
                    }
                    oldWorker?.let(nodes::get)?.let { worker ->
                        outbound += worker.peer to ComputeRelayServerMessage.LeaseCancelled(
                            leaseId = lease.envelope.leaseId,
                            reason = "Lease expired and was requeued",
                        )
                    }
                    reoffer += lease.envelope.leaseId
                }
        }
        sendAll(outbound)
        reoffer.forEach { offerLease(it) }
    }

    suspend fun pendingLeaseCount(): Int = mutex.withLock { leases.values.count { it.result == null } }

    suspend fun isEmpty(): Boolean = mutex.withLock { nodes.isEmpty() && leases.isEmpty() }

    private suspend fun offerPendingLeases() {
        val ids = mutex.withLock {
            leases.values.filter { it.workerNodeId == null }.map { it.envelope.leaseId }
        }
        ids.forEach { offerLease(it) }
    }

    private suspend fun offerLease(leaseId: String) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        mutex.withLock {
            val lease = leases[leaseId] ?: return
            if (lease.result != null || lease.workerNodeId != null) return
            val activeByNode = leases.values
                .filter { it.result == null }
                .mapNotNull(LeaseRecord::workerNodeId)
                .groupingBy { it }
                .eachCount()
            nodes.values
                .asSequence()
                .filter { it.descriptor.nodeId != lease.originNodeId }
                .filter {
                    it.descriptor.canRun(
                        lease.envelope.requirements,
                        lease.envelope.delegatedExecutor,
                    )
                }
                .filter {
                    (activeByNode[it.descriptor.nodeId] ?: 0) < it.descriptor.maxParallelLeases
                }
                .sortedByDescending { it.descriptor.preferenceScore(lease.envelope.requirements) }
                .forEach { worker ->
                    outbound += worker.peer to ComputeRelayServerMessage.LeaseOffered(lease.envelope)
                }
        }
        sendAll(outbound)
    }

    private fun replayMessages(lease: LeaseRecord): List<ComputeRelayServerMessage> = buildList {
        add(ComputeRelayServerMessage.LeaseAccepted(lease.envelope.leaseId))
        val workerNodeId = lease.workerNodeId
        if (workerNodeId != null && lease.result == null) {
            add(
                ComputeRelayServerMessage.LeaseClaimed(
                    leaseId = lease.envelope.leaseId,
                    workerNodeId = workerNodeId,
                    expiresAtEpochMillis = lease.expiresAtEpochMillis ?: nowEpochMillis(),
                ),
            )
            lease.progress?.let { progress ->
                add(
                    ComputeRelayServerMessage.LeaseProgress(
                        leaseId = lease.envelope.leaseId,
                        workerNodeId = workerNodeId,
                        progress = progress,
                    ),
                )
            }
        }
        lease.result?.let { result ->
            add(
                ComputeRelayServerMessage.LeaseCompleted(
                    leaseId = lease.envelope.leaseId,
                    workerNodeId = workerNodeId ?: "remote-node",
                    result = result,
                ),
            )
        }
    }

    private suspend fun sendAll(outbound: List<Pair<RelayPeer, ComputeRelayServerMessage>>) {
        outbound.forEach { (peer, message) ->
            runCatching { peer.send(message) }
        }
    }

    override fun toString(): String = "RelayPool(" + poolId + ")"

    private companion object {
        const val TERMINAL_RETENTION_MILLIS: Long = 24L * 60L * 60L * 1_000L
    }
}
