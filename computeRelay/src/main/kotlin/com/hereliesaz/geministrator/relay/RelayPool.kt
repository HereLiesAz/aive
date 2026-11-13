package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.COMPUTE_NODE_SIGNATURE_ALGORITHM
import com.hereliesaz.geministrator.distributed.ComputeNodeAuth
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentRequest
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.DISTRIBUTED_COMPUTE_PROTOCOL_VERSION
import com.hereliesaz.geministrator.distributed.DistributedTaskEnvelope
import com.hereliesaz.geministrator.distributed.EnrolledComputeNode
import com.hereliesaz.geministrator.domain.TaskRunStatus
import java.security.PublicKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RelayPeer {
    suspend fun send(message: ComputeRelayServerMessage)

    /** Ends the peer's connection; the relay calls it when the node behind it is revoked. */
    suspend fun close(reason: String) = Unit
}

/** The outcome of checking a signed connection request against a pool's enrolled nodes. */
enum class NodeAuthentication(val errorCode: String) {
    Authenticated(""),
    Malformed(ComputeNodeAuth.ERROR_UNAUTHORIZED),
    StaleRequest(ComputeNodeAuth.ERROR_STALE_REQUEST),
    NotEnrolled(ComputeNodeAuth.ERROR_NODE_NOT_ENROLLED),
    Revoked(ComputeNodeAuth.ERROR_NODE_REVOKED),
    BadSignature(ComputeNodeAuth.ERROR_BAD_SIGNATURE),
    Replayed(ComputeNodeAuth.ERROR_REPLAYED_REQUEST),
}

/** An enrolment the pool refuses; [code] is one of the `ComputeNodeAuth.ERROR_*` codes. */
class NodeEnrolmentException(val code: String, message: String) : Exception(message)

