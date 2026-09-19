package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeRelayClientMessage
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.DISTRIBUTED_COMPUTE_PROTOCOL_VERSION
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {
    private val pools = ConcurrentHashMap<String, RelayPool>()

    fun pool(poolId: String): RelayPool = pools.computeIfAbsent(poolId) {
        RelayPool(poolId, nowEpochMillis)
    }

    suspend fun sweepExpired() {
        pools.values.forEach { it.sweepExpired() }
    }
}

private class WebSocketRelayPeer(
    private val sendText: suspend (String) -> Unit,
) : RelayPeer {
    private val sendMutex = Mutex()

    override suspend fun send(message: ComputeRelayServerMessage) {
        val payload = relayJson.encodeToString(ComputeRelayServerMessage.serializer(), message)
        sendMutex.withLock { sendText(payload) }
    }
}

fun Application.computeRelayModule(
    relayToken: String,
    hub: ComputeRelayHub = ComputeRelayHub(),
) {
    require(relayToken.isNotBlank()) { "relayToken must not be blank" }

    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 45.seconds
        maxFrameSize = 8L * 1024L * 1024L
        masking = false
    }

    val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    sweepScope.launch {
        while (isActive) {
            delay(5_000)
            hub.sweepExpired()
        }
    }

    routing {
        webSocket("/v1/compute/{poolId}") {
            val authorization = call.request.headers[HttpHeaders.Authorization]
            if (authorization != "Bearer $relayToken") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }
            val poolId = call.parameters["poolId"]?.trim().orEmpty()
            if (poolId.isBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing compute pool"))
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
            val pool = hub.pool(poolId)
            val peer = WebSocketRelayPeer { payload -> send(payload) }
            val online = pool.register(registration.node, peer)
            peer.send(
                ComputeRelayServerMessage.Registered(
                    protocolVersion = DISTRIBUTED_COMPUTE_PROTOCOL_VERSION,
                    nodeId = nodeId,
                    onlineNodes = online,
                ),
            )

            try {
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
                    }
                }
            } finally {
                pool.unregister(nodeId)
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
    embeddedServer(
        factory = CIO,
        host = "0.0.0.0",
        port = port,
    ) {
        computeRelayModule(token)
    }.start(wait = true)
}
