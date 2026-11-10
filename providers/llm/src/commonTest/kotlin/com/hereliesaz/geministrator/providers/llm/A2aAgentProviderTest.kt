package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.a2a.A2aAgentCard
import com.hereliesaz.geministrator.a2a.A2aAgentConnections
import com.hereliesaz.geministrator.a2a.A2aAgentInterface
import com.hereliesaz.geministrator.a2a.A2aCodec
import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aAgentProviderTest {
    private val request = AgentTaskRequest(
        taskRunId = TaskRunId("task-1"),
        objective = "Summarize the incident",
        roleInstructions = "Be brief.",
        acceptanceCriteria = listOf(AcceptanceCriterion("Mentions the root cause")),
        requiredArtifacts = setOf(ArtifactKind.Research),
    )

    private fun card(url: String = "https://agent.example/a2a", version: String = "1.0") = A2aCodec.encodeCard(
        A2aAgentCard(
            name = "Remote",
            description = "d",
            version = "1",
            interfaces = listOf(A2aAgentInterface(url, protocolVersion = version)),
            skills = emptyList(),
            bearerAuth = true,
        ),
    ).toString()

    private data class Call(val method: String, val rpcMethod: String?, val body: String, val auth: String?, val version: String?)

    private fun provider(
        calls: MutableList<Call>,
        cardJson: String = card(),
        token: String? = "tok",
        handle: (String, String) -> String,
    ): Pair<A2aAgentProvider, HttpClient> {
        val client = HttpClient(MockEngine { call ->
            if (call.method == HttpMethod.Get) {
                calls += Call("GET", null, "", call.headers[HttpHeaders.Authorization], null)
                json(cardJson)
            } else {
                val body = (call.body as TextContent).text
                val rpc = Json.parseToJsonElement(body).jsonObject
                val method = rpc["method"]!!.jsonPrimitive.content
                calls += Call("POST", method, body, call.headers[HttpHeaders.Authorization], call.headers["A2A-Version"])
                val id = rpc["id"].toString()
                json("""{"jsonrpc":"2.0","id":$id,${handle(method, body)}}""")
            }
        }) { expectSuccess = false }
        var n = 0
        return A2aAgentProvider(
            id = AgentProviderId("a2a:agent.example"),
            displayName = "Remote",
            cardUrl = "https://agent.example/.well-known/agent-card.json",
            bearerToken = token,
            client = client,
            pollIntervalMillis = 250,
            idFactory = { "id-${++n}" },
        ) to client
    }

    @Test
    fun isExplicitOnlyAndDraftsPlansWithoutCallingTheAgent() = runTest {
        val calls = mutableListOf<Call>()
        val (provider, client) = provider(calls) { _, _ -> error("no calls") }
        try {
            assertTrue(provider.explicitOnly)
            assertContains(provider.draftPlan(request), "Mentions the root cause")
            assertTrue(calls.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun sendsTheTaskPollsAndMapsArtifactsOnCompletion() = runTest {
        val calls = mutableListOf<Call>()
        var polls = 0
        val (provider, client) = provider(calls) { method, _ ->
            when (method) {
                "SendMessage" -> """"result":{"task":{"id":"t-1","contextId":"c-1","status":{"state":"TASK_STATE_SUBMITTED"}}}"""
                "GetTask" -> {
                    polls += 1
                    when (polls) {
                        1 -> """"result":{"id":"t-1","contextId":"c-1","status":{"state":"TASK_STATE_WORKING","message":{"messageId":"s1","role":"ROLE_AGENT","parts":[{"text":"Reading logs"}]}}}"""
                        else -> """"result":{"id":"t-1","contextId":"c-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"artifactId":"a1","name":"Summary","parts":[{"text":"Root cause: a race."},{"url":"https://agent.example/report.pdf","mediaType":"application/pdf","filename":"report.pdf"}]}]}"""
                    }
                }
                else -> error("unexpected $method")
            }
        }
        try {
            val handle = provider.start(request)
            assertEquals(ProviderRunId("t-1"), handle.providerRunId)
            val send = calls.single { it.rpcMethod == "SendMessage" }
            assertEquals("Bearer tok", send.auth)
            assertEquals("1.0", send.version)
            assertContains(send.body, "Summarize the incident")
            assertContains(send.body, "ROLE_USER")
            // The card is fetched without the token.
            assertNull(calls.first { it.method == "GET" }.auth)

            val events = provider.observe(handle.providerRunId).toList()
            assertIs<AgentEvent.Completed>(events.last())
            assertTrue(events.any { it is AgentEvent.Message && it.content == "Reading logs" })
            val artifacts = events.filterIsInstance<AgentEvent.ArtifactProduced>().map { it.artifact }
            assertEquals(ArtifactKind.Research, artifacts.first().kind)
            assertContains(artifacts.first().textContent.orEmpty(), "Root cause: a race.")
            assertEquals("https://agent.example/report.pdf", artifacts.single { it.uri != null }.uri)
            assertEquals("t-1", artifacts.first().metadata["a2aTaskId"])
            // Remote output is external content, links included (docs/THREAT_MODEL.md, Context provenance).
            artifacts.forEach { assertEquals("ExternalContent", it.metadata["aive.provenance"]) }
        } finally {
            client.close()
        }
    }

    @Test
    fun failedAndRejectedTasksFail() = runTest {
        for (state in listOf("failed", "rejected", "canceled")) {
            val calls = mutableListOf<Call>()
            val (provider, client) = provider(calls, cardJson = card(version = "0.3.0")) { method, _ ->
                when (method) {
                    "message/send" -> """"result":{"kind":"task","id":"t-2","contextId":"c","status":{"state":"submitted"}}"""
                    "tasks/get" -> """"result":{"kind":"task","id":"t-2","contextId":"c","status":{"state":"$state","message":{"kind":"message","messageId":"x","role":"agent","parts":[{"kind":"text","text":"out of quota"}]}}}"""
                    else -> error("unexpected $method")
                }
            }
            try {
                val handle = provider.start(request)
                assertNull(calls.single { it.rpcMethod == "message/send" }.version)
                val events = provider.observe(handle.providerRunId).toList()
                val failed = assertIs<AgentEvent.Failed>(events.last())
                assertContains(failed.reason, "out of quota")
                assertTrue(events.none { it is AgentEvent.Completed })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun aDirectMessageReplyCompletesWithItsText() = runTest {
        val calls = mutableListOf<Call>()
        val (provider, client) = provider(calls) { _, _ ->
            """"result":{"message":{"messageId":"r1","role":"ROLE_AGENT","parts":[{"text":"Here is the summary."}]}}"""
        }
        try {
            val handle = provider.start(request)
            val events = provider.observe(handle.providerRunId).toList()
            assertIs<AgentEvent.Completed>(events.last())
            assertEquals("Here is the summary.", events.filterIsInstance<AgentEvent.ArtifactProduced>().single().artifact.textContent)
            assertIs<ProviderActionResult.Rejected>(provider.reconnect(handle.providerRunId, request, false, false))
        } finally {
            client.close()
        }
    }

    @Test
    fun cancelAndMessagesReachTheRemoteTask() = runTest {
        val calls = mutableListOf<Call>()
        val (provider, client) = provider(calls) { method, _ ->
            when (method) {
                "SendMessage" -> """"result":{"task":{"id":"t-3","contextId":"c-3","status":{"state":"TASK_STATE_INPUT_REQUIRED"}}}"""
                "CancelTask" -> """"error":{"code":-32002,"message":"already done"}"""
                else -> error("unexpected $method")
            }
        }
        try {
            val handle = provider.start(request)
            assertEquals(ProviderActionResult.Accepted, provider.sendMessage(handle.providerRunId, "Use the March logs"))
            val reply = calls.filter { it.rpcMethod == "SendMessage" }.last()
            assertContains(reply.body, "\"taskId\":\"t-3\"")
            assertContains(reply.body, "\"contextId\":\"c-3\"")
            // Not cancelable remotely means it is over there already: nothing left to stop.
            assertEquals(ProviderActionResult.Accepted, provider.cancel(handle.providerRunId))
            assertTrue(calls.any { it.rpcMethod == "CancelTask" })
        } finally {
            client.close()
        }
    }

    @Test
    fun aTokenIsNeverSentToAnotherOrigin() = runTest {
        val calls = mutableListOf<Call>()
        val (provider, client) = provider(calls, cardJson = card(url = "https://elsewhere.example/a2a")) { _, _ -> error("must not post") }
        try {
            assertFailsWith<IllegalArgumentException> { provider.start(request) }
            assertTrue(calls.none { it.method == "POST" })
        } finally {
            client.close()
        }
    }

    @Test
    fun aTaskIsNeverSentToAnotherOriginEvenWhenThereIsNoToken() = runTest {
        val calls = mutableListOf<Call>()
        val (provider, client) = provider(calls, cardJson = card(url = "https://elsewhere.example/a2a"), token = null) { _, _ -> error("must not post") }
        try {
            assertFailsWith<IllegalArgumentException> { provider.start(request) }
            assertTrue(calls.none { it.method == "POST" })
        } finally {
            client.close()
        }
    }

    @Test
    fun anOversizedAgentCardIsRefusedByBytesWhileItIsRead() = runTest {
        // Multi-byte text: few characters, many bytes. The cap counts bytes.
        val wide = """{"name":"${"€".repeat(A2aClientApi.MAX_CARD_BYTES / 2)}"}"""
        val client = HttpClient(MockEngine { json(wide) }) { expectSuccess = false }
        try {
            val provider = A2aAgentProvider(
                id = AgentProviderId("a2a:agent.example"), displayName = "Remote",
                cardUrl = "https://agent.example/.well-known/agent-card.json", bearerToken = null, client = client, pollIntervalMillis = 250,
            )
            val failure = assertFailsWith<IllegalStateException> { provider.start(request) }
            assertContains(failure.message.orEmpty(), "larger than")
        } finally {
            client.close()
        }
    }

    @Test
    fun anOversizedRpcResponseIsRefused() = runTest {
        val big = "€".repeat(A2aClientApi.MAX_RESPONSE_BYTES / 2)
        val client = HttpClient(MockEngine { call ->
            if (call.method == HttpMethod.Get) json(card()) else json("""{"jsonrpc":"2.0","id":"x","result":{"pad":"$big"}}""")
        }) { expectSuccess = false }
        try {
            val provider = A2aAgentProvider(
                id = AgentProviderId("a2a:agent.example"), displayName = "Remote",
                cardUrl = "https://agent.example/.well-known/agent-card.json", bearerToken = null, client = client, pollIntervalMillis = 250,
            )
            val failure = assertFailsWith<Exception> { provider.start(request) }
            assertContains(failure.message.orEmpty(), "larger than")
        } finally {
            client.close()
        }
    }

    @Test
    fun refusedTokenIsReportedAsAuthFailure() = runTest {
        val client = HttpClient(MockEngine { call ->
            if (call.method == HttpMethod.Get) json(card()) else respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
        }) { expectSuccess = false }
        try {
            val provider = A2aAgentProvider(
                id = AgentProviderId("a2a:agent.example"),
                displayName = "Remote",
                cardUrl = "https://agent.example/.well-known/agent-card.json",
                bearerToken = "wrong",
                client = client,
                pollIntervalMillis = 250,
            )
            val failure = assertFailsWith<IllegalStateException> { provider.start(request) }
            assertContains(failure.message.orEmpty(), "401")
        } finally {
            client.close()
        }
    }

    @Test
    fun connectedAgentsAreBuiltFromTheCatalog() {
        A2aAgentConnections.useSettings(MapSettings())
        try {
            val agent = A2aAgentConnections.add("https://agent.example")
            assertTrue(HostedLlmProviders.configured(emptyMap()).none { it.id.value == agent.providerId })
            val built = HostedLlmProviders.configured(mapOf(agent.providerId to ProviderCatalog.ANONYMOUS_CREDENTIAL))
                .single { it.id.value == agent.providerId }
            assertIs<A2aAgentProvider>(built)
            assertTrue(built.explicitOnly)
        } finally {
            A2aAgentConnections.useSettings(MapSettings())
        }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