class RelayPool(
    private val poolId: String,
    private val nowEpochMillis: () -> Long,
    private val stateStore: RelayStateStore = NoopRelayStateStore,
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
        /** Relay clock, not the origin's: an unfinished lease is dropped [MAX_UNFINISHED_LEASE_AGE_MILLIS] after it. */
        val acceptedAtEpochMillis: Long,
    )

    private data class Enrolment(
        val record: PersistedRelayNode,
        val key: PublicKey,
    ) {
        val revoked: Boolean get() = record.revokedAtEpochMillis != null
    }

    private val mutex = Mutex()
    private val nodes = linkedMapOf<String, NodeSession>()

    // Enrolled node identities. An unreadable record (sealed under a rotated token, or damaged) starts
    // the pool with none: every node then enrols again, which needs the current pool token.
    private val enrolments = linkedMapOf<String, Enrolment>().apply {
        val restored = try {
            stateStore.loadNodes(poolId)
        } catch (_: Exception) {
            emptyList()
        }
        restored.forEach { persisted ->
            NodeSignatures.decodePublicKey(persisted.publicKey)?.let { key -> put(persisted.nodeId, Enrolment(persisted, key)) }
        }
    }

    /** Nonces of accepted connection requests, by node, until their timestamp leaves the skew window. */
    private val seenNonces = HashMap<String, Long>()

    // State written under another token (after a rotation), by a newer relay, or damaged on disk
    // cannot be recovered; the pool starts empty and the unreadable file is dropped, instead of
    // the pool refusing to exist until someone deletes the file by hand.
    private val restoredLeases = try {
        stateStore.load(poolId)
    } catch (_: Exception) {
        runCatching { stateStore.delete(poolId) }
        emptyList()
    }
    private val leases = linkedMapOf<String, LeaseRecord>().apply {
        restoredLeases
            .filterNot { persisted ->
                persisted.result != null &&
                    persisted.completedAtEpochMillis?.let { completedAt ->
                        completedAt <= nowEpochMillis() - RESUME_RETENTION_MILLIS
                    } == true
            }
            .forEach { persisted ->
                put(
                    persisted.envelope.leaseId,
                    LeaseRecord(
                        envelope = persisted.envelope,
                        originNodeId = persisted.originNodeId,
                        // A relay restart severs every worker socket. Unfinished work must return to
                        // pending rather than pretending the dead process still owns a claim.
                        workerNodeId = persisted.workerNodeId.takeIf { persisted.result != null },
                        expiresAtEpochMillis = null,
                        progress = persisted.progress.takeIf { persisted.result != null },
                        result = persisted.result,
                        completedAtEpochMillis = persisted.completedAtEpochMillis,
                        acceptedAtEpochMillis = persisted.acceptedAtEpochMillis ?: nowEpochMillis(),
                    ),
                )
            }
    }

    init {
        if (restoredLeases.size != leases.size) {
            persistLocked()
        }
    }

    /**
     * Registers [node] on [peer]. Throws [NodeIdInUseException] while another connection holds the
     * same node ID: node IDs are self-asserted, so letting a newcomer displace a live connection
     * would let any token holder take over a node, receive its replayed leases and cancel its work.
     * A node that reconnects is accepted once the relay has noticed its old connection close.
     */
    suspend fun register(node: ComputeNodeDescriptor, peer: RelayPeer): List<ComputeNodeDescriptor> {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        val online = mutex.withLock {
            // Revocation can land between a node's signed request being checked and its registration.
            if (enrolments[node.nodeId]?.revoked == true) throw NodeRevokedException(node.nodeId)
            val current = nodes[node.nodeId]
            if (current != null && current.peer !== peer) throw NodeIdInUseException(node.nodeId)
            nodes[node.nodeId] = NodeSession(node, peer)
            val message = if (current == null) {
                ComputeRelayServerMessage.NodeJoined(node)
            } else {
                ComputeRelayServerMessage.NodeUpdated(node)
            }
            nodes.values
                .filter { it.descriptor.nodeId != node.nodeId }
                .forEach { outbound += it.peer to message }
            nodes.values.map(NodeSession::descriptor)
        }
        sendAll(outbound)
        offerPendingLeases()
        return online
    }

    /**
     * Records [request]'s public key for its node ID. The caller has already checked the shared pool
     * token. Enrolling the same key again is a no-op apart from the display name; a different key
     * for an enrolled node ID, or any key for a revoked one, is refused.
     */
    suspend fun enrol(request: ComputeNodeEnrolmentRequest, ownerAuthority: Boolean = false): EnrolledComputeNode {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        val enrolled = mutex.withLock {
            if (request.protocolVersion != DISTRIBUTED_COMPUTE_PROTOCOL_VERSION) {
                throw NodeEnrolmentException(
                    ComputeNodeAuth.ERROR_BAD_REQUEST,
                    "Unsupported protocol version " + request.protocolVersion,
                )
            }
            if (request.algorithm != COMPUTE_NODE_SIGNATURE_ALGORITHM) {
                throw NodeEnrolmentException(ComputeNodeAuth.ERROR_BAD_REQUEST, "Unsupported signature algorithm")
            }
            if (!ComputeNodeAuth.isValidField(request.nodeId) || !ComputeNodeAuth.isValidField(request.displayName)) {
                throw NodeEnrolmentException(ComputeNodeAuth.ERROR_BAD_REQUEST, "Invalid node ID or display name")
            }
            val now = nowEpochMillis()
            if (!withinSkew(request.timestampEpochMillis, now)) {
                throw NodeEnrolmentException(ComputeNodeAuth.ERROR_STALE_REQUEST, "Enrolment request is too old or from the future")
            }
            val key = NodeSignatures.decodePublicKey(request.publicKey)
                ?: throw NodeEnrolmentException(ComputeNodeAuth.ERROR_BAD_REQUEST, "The public key must be a P-256 SubjectPublicKeyInfo")
            val message = runCatching {
                ComputeNodeAuth.enrolmentMessage(
                    poolId = poolId,
                    nodeId = request.nodeId,
                    displayName = request.displayName,
                    publicKey = request.publicKey,
                    timestampEpochMillis = request.timestampEpochMillis,
                )
            }.getOrNull() ?: throw NodeEnrolmentException(ComputeNodeAuth.ERROR_BAD_REQUEST, "Invalid enrolment request")
            if (!NodeSignatures.verify(key, message, request.signature)) {
                throw NodeEnrolmentException(ComputeNodeAuth.ERROR_BAD_SIGNATURE, "The enrolment signature does not match the key")
            }
            val existing = enrolments[request.nodeId]
            val record = when {
                existing == null -> {
                    if (enrolments.size >= MAX_ENROLLED_NODES_PER_POOL) {
                        throw NodeEnrolmentException(
                            ComputeNodeAuth.ERROR_POOL_FULL,
                            "This pool already has $MAX_ENROLLED_NODES_PER_POOL enrolled nodes",
                        )
                    }
                    PersistedRelayNode(
                        nodeId = request.nodeId,
                        displayName = request.displayName,
                        publicKey = request.publicKey,
                        enrolledAtEpochMillis = now,
                    )
                }
                // Only the pool owner can bring a revoked node ID back, and only with a freshly signed request.
                existing.revoked && ownerAuthority -> PersistedRelayNode(
                    nodeId = request.nodeId,
                    displayName = request.displayName,
                    publicKey = request.publicKey,
                    enrolledAtEpochMillis = now,
                )
                existing.revoked -> throw NodeEnrolmentException(
                    ComputeNodeAuth.ERROR_NODE_REVOKED,
                    "Node ID ${request.nodeId} was revoked in this pool; choose a new device ID",
                )
                existing.record.publicKey != request.publicKey -> throw NodeEnrolmentException(
                    ComputeNodeAuth.ERROR_NODE_ID_TAKEN,
                    "Node ID ${request.nodeId} is enrolled with another key; choose a new device ID",
                )
                else -> existing.record.copy(displayName = request.displayName)
            }
            enrolments[request.nodeId] = Enrolment(record, key)
            persistNodesLocked()
            val list = enrolledNodesLocked()
            nodes.values.forEach { outbound += it.peer to ComputeRelayServerMessage.EnrolledNodes(list) }
            record.toEnrolledNode()
        }
        sendAll(outbound)
        return enrolled
    }

    /**
     * Checks a signed connection request: an enrolled, unrevoked node, a timestamp within
     * [ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS], a nonce not seen before, and a valid signature by that
     * node's enrolled key. Only [NodeAuthentication.Authenticated] may go on to [register].
     */
    suspend fun authenticate(
        nodeId: String?,
        timestamp: String?,
        nonce: String?,
        signature: String?,
    ): NodeAuthentication {
        return mutex.withLock { authenticateLocked(nodeId, timestamp, nonce, signature) }
    }

    private fun authenticateLocked(
        nodeId: String?,
        timestamp: String?,
        nonce: String?,
        signature: String?,
    ): NodeAuthentication {
        if (nodeId == null || timestamp == null || nonce == null || signature == null) return NodeAuthentication.Malformed
        val timestampEpochMillis = timestamp.toLongOrNull() ?: return NodeAuthentication.Malformed
        if (!ComputeNodeAuth.isValidField(nodeId) || !ComputeNodeAuth.isValidNonce(nonce)) return NodeAuthentication.Malformed
        val now = nowEpochMillis()
        if (!withinSkew(timestampEpochMillis, now)) return NodeAuthentication.StaleRequest
        val enrolment = enrolments[nodeId] ?: return NodeAuthentication.NotEnrolled
        if (enrolment.revoked) return NodeAuthentication.Revoked
        val message = runCatching {
            ComputeNodeAuth.connectMessage(poolId, nodeId, timestampEpochMillis, nonce)
        }.getOrNull() ?: return NodeAuthentication.Malformed
        if (!NodeSignatures.verify(enrolment.key, message, signature)) return NodeAuthentication.BadSignature
        seenNonces.entries.removeAll { it.value < now }
        val nonceKey = nodeId + "\n" + nonce
        if (seenNonces.containsKey(nonceKey)) return NodeAuthentication.Replayed
        // Once the timestamp leaves the window the request is refused as stale, so the nonce can go.
        seenNonces[nonceKey] = timestampEpochMillis + ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS
        enrolments[nodeId] = enrolment.copy(record = enrolment.record.copy(lastSeenAtEpochMillis = now))
        persistNodesLocked()
        return NodeAuthentication.Authenticated
    }

    suspend fun enrolledNodes(): List<EnrolledComputeNode> = mutex.withLock { enrolledNodesLocked() }

    /**
     * [byNodeId] revokes [targetNodeId]: its key stops opening sessions, its node ID cannot enrol
     * again (unless the pool owner re-enrols it), its live session is closed, leases it published
     * are cancelled and leases it holds are requeued. Revocation is kept with the pool's enrolled
     * nodes. A node may revoke only itself; revoking another node needs [ownerAuthority], which the
     * relay grants only for the pool owner's token.
     */
    suspend fun revoke(byNodeId: String, targetNodeId: String, ownerAuthority: Boolean = false) {
        val outbound = mutableListOf<Pair<RelayPeer, ComputeRelayServerMessage>>()
        val reoffer = mutableListOf<String>()
        var revokedPeer: RelayPeer? = null
        mutex.withLock {
            val by = enrolments[byNodeId]
            check(by != null && !by.revoked) { "Only an enrolled node can revoke another" }
            check(targetNodeId == byNodeId || ownerAuthority) { "Revoking another node needs the pool owner's token" }
            val target = enrolments[targetNodeId]
                ?: throw IllegalArgumentException("No node $targetNodeId is enrolled in this pool")
            if (target.revoked) return
            enrolments[targetNodeId] = target.copy(
                record = target.record.copy(
                    revokedAtEpochMillis = nowEpochMillis(),
                    revokedByNodeId = byNodeId,
                ),
            )
            persistNodesLocked()

            val published = leases.values.filter { it.originNodeId == targetNodeId && it.result == null }
            if (published.isNotEmpty()) {
                mutatePersistedLocked { published.forEach { leases.remove(it.envelope.leaseId) } }
            }
            published.forEach { lease ->
                lease.workerNodeId?.let(nodes::get)?.let { worker ->
                    outbound += worker.peer to ComputeRelayServerMessage.LeaseCancelled(
                        leaseId = lease.envelope.leaseId,
                        reason = "The node that published this lease was revoked",
                    )
                }
            }
            leases.values
                .filter { it.workerNodeId == targetNodeId && it.result == null }
                .forEach { lease ->
                    leases[lease.envelope.leaseId] = lease.copy(workerNodeId = null, expiresAtEpochMillis = null)
                    nodes[lease.originNodeId]?.let { origin ->
                        outbound += origin.peer to ComputeRelayServerMessage.LeaseRequeued(
                            leaseId = lease.envelope.leaseId,
                            reason = "Worker was revoked",
                        )
                    }
                    reoffer += lease.envelope.leaseId
                }

            nodes.remove(targetNodeId)?.let { session ->
                revokedPeer = session.peer
                outbound += session.peer to ComputeRelayServerMessage.Error(
                    code = ComputeNodeAuth.ERROR_NODE_REVOKED,
                    message = "This device's identity was revoked by $byNodeId",
                )
                nodes.values.forEach { outbound += it.peer to ComputeRelayServerMessage.NodeLeft(targetNodeId) }
            }
            val list = enrolledNodesLocked()
            nodes.values.forEach { outbound += it.peer to ComputeRelayServerMessage.EnrolledNodes(list) }
        }
        sendAll(outbound)
        revokedPeer?.let { peer -> runCatching { peer.close("Node revoked") } }
        reoffer.forEach { offerLease(it) }
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

    suspend fun isCurrentPeer(nodeId: String, peer: RelayPeer): Boolean =
        mutex.withLock { nodes[nodeId]?.peer === peer }

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
            require(envelope.leaseDurationMillis <= MAX_LEASE_DURATION_MILLIS) {
                "leaseDurationMillis must be at most $MAX_LEASE_DURATION_MILLIS"
            }
            check(leases.values.count { it.originNodeId == originNodeId && it.result == null } < MAX_UNFINISHED_LEASES_PER_ORIGIN) {
                "This node already has $MAX_UNFINISHED_LEASES_PER_ORIGIN unfinished leases"
            }
            check(leases.size < MAX_LEASES_PER_POOL) { "This pool already holds $MAX_LEASES_PER_POOL leases" }
            mutatePersistedLocked {
                leases[envelope.leaseId] = LeaseRecord(
                    envelope = envelope,
                    originNodeId = originNodeId,
                    acceptedAtEpochMillis = nowEpochMillis(),
                )
            }
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
            mutatePersistedLocked {
                leases[leaseId] = lease.copy(
                    expiresAtEpochMillis = null,
                    result = result,
                    completedAtEpochMillis = nowEpochMillis(),
                )
            }
            // The lease stays attributed to the worker (its terminal record keeps workerNodeId), and
            // the worker's identity counts the outcome. workerNodeId is the authenticated session's.
            enrolments[workerNodeId]?.let { enrolment ->
                val record = enrolment.record
                enrolments[workerNodeId] = enrolment.copy(
                    record = if (result.status == TaskRunStatus.Completed) {
                        record.copy(leasesCompleted = record.leasesCompleted + 1)
                    } else {
                        record.copy(leasesFailed = record.leasesFailed + 1)
                    },
                )
                persistNodesLocked()
                val list = enrolledNodesLocked()
                nodes.values.forEach { outbound += it.peer to ComputeRelayServerMessage.EnrolledNodes(list) }
            }
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
            mutatePersistedLocked {
                leases.remove(leaseId)
            }
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
            val expiredTerminalIds = leases.values
                .filter { lease ->
                    lease.result != null &&
                        lease.completedAtEpochMillis?.let { completedAt ->
                            completedAt <= now - RESUME_RETENTION_MILLIS
                        } == true
                }
                .map { it.envelope.leaseId }
            // Unfinished work does not live forever either: nothing else bounds a lease no worker
            // can run, or one whose origin never comes back to cancel it.
            val abandoned = leases.values.filter { lease ->
                lease.result == null && lease.acceptedAtEpochMillis <= now - MAX_UNFINISHED_LEASE_AGE_MILLIS
            }
            if (expiredTerminalIds.isNotEmpty() || abandoned.isNotEmpty()) {
                mutatePersistedLocked {
                    expiredTerminalIds.forEach(leases::remove)
                    abandoned.forEach { leases.remove(it.envelope.leaseId) }
                }
            }
            abandoned.forEach { lease ->
                val cancelled = ComputeRelayServerMessage.LeaseCancelled(
                    leaseId = lease.envelope.leaseId,
                    reason = "Lease did not finish within 24 hours and was dropped",
                )
                nodes[lease.originNodeId]?.let { origin -> outbound += origin.peer to cancelled }
                lease.workerNodeId?.let(nodes::get)?.let { worker -> outbound += worker.peer to cancelled }
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

    /** The attribution a terminal lease keeps: the node that ran it, or null while it is unfinished. */
    suspend fun completedBy(leaseId: String): String? = mutex.withLock {
        leases[leaseId]?.takeIf { it.result != null }?.workerNodeId
    }

    /** A pool with enrolled nodes is never empty: dropping it would forget their keys and revocations. */
    suspend fun isEmpty(): Boolean = mutex.withLock { nodes.isEmpty() && leases.isEmpty() && enrolments.isEmpty() }

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

    private fun mutatePersistedLocked(mutation: () -> Unit) {
        val before = leases.toMap()
        try {
            mutation()
            persistLocked()
        } catch (failure: Throwable) {
            leases.clear()
            leases.putAll(before)
            throw failure
        }
    }

    private fun persistLocked() {
        val persisted = leases.values.map { lease ->
            val terminal = lease.result != null
            PersistedRelayLease(
                envelope = lease.envelope,
                originNodeId = lease.originNodeId,
                workerNodeId = lease.workerNodeId.takeIf { terminal },
                progress = lease.progress.takeIf { terminal },
                result = lease.result,
                completedAtEpochMillis = lease.completedAtEpochMillis,
                acceptedAtEpochMillis = lease.acceptedAtEpochMillis,
            )
        }
        // A full disk or a permissions problem must not end the node's session: the leases stay
        // live in memory and the next change tries to persist again.
        try {
            if (persisted.isEmpty()) stateStore.delete(poolId) else stateStore.save(poolId, persisted)
        } catch (_: java.io.IOException) {
        }
    }

    private fun withinSkew(timestampEpochMillis: Long, now: Long): Boolean =
        timestampEpochMillis >= now - ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS &&
            timestampEpochMillis <= now + ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS

    private fun enrolledNodesLocked(): List<EnrolledComputeNode> = enrolments.values.map { it.record.toEnrolledNode() }

    private fun PersistedRelayNode.toEnrolledNode(): EnrolledComputeNode = EnrolledComputeNode(
        nodeId = nodeId,
        displayName = displayName,
        publicKeyFingerprint = NodeSignatures.fingerprint(publicKey),
        enrolledAtEpochMillis = enrolledAtEpochMillis,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
        revokedAtEpochMillis = revokedAtEpochMillis,
        revokedByNodeId = revokedByNodeId,
        leasesCompleted = leasesCompleted,
        leasesFailed = leasesFailed,
    )

    private fun persistNodesLocked() {
        // As with leases, a storage failure must not end sessions; the next change tries again.
        try {
            stateStore.saveNodes(poolId, enrolments.values.map(Enrolment::record))
        } catch (_: java.io.IOException) {
        }
    }

    private suspend fun sendAll(outbound: List<Pair<RelayPeer, ComputeRelayServerMessage>>) {
        outbound.forEach { (peer, message) ->
            runCatching { peer.send(message) }
        }
    }

    override fun toString(): String = "RelayPool(" + poolId + ")"

    companion object {
        private const val RESUME_RETENTION_MILLIS: Long = 24L * 60L * 60L * 1_000L

        /** Longest heartbeat window a lease may ask for; workers renew it while they run. */
        const val MAX_LEASE_DURATION_MILLIS: Long = 10L * 60L * 1_000L

        /** Unfinished leases one origin may have in a pool at once. */
        const val MAX_UNFINISHED_LEASES_PER_ORIGIN: Int = 64

        /** Leases, unfinished and retained terminal ones, one pool may hold. */
        const val MAX_LEASES_PER_POOL: Int = 128

        /** An unfinished lease is cancelled and dropped this long after the relay accepted it. */
        const val MAX_UNFINISHED_LEASE_AGE_MILLIS: Long = 24L * 60L * 60L * 1_000L

        /** Node identities, revoked ones included, one pool may hold. */
        const val MAX_ENROLLED_NODES_PER_POOL: Int = 256
    }
}

/** [nodeId] was revoked in this pool and may not register. */
class NodeRevokedException(val nodeId: String) :
    IllegalStateException("Node ID $nodeId was revoked in this pool")

/** Another live connection already holds [nodeId] in this pool. */
class NodeIdInUseException(val nodeId: String) :
    IllegalStateException("Node ID $nodeId is already connected to this pool")
