package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeAuth
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentRequest
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentResponse
import com.hereliesaz.geministrator.distributed.ComputeRelayClientMessage
import com.hereliesaz.geministrator.distributed.ComputeRelayHttpError
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.DISTRIBUTED_COMPUTE_PROTOCOL_VERSION
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val relayJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

class ComputeRelayHub(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val stateStore: RelayStateStore = NoopRelayStateStore,
) {
    private val pools = ConcurrentHashMap<String, RelayPool>()

    // When each pool was last handed out. A pool is empty from creation until its connection
    // registers, so emptiness alone must not let the sweep remove it in that window: the connecting
    // node would register on an orphan and the next connection would build a second pool.
    private val handedOutAt = ConcurrentHashMap<String, Long>()

    fun pool(poolId: String): RelayPool {
        handedOutAt[poolId] = nowEpochMillis()
        return pools.computeIfAbsent(poolId) { RelayPool(poolId, nowEpochMillis, stateStore) }
    }

    suspend fun sweepExpired() {
        pools.values.forEach { it.sweepExpired() }
        val now = nowEpochMillis()
        val candidates = pools.entries
            .filter { now - (handedOutAt[it.key] ?: 0L) >= POOL_REMOVAL_GRACE_MILLIS && it.value.isEmpty() }
        // Removal is conditional on the same pool instance, so a pool replaced or re-handed-out
        // meanwhile is never dropped by a stale decision.
        candidates.forEach { (id, pool) ->
            if (now - (handedOutAt[id] ?: 0L) >= POOL_REMOVAL_GRACE_MILLIS && pools.remove(id, pool)) {
                handedOutAt.remove(id)
            }
        }
    }

    private companion object {
        const val POOL_REMOVAL_GRACE_MILLIS = 60_000L
    }
}

private class WebSocketRelayPeer(
    private val sendText: suspend (String) -> Unit,
    private val closeSession: suspend (String) -> Unit,
) : RelayPeer {
    private val sendMutex = Mutex()

    override suspend fun send(message: ComputeRelayServerMessage) {
        val payload = relayJson.encodeToString(ComputeRelayServerMessage.serializer(), message)
        sendMutex.withLock { sendText(payload) }
    }

    override suspend fun close(reason: String) = closeSession(reason)
}

private suspend fun ApplicationCall.respondRelayError(status: HttpStatusCode, code: String, message: String) {
    respondText(
        relayJson.encodeToString(ComputeRelayHttpError.serializer(), ComputeRelayHttpError(code, message)),
        ContentType.Application.Json,
        status,
    )
}

private fun enrolmentStatus(code: String): HttpStatusCode = when (code) {
    ComputeNodeAuth.ERROR_STALE_REQUEST, ComputeNodeAuth.ERROR_BAD_SIGNATURE -> HttpStatusCode.Unauthorized
    ComputeNodeAuth.ERROR_NODE_REVOKED -> HttpStatusCode.Forbidden
    ComputeNodeAuth.ERROR_NODE_ID_TAKEN, ComputeNodeAuth.ERROR_POOL_FULL -> HttpStatusCode.Conflict
    else -> HttpStatusCode.BadRequest
}

private const val MAX_ENROLMENT_BODY_BYTES: Long = 64L * 1024L

/** Compares the bearer token in constant time, so response timing does not leak the token. */
internal fun isAuthorized(authorizationHeader: String?, relayToken: String): Boolean {
    val presented = (authorizationHeader ?: "").toByteArray(Charsets.UTF_8)
    val expected = "Bearer $relayToken".toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(presented, expected)
}

