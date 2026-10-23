package com.hereliesaz.geministrator.relay

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The relay's WebSocket route itself: authentication and the register-first handshake. */
class ComputeRelayRouteTest {
    @Test
    fun aWrongBearerTokenIsClosedAsAPolicyViolation() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/v1/compute/pool-a", request = { header(HttpHeaders.Authorization, "Bearer wrong") }) {
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
    }

    @Test
    fun noAuthorizationHeaderIsRefusedToo() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/v1/compute/pool-a") {
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
    }

    @Test
    fun anAuthorizedSocketMustRegisterBeforeAnythingElse() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/v1/compute/pool-a", request = { header(HttpHeaders.Authorization, "Bearer secret-token") }) {
                send(Frame.Text("{}"))
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
            }
        }
    }
}
