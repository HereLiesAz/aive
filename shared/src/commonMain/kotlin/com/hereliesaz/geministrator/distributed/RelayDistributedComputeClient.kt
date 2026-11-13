package com.hereliesaz.geministrator.distributed

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** The lease id a relay message reports on, or null for messages that are not about one of our leases. */
internal fun leaseMessageId(message: ComputeRelayServerMessage): String? = when (message) {
    is ComputeRelayServerMessage.LeaseAccepted -> message.leaseId
    is ComputeRelayServerMessage.LeaseClaimed -> message.leaseId
    is ComputeRelayServerMessage.LeaseRequeued -> message.leaseId
    is ComputeRelayServerMessage.LeaseProgress -> message.leaseId
    is ComputeRelayServerMessage.LeaseCompleted -> message.leaseId
    is ComputeRelayServerMessage.LeaseCancelled -> message.leaseId
    else -> null
}

/**
 * One node's session with a compute relay.
 *
 * [bearerToken] is the shared pool token and is used only to enrol [signer]'s public key with the
 * pool, once (remembered in [enrolmentLedger]). Every connection is then opened with a short-lived
 * request signed by [signer], and the relay binds the session to the node ID that signed it.
 */
class RelayDistributedComputeClient(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val poolId: String,
    private val bearerToken: String,
    initialNode: ComputeNodeDescriptor,
    private val signer: ComputeNodeSigner,
    private val enrolmentLedger: ComputeNodeEnrolmentLedger = InMemoryComputeNodeEnrolmentLedger(),
    private val nowEpochMillis: () -> Long = ::currentEpochMillis,
    private val json: Json = defaultJson,
    /** The pool owner's token when the keeper set one; sent only with enrolment. */
    private val ownerToken: String? = null,
) {
    private val sessionMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var loopJob: Job? = null
    private var node = initialNode

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _onlineNodes = MutableStateFlow<Map<String, ComputeNodeDescriptor>>(emptyMap())
    val onlineNodes: StateFlow<Map<String, ComputeNodeDescriptor>> = _onlineNodes

    private val _leaseStates = MutableStateFlow<Map<String, DistributedLeaseState>>(emptyMap())
    val leaseStates: StateFlow<Map<String, DistributedLeaseState>> = _leaseStates

    private val _offers = MutableSharedFlow<DistributedTaskEnvelope>(extraBufferCapacity = 32)
    val offers: SharedFlow<DistributedTaskEnvelope> = _offers

    private val _errors = MutableSharedFlow<ComputeRelayServerMessage.Error>(extraBufferCapacity = 16)
    val errors: SharedFlow<ComputeRelayServerMessage.Error> = _errors

    private val _enrolledNodes = MutableStateFlow<List<EnrolledComputeNode>>(emptyList())

    /** The pool's enrolled nodes as the relay last reported them, revoked ones included. */
    val enrolledNodes: StateFlow<List<EnrolledComputeNode>> = _enrolledNodes

    private val _revoked = MutableStateFlow(false)

    /** True once the relay has said this node's identity is revoked; the client then stops reconnecting. */
    val revoked: StateFlow<Boolean> = _revoked

    private val enroller = ComputeNodeEnroller(
        httpClient = httpClient,
        relayBaseUrl = relayBaseUrl,
        poolId = poolId,
        poolToken = bearerToken,
        signer = signer,
        nowEpochMillis = nowEpochMillis,
        json = json,
        ownerToken = ownerToken,
    )

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var backoffMillis = 1_000L
            while (isActive) {
                val connectedAt = TimeSource.Monotonic.markNow()
                try {
                    connectOnce(this)
                } catch (_: Throwable) {
                    _connected.value = false
                } finally {
                    sessionMutex.withLock {
                        session?.close()
                        session = null
                    }
                    _connected.value = false
                }
                // A revoked identity cannot come back by retrying; the keeper has to set it up afresh.
                if (_revoked.value) break
                // Only a connection that held for a while resets the backoff: a relay that accepts the
                // socket and then drops it must not be hammered once a second forever.
                backoffMillis = if (connectedAt.elapsedNow() >= STABLE_CONNECTION) 1_000L
                else (backoffMillis * 2).coerceAtMost(15_000L)
                delay(backoffMillis + Random.nextLong(backoffMillis / 4 + 1))
            }
        }
    }

    suspend fun stop() {
        loopJob?.cancel()
        loopJob = null
        sessionMutex.withLock {
            session?.close()
            session = null
        }
        _connected.value = false
    }

    suspend fun updateNode(updated: ComputeNodeDescriptor) {
        require(updated.nodeId == node.nodeId) { "nodeId cannot change while connected" }
        node = updated
        if (_connected.value) {
            send(ComputeRelayClientMessage.UpdateNode(updated))
        }
    }

    suspend fun publish(envelope: DistributedTaskEnvelope) {
        require(envelope.originNodeId == node.nodeId) { "lease origin must be this node" }
        updateLease(
            DistributedLeaseState(
                leaseId = envelope.leaseId,
                phase = DistributedLeasePhase.Publishing,
            ),
        )
        send(ComputeRelayClientMessage.PublishLease(envelope))
    }

    suspend fun claim(leaseId: String) {
        send(ComputeRelayClientMessage.ClaimLease(leaseId))
    }

    suspend fun heartbeatLease(leaseId: String) {
        send(ComputeRelayClientMessage.LeaseHeartbeat(leaseId))
    }

    suspend fun progress(leaseId: String, progress: DistributedExecutionProgress) {
        send(ComputeRelayClientMessage.LeaseProgress(leaseId, progress))
    }

    suspend fun complete(leaseId: String, result: DistributedExecutionResult) {
        send(ComputeRelayClientMessage.CompleteLease(leaseId, result))
    }

    suspend fun cancel(leaseId: String, reason: String? = null) {
        send(ComputeRelayClientMessage.CancelLease(leaseId, reason))
    }

    suspend fun requestEnrolledNodes() {
        send(ComputeRelayClientMessage.ListEnrolledNodes)
    }

    /**
     * Revokes another node of the pool. The relay accepts this only with the pool owner's token;
     * without it a node can revoke only itself.
     */
    suspend fun revokeNode(nodeId: String, ownerToken: String? = null) {
        require(nodeId != node.nodeId) { "A node cannot revoke itself" }
        send(ComputeRelayClientMessage.RevokeNode(nodeId, ownerToken))
    }

    /** Enrols this node's key unless the ledger says it already is. Uses the pool token. */
    internal suspend fun ensureEnrolled() {
        val fingerprint = ComputeNodeEnrolmentLedger.fingerprint(relayBaseUrl, poolId, node.nodeId, signer.publicKey)
        if (enrolmentLedger.isEnrolled(fingerprint)) return
        try {
            enroller.enrol(node.nodeId, node.displayName)
        } catch (refused: ComputeNodeEnrolmentException) {
            if (refused.code == ComputeNodeAuth.ERROR_NODE_REVOKED) _revoked.value = true
            _errors.emit(ComputeRelayServerMessage.Error(code = refused.code, message = refused.message ?: refused.code))
            throw refused
        }
        enrolmentLedger.recordEnrolled(fingerprint)
    }

    private suspend fun connectOnce(scope: CoroutineScope) {
        require(relayBaseUrl.isNotBlank()) { "relayBaseUrl must not be blank" }
        require(poolId.isNotBlank()) { "poolId must not be blank" }
        require(bearerToken.isNotBlank()) { "bearerToken must not be blank" }
        require(isSecureRelayUrl(relayBaseUrl)) {
            "The relay is only reached over wss:// or https:// (plain ws:// and http:// are allowed for localhost only)"
        }

        ensureEnrolled()
        val signedHeaders = ComputeNodeAuth.signedConnectHeaders(
            signer = signer,
            poolId = poolId,
            nodeId = node.nodeId,
            timestampEpochMillis = nowEpochMillis(),
        )
        val socket = httpClient.webSocketSession(
            urlString = relayBaseUrl.trimEnd('/') + "/v1/compute/" + poolId,
        ) {
            signedHeaders.forEach { (name, value) -> header(name, value) }
        }
        sessionMutex.withLock { session = socket }
        socket.sendSerialized(
            ComputeRelayClientMessage.Register(
                protocolVersion = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
                node = node,
            ),
        )

        val heartbeat = scope.launch {
            while (isActive) {
                delay(15_000)
                runCatching { send(ComputeRelayClientMessage.NodeHeartbeat) }
            }
        }
        try {
            for (frame in socket.incoming) {
                if (frame !is Frame.Text) continue
                handle(json.decodeFromString<ComputeRelayServerMessage>(frame.readText()))
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun handle(message: ComputeRelayServerMessage) {
        // Lease messages are accepted only for leases this client published; a relay cannot
        // introduce results for ids it was never given.
        leaseMessageId(message)?.let { if (it !in _leaseStates.value) return }
        when (message) {
            is ComputeRelayServerMessage.Registered -> {
                require(message.protocolVersion == DISTRIBUTED_COMPUTE_PROTOCOL_VERSION) {
                    "Relay protocol " + message.protocolVersion +
                        " is incompatible with client " + DISTRIBUTED_COMPUTE_PROTOCOL_VERSION
                }
                _onlineNodes.value = message.onlineNodes.associateBy(ComputeNodeDescriptor::nodeId)
                _connected.value = true
            }
            is ComputeRelayServerMessage.NodeJoined -> {
                _onlineNodes.value = _onlineNodes.value + (message.node.nodeId to message.node)
            }
            is ComputeRelayServerMessage.NodeUpdated -> {
                _onlineNodes.value = _onlineNodes.value + (message.node.nodeId to message.node)
            }
            is ComputeRelayServerMessage.NodeLeft -> {
                _onlineNodes.value = _onlineNodes.value - message.nodeId
            }
            is ComputeRelayServerMessage.LeaseAccepted -> {
                updateLease(DistributedLeaseState(message.leaseId, DistributedLeasePhase.Pending))
            }
            is ComputeRelayServerMessage.LeaseOffered -> {
                _offers.emit(message.envelope)
            }
            is ComputeRelayServerMessage.LeaseClaimed -> {
                val previous = _leaseStates.value[message.leaseId]
                updateLease(
                    DistributedLeaseState(
                        leaseId = message.leaseId,
                        phase = DistributedLeasePhase.Claimed,
                        workerNodeId = message.workerNodeId,
                        expiresAtEpochMillis = message.expiresAtEpochMillis,
                        progress = previous?.progress,
                        progressMessage = previous?.progressMessage,
                    ),
                )
            }
            is ComputeRelayServerMessage.LeaseRequeued -> {
                updateLease(
                    DistributedLeaseState(
                        leaseId = message.leaseId,
                        phase = DistributedLeasePhase.Pending,
                        progressMessage = message.reason,
                    ),
                )
            }
            is ComputeRelayServerMessage.LeaseProgress -> {
                val phase = when (message.progress.status) {
                    com.hereliesaz.geministrator.domain.TaskRunStatus.Verifying -> DistributedLeasePhase.Verifying
                    else -> DistributedLeasePhase.Running
                }
                val previous = _leaseStates.value[message.leaseId]
                updateLease(
                    DistributedLeaseState(
                        leaseId = message.leaseId,
                        phase = phase,
                        workerNodeId = message.workerNodeId,
                        expiresAtEpochMillis = previous?.expiresAtEpochMillis,
                        progress = message.progress.progress,
                        progressMessage = message.progress.message,
                    ),
                )
            }
            is ComputeRelayServerMessage.LeaseCompleted -> {
                val phase = if (message.result.status == com.hereliesaz.geministrator.domain.TaskRunStatus.Completed) {
                    DistributedLeasePhase.Completed
                } else {
                    DistributedLeasePhase.Failed
                }
                updateLease(
                    DistributedLeaseState(
                        leaseId = message.leaseId,
                        phase = phase,
                        workerNodeId = message.workerNodeId,
                        progress = if (phase == DistributedLeasePhase.Completed) 1f else null,
                        progressMessage = message.result.progressMessage,
                        artifacts = message.result.artifacts,
                        failureMessage = message.result.failureMessage,
                    ),
                )
            }
            is ComputeRelayServerMessage.LeaseCancelled -> {
                val previous = _leaseStates.value[message.leaseId]
                updateLease(
                    DistributedLeaseState(
                        leaseId = message.leaseId,
                        phase = DistributedLeasePhase.Cancelled,
                        workerNodeId = previous?.workerNodeId,
                        progressMessage = message.reason,
                    ),
                )
            }
            is ComputeRelayServerMessage.EnrolledNodes -> {
                _enrolledNodes.value = message.nodes
            }
            is ComputeRelayServerMessage.Error -> {
                when (message.code) {
                    // The relay lost its record (for example it restarted without saved state): enrol again.
                    ComputeNodeAuth.ERROR_NODE_NOT_ENROLLED -> enrolmentLedger.forget()
                    ComputeNodeAuth.ERROR_NODE_REVOKED -> _revoked.value = true
                    else -> Unit
                }
                _errors.emit(message)
            }
        }
    }

    private suspend fun send(message: ComputeRelayClientMessage) {
        val socket = sessionMutex.withLock { session }
            ?: error("Distributed compute relay is not connected")
        socket.sendSerialized(message)
    }

    private suspend fun DefaultClientWebSocketSession.sendSerialized(message: ComputeRelayClientMessage) {
        send(Frame.Text(json.encodeToString(ComputeRelayClientMessage.serializer(), message)))
    }

    private fun updateLease(state: DistributedLeaseState) {
        _leaseStates.value = _leaseStates.value + (state.leaseId to state)
    }

    companion object {
        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        private val STABLE_CONNECTION = 30.seconds
    }
}

interface DistributedComputeGateway {
    val localNodeId: String
    fun isAvailable(): Boolean
    fun leaseState(leaseId: String): DistributedLeaseState?
    suspend fun submit(envelope: DistributedTaskEnvelope)
    suspend fun cancel(leaseId: String, reason: String? = null)
}

class RelayDistributedComputeGateway(
    private val client: RelayDistributedComputeClient,
    override val localNodeId: String,
) : DistributedComputeGateway {
    override fun isAvailable(): Boolean = client.connected.value

    override fun leaseState(leaseId: String): DistributedLeaseState? = client.leaseStates.value[leaseId]

    override suspend fun submit(envelope: DistributedTaskEnvelope) = client.publish(envelope)

    override suspend fun cancel(leaseId: String, reason: String?) = client.cancel(leaseId, reason)
}

@OptIn(ExperimentalTime::class)
private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** True for wss/https, or ws/http to a loopback host, where the bearer token cannot cross a network in clear. */
internal fun isSecureRelayUrl(url: String): Boolean {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme == "wss" || scheme == "https") return true
    if (scheme != "ws" && scheme != "http") return false
    val authority = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]")
}