fun Application.computeRelayModule(
    relayToken: String,
    hub: ComputeRelayHub = ComputeRelayHub(),
    /** The pool owner's token. Without one, no node may revoke another or re-enrol a revoked ID. */
    ownerToken: String? = null,
) {
    require(relayToken.isNotBlank()) { "relayToken must not be blank" }

    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 45.seconds
        maxFrameSize = 8L * 1024L * 1024L
        masking = false
    }

    val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    monitor.subscribe(ApplicationStopped) { sweepScope.cancel() }
    sweepScope.launch {
        while (isActive) {
            delay(5_000)
            hub.sweepExpired()
        }
    }

    routing {
        // Enrolment is the only request the shared pool token authorizes: it records a node's public
        // key. Sessions are then opened with requests signed by that key (ComputeNodeAuth).
        post("/v1/compute/{poolId}/enrol") {
            if (!isAuthorized(call.request.headers[HttpHeaders.Authorization], relayToken)) {
                call.respondRelayError(HttpStatusCode.Unauthorized, ComputeNodeAuth.ERROR_UNAUTHORIZED, "Unauthorized")
                return@post
            }
            val poolId = call.parameters["poolId"]?.trim().orEmpty()
            if (!ComputeNodeAuth.isValidField(poolId)) {
                call.respondRelayError(HttpStatusCode.BadRequest, ComputeNodeAuth.ERROR_BAD_REQUEST, "Missing compute pool")
                return@post
            }
            if ((call.request.contentLength() ?: 0L) > MAX_ENROLMENT_BODY_BYTES) {
                call.respondRelayError(HttpStatusCode.PayloadTooLarge, ComputeNodeAuth.ERROR_BAD_REQUEST, "Enrolment request is too large")
                return@post
            }
            val body = call.receiveText()
            val request = runCatching {
                relayJson.decodeFromString(ComputeNodeEnrolmentRequest.serializer(), body)
            }.getOrNull()
            if (request == null || body.length > MAX_ENROLMENT_BODY_BYTES) {
                call.respondRelayError(HttpStatusCode.BadRequest, ComputeNodeAuth.ERROR_BAD_REQUEST, "Invalid enrolment request")
                return@post
            }
            val ownerAuthority = ownerToken != null &&
                isAuthorized(call.request.headers[ComputeNodeAuth.HEADER_OWNER_TOKEN]?.let { "Bearer $it" }, ownerToken)
            try {
                val enrolled = hub.pool(poolId).enrol(request, ownerAuthority)
                call.respondText(
                    relayJson.encodeToString(
                        ComputeNodeEnrolmentResponse.serializer(),
                        ComputeNodeEnrolmentResponse(enrolled.nodeId, enrolled.enrolledAtEpochMillis),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            } catch (refused: NodeEnrolmentException) {
                call.respondRelayError(enrolmentStatus(refused.code), refused.code, refused.message ?: refused.code)
            }
        }

        webSocket("/v1/compute/{poolId}") {
            val poolId = call.parameters["poolId"]?.trim().orEmpty()
            if (!ComputeNodeAuth.isValidField(poolId)) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing compute pool"))
                return@webSocket
            }
            val headers = call.request.headers
            val nodeIdHeader = headers[ComputeNodeAuth.HEADER_NODE_ID]
            val timestampHeader = headers[ComputeNodeAuth.HEADER_TIMESTAMP]
            val nonceHeader = headers[ComputeNodeAuth.HEADER_NONCE]
            val signatureHeader = headers[ComputeNodeAuth.HEADER_SIGNATURE]
            // Nothing unsigned reaches a pool: the pool token alone no longer opens a session.
            if (nodeIdHeader == null || timestampHeader == null || nonceHeader == null || signatureHeader == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }
            val pool = hub.pool(poolId)
            val authentication = pool.authenticate(nodeIdHeader, timestampHeader, nonceHeader, signatureHeader)
            if (authentication != NodeAuthentication.Authenticated) {
                // Not-enrolled and revoked are told to the client so it can enrol again or stop.
                if (authentication == NodeAuthentication.NotEnrolled || authentication == NodeAuthentication.Revoked) {
                    send(
                        relayJson.encodeToString(
                            ComputeRelayServerMessage.serializer(),
                            ComputeRelayServerMessage.Error(
                                code = authentication.errorCode,
                                message = if (authentication == NodeAuthentication.Revoked) {
                                    "This device's identity was revoked in this pool"
                                } else {
                                    "This device is not enrolled in this pool"
                                },
                            ),
                        ),
                    )
                }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            val first = incoming.receive()
            if (first !is Frame.Text) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "First frame must register node"))
                return@webSocket
            }
            val registration = runCatching {
                relayJson.decodeFromString<ComputeRelayClientMessage>(first.readText())
            }.getOrNull() as? ComputeRelayClientMessage.Register
            if (registration == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "First message must be register"))
                return@webSocket
            }
            if (registration.protocolVersion != DISTRIBUTED_COMPUTE_PROTOCOL_VERSION) {
                send(
                    relayJson.encodeToString(
                        ComputeRelayServerMessage.serializer(),
                        ComputeRelayServerMessage.Error(
                            code = "protocol-version",
                            message = "Unsupported protocol version " + registration.protocolVersion,
                        ),
                    ),
                )
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Protocol version mismatch"))
                return@webSocket
            }

            val nodeId = registration.node.nodeId
            // The session belongs to the node that signed the request, not to whatever ID it registers.
            if (nodeId != nodeIdHeader) {
                send(
                    relayJson.encodeToString(
                        ComputeRelayServerMessage.serializer(),
                        ComputeRelayServerMessage.Error(
                            code = ComputeNodeAuth.ERROR_UNAUTHORIZED,
                            message = "The registered node ID does not match the signing node",
                        ),
                    ),
                )
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Node ID mismatch"))
                return@webSocket
            }
            val peer = WebSocketRelayPeer(
                sendText = { payload -> send(payload) },
                closeSession = { reason -> close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)) },
            )
            val online = try {
                pool.register(registration.node, peer)
            } catch (inUse: NodeIdInUseException) {
                peer.send(ComputeRelayServerMessage.Error(code = "node-id-in-use", message = inUse.message ?: "Node ID in use"))
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Node ID in use"))
                return@webSocket
            } catch (revoked: NodeRevokedException) {
                peer.send(
                    ComputeRelayServerMessage.Error(
                        code = ComputeNodeAuth.ERROR_NODE_REVOKED,
                        message = revoked.message ?: "Node revoked",
                    ),
                )
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Node revoked"))
                return@webSocket
            }
            try {
                peer.send(
                    ComputeRelayServerMessage.Registered(
                        protocolVersion = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
                        nodeId = nodeId,
                        onlineNodes = online,
                    ),
                )
                peer.send(ComputeRelayServerMessage.EnrolledNodes(pool.enrolledNodes()))
                pool.replayOriginLeases(nodeId, peer)

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = runCatching {
                        relayJson.decodeFromString<ComputeRelayClientMessage>(frame.readText())
                    }.getOrElse { failure ->
                        peer.send(
                            ComputeRelayServerMessage.Error(
                                code = "invalid-message",
                                message = failure.message ?: "Unable to decode message",
                            ),
                        )
                        continue
                    }
                    if (!pool.isCurrentPeer(nodeId, peer)) {
                        peer.send(
                            ComputeRelayServerMessage.Error(
                                code = "displaced",
                                message = "This connection has been replaced by a newer session for the same node",
                            ),
                        )
                        close(CloseReason(CloseReason.Codes.NORMAL, "Node session replaced"))
                        break
                    }
                    try {
                        when (message) {
                            is ComputeRelayClientMessage.Register -> {
                                peer.send(
                                    ComputeRelayServerMessage.Error(
                                        code = "already-registered",
                                        message = "This connection is already registered",
                                    ),
                                )
                            }
                            is ComputeRelayClientMessage.UpdateNode -> pool.updateNode(nodeId, message.node)
                            is ComputeRelayClientMessage.PublishLease -> pool.publish(nodeId, message.envelope)
                            is ComputeRelayClientMessage.ClaimLease -> pool.claim(nodeId, message.leaseId)
                            is ComputeRelayClientMessage.LeaseHeartbeat -> pool.heartbeat(nodeId, message.leaseId)
                            is ComputeRelayClientMessage.LeaseProgress -> {
                                pool.progress(nodeId, message.leaseId, message.progress)
                            }
                            is ComputeRelayClientMessage.CompleteLease -> {
                                pool.complete(nodeId, message.leaseId, message.result)
                            }
                            is ComputeRelayClientMessage.CancelLease -> {
                                pool.cancel(nodeId, message.leaseId, message.reason)
                            }
                            ComputeRelayClientMessage.NodeHeartbeat -> Unit
                            ComputeRelayClientMessage.ListEnrolledNodes -> {
                                peer.send(ComputeRelayServerMessage.EnrolledNodes(pool.enrolledNodes()))
                            }
                            is ComputeRelayClientMessage.RevokeNode -> {
                                val ownerAuthority = ownerToken != null && message.ownerToken != null &&
                                    isAuthorized("Bearer ${message.ownerToken}", ownerToken)
                                pool.revoke(nodeId, message.nodeId, ownerAuthority)
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        peer.send(
                            ComputeRelayServerMessage.Error(
                                code = "bad-request",
                                message = e.message ?: "Invalid message content",
                            ),
                        )
                    } catch (e: IllegalStateException) {
                        peer.send(
                            ComputeRelayServerMessage.Error(
                                code = "bad-state",
                                message = e.message ?: "Unexpected relay state",
                            ),
                        )
                    }
                }
            } finally {
                pool.unregister(nodeId, peer)
            }
        }
    }
}

