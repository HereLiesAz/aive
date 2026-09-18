package com.hereliesaz.geministrator.distributed

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
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

class RelayDistributedComputeClient(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val poolId: String,
    private val bearerToken: String,
    initialNode: ComputeNodeDescriptor,
    private val json: Json = defaultJson,
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

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var backoffMillis = 1_000L
            while (isActive) {
                try {
                    connectOnce(this)
                    backoffMillis = 1_000L
                } catch (_: Throwable) {
                    _connected.value = false
                } finally {
                    sessionMutex.withLock {
                        session?.close()
                        session = null
                    }
                    _connected.value = false
                }
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(15_000L)
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

    private suspend fun connectOnce(scope: CoroutineScope) {
        require(relayBaseUrl.isNotBlank()) { "relayBaseUrl must not be blank" }
        require(poolId.isNotBlank()) { "poolId must not be blank" }
        require(bearerToken.isNotBlank()) { "bearerToken must not be blank" }

        val socket = httpClient.webSocketSession(
            urlString = relayBaseUrl.trimEnd('/') + "/v1/compute/" + poolId,
        ) {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
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
            is ComputeRelayServerMessage.Error -> _errors.emit(message)
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
