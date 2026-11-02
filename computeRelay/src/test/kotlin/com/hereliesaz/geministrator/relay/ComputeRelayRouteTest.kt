package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeAuth
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentRequest
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentResponse
import com.hereliesaz.geministrator.distributed.ComputeRelayClientMessage
import com.hereliesaz.geministrator.distributed.ComputeRelayHttpError
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.RelayDistributedComputeClient
import com.hereliesaz.geministrator.domain.ComputePlatform
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The relay's routes: pool-token enrolment, signed sessions, and the register-first handshake. */
class ComputeRelayRouteTest {
    private val json = RelayDistributedComputeClient.defaultJson

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
    fun thePoolTokenAloneNoLongerOpensASession() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/v1/compute/pool-a", request = { header(HttpHeaders.Authorization, "Bearer secret-token") }) {
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
    }

    @Test
    fun enrolmentNeedsThePoolToken() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val response = client.post("/v1/compute/pool-a/enrol") {
                header(HttpHeaders.Authorization, "Bearer wrong")
                setBody(enrolmentBody(TestSigner(), "desk"))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val error = json.decodeFromString(ComputeRelayHttpError.serializer(), response.bodyAsText())
            assertEquals(ComputeNodeAuth.ERROR_UNAUTHORIZED, error.code)
        }
    }

    @Test
    fun anEnrolledNodeOpensASessionWithASignedRequest() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            val desk = TestSigner()
            val enrolled = client.enrol(desk, "desk")
            assertEquals(HttpStatusCode.OK, enrolled.first)
            assertEquals("desk", json.decodeFromString(ComputeNodeEnrolmentResponse.serializer(), enrolled.second).nodeId)

            client.webSocket("/v1/compute/pool-a", request = { signedAs(desk, "desk") }) {
                send(Frame.Text(json.encodeToString(ComputeRelayClientMessage.serializer(), register("desk"))))
                assertIs<ComputeRelayServerMessage.Registered>(decode(incoming.receive()))
                val listed = assertIs<ComputeRelayServerMessage.EnrolledNodes>(decode(incoming.receive()))
                assertEquals(listOf("desk"), listed.nodes.map { it.nodeId })
            }
        }
    }

    @Test
    fun anUnenrolledNodeIsToldSoAndClosed() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            client.webSocket("/v1/compute/pool-a", request = { signedAs(TestSigner(), "stranger") }) {
                val error = assertIs<ComputeRelayServerMessage.Error>(decode(incoming.receive()))
                assertEquals(ComputeNodeAuth.ERROR_NODE_NOT_ENROLLED, error.code)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
    }

    @Test
    fun aSessionCannotRegisterAsAnotherNode() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            val desk = TestSigner()
            client.enrol(desk, "desk")
            client.enrol(TestSigner(), "phone")
            client.webSocket("/v1/compute/pool-a", request = { signedAs(desk, "desk") }) {
                send(Frame.Text(json.encodeToString(ComputeRelayClientMessage.serializer(), register("phone"))))
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
            }
        }
    }

    @Test
    fun anAuthorizedSocketMustRegisterBeforeAnythingElse() = runBlocking {
        testApplication {
            application { computeRelayModule("secret-token") }
            val client = createClient { install(ClientWebSockets) }
            val desk = TestSigner()
            client.enrol(desk, "desk")
            client.webSocket("/v1/compute/pool-a", request = { signedAs(desk, "desk") }) {
                send(Frame.Text("{}"))
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closeReason.await()?.code)
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signedAs(signer: TestSigner, nodeId: String) {
        ComputeNodeAuth.signedConnectHeaders(signer, "pool-a", nodeId, System.currentTimeMillis())
            .forEach { (name, value) -> header(name, value) }
    }

    private suspend fun HttpClient.enrol(signer: TestSigner, nodeId: String): Pair<HttpStatusCode, String> {
        val response = post("/v1/compute/pool-a/enrol") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            setBody(enrolmentBody(signer, nodeId))
        }
        return response.status to response.bodyAsText()
    }

    private fun enrolmentBody(signer: TestSigner, nodeId: String): String = json.encodeToString(
        ComputeNodeEnrolmentRequest.serializer(),
        ComputeNodeAuth.enrolmentRequest(signer, "pool-a", nodeId, nodeId, System.currentTimeMillis()),
    )

    private fun decode(frame: Frame): ComputeRelayServerMessage =
        json.decodeFromString(ComputeRelayServerMessage.serializer(), (frame as Frame.Text).readText())

    private fun register(nodeId: String) = ComputeRelayClientMessage.Register(
        node = ComputeNodeDescriptor(
            nodeId = nodeId,
            displayName = nodeId,
            platform = ComputePlatform.Desktop,
            architecture = "x86_64",
            logicalProcessors = 4,
            memoryMiB = 8_192,
        ),
    )
}