fun main() {
    val token = System.getenv("AIVE_RELAY_TOKEN")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("HAIVE_RELAY_TOKEN")
            ?.takeIf(String::isNotBlank)
        ?: error("AIVE_RELAY_TOKEN is required (HAIVE_RELAY_TOKEN is accepted for compatibility)")
    val port = (
        System.getenv("PORT")
            ?: System.getenv("AIVE_RELAY_PORT")
            ?: System.getenv("HAIVE_RELAY_PORT")
            ?: "8080"
        ).toInt()
    // Binds all interfaces by default because the relay normally runs in a container behind a
    // TLS-terminating proxy; set AIVE_RELAY_HOST=127.0.0.1 to keep it off the network. The relay
    // speaks plain ws, so never expose it directly: terminate TLS in front of it.
    val host = System.getenv("AIVE_RELAY_HOST")?.takeIf(String::isNotBlank) ?: "0.0.0.0"
    val stateDirectory = System.getenv("AIVE_RELAY_STATE_DIR")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("HAIVE_RELAY_STATE_DIR")
            ?.takeIf(String::isNotBlank)
    val stateStore = stateDirectory
        ?.let { EncryptedFileRelayStateStore(Path.of(it), token) }
        ?: NoopRelayStateStore
    val hub = ComputeRelayHub(stateStore = stateStore)
    val ownerToken = System.getenv("AIVE_RELAY_OWNER_TOKEN")?.takeIf(String::isNotBlank)
    embeddedServer(
        factory = CIO,
        host = host,
        port = port,
    ) {
        computeRelayModule(token, hub, ownerToken)
    }.start(wait = true)
}
