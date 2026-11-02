package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptCachingTest {
    private val prompt = CacheablePrompt(
        stablePrefix = "ROLE INSTRUCTIONS\nArchitect\n\n",
        dynamicSuffix = "TASK\nDesign the sync worker",
        reusePolicy = PromptReusePolicy.PreferCache,
    )

    @Test
    fun anthropicMarksTheStablePrefixWithAnEphemeralBreakpointAndReadsCacheUsage() = runTest {
        val bodies = mutableListOf<String>()
        val client = recordingClient(bodies) {
            respondJson(
                """{"content":[{"type":"text","text":"ok"}],
                   "usage":{"input_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":80,"output_tokens":4}}""",
            )
        }
        val api = AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client)
        val result = api.generate(prompt)

        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("text", system.getValue("type").jsonPrimitive.content)
        assertEquals("ROLE INSTRUCTIONS\nArchitect", system.getValue("text").jsonPrimitive.content)
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        val message = body.getValue("messages").jsonArray.single().jsonObject
        assertEquals("TASK\nDesign the sync worker", message.getValue("content").jsonPrimitive.content)

        assertEquals(100L, result.inputTokens, "input counts the whole prompt, cache reads and writes included")
        assertEquals(80L, result.cachedInputTokens)
        assertEquals(0.8f, result.cacheHitFraction)
        assertEquals(PromptCacheMode.ExplicitBreakpoints, api.promptCaching.modes.single())
        assertTrue(api.promptCaching.reportsCacheUsage)
    }

    @Test
    fun anthropicSendsNoBreakpointUnlessThePolicyPrefersCache() = runTest {
        for (policy in listOf(PromptReusePolicy.DisableCache, PromptReusePolicy.ProviderDefault)) {
            val bodies = mutableListOf<String>()
            val client = recordingClient(bodies) {
                respondJson("""{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":9,"output_tokens":1}}""")
            }
            val result = AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client)
                .generate(prompt.copy(reusePolicy = policy))
            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            val system = body.getValue("system").jsonArray.single().jsonObject
            assertFalse("ephemeral" in bodies.single(), "$policy must not request a cache breakpoint")
            assertNull(system["cache_control"])
            assertEquals(9L, result.inputTokens)
            assertNull(result.cacheHitFraction, "no cache fields, no fabricated cache telemetry")
        }
    }

    @Test
    fun anthropicSendsAPlainPromptAsOneUserMessage() = runTest {
        val bodies = mutableListOf<String>()
        val client = recordingClient(bodies) {
            respondJson("""{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":3,"output_tokens":1}}""")
        }
        AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client).generate("Design it")
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertNull(body["system"])
        assertEquals("Design it", body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun anthropicStreamReadsCacheUsageFromMessageStart() = runTest {
        val bodies = mutableListOf<String>()
        val client = recordingClient(bodies) {
            sse(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":30,"cache_read_input_tokens":60}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
                """{"type":"message_stop"}""",
            )
        }
        val result = AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client).stream(prompt) {}
        assertTrue("\"cache_control\"" in bodies.single() && "\"ephemeral\"" in bodies.single())
        assertEquals(100L, result.inputTokens)
        assertEquals(60L, result.cachedInputTokens)
        assertEquals(0.6f, result.cacheHitFraction)
    }

    @Test
    fun openAiResponsesReadCachedTokensAndSendThePrefixFirst() = runTest {
        val bodies = mutableListOf<String>()
        val client = recordingClient(bodies) {
            respondJson(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],
                   "usage":{"input_tokens":2000,"input_tokens_details":{"cached_tokens":1500},"output_tokens":5}}""",
            )
        }
        val api = OpenAiResponsesApi(LlmApiKeyProvider { "k" }, client = client)
        val result = api.generate(prompt)
        val input = Json.parseToJsonElement(bodies.single()).jsonObject.getValue("input").jsonPrimitive.content
        assertTrue(input.startsWith("ROLE INSTRUCTIONS"))
        assertEquals(0.75f, result.cacheHitFraction)
        assertEquals(setOf(PromptCacheMode.ImplicitPrefix), api.promptCaching.modes)
    }

    @Test
    fun geminiReadsCachedContentTokenCount() = runTest {
        val oneShot = recordingClient(mutableListOf()) {
            respondJson(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                   "usageMetadata":{"promptTokenCount":4000,"cachedContentTokenCount":1000,"candidatesTokenCount":5}}""",
            )
        }
        val api = GeminiGenerateContentApi(LlmApiKeyProvider { "k" }, client = oneShot)
        assertEquals(0.25f, api.generate(prompt).cacheHitFraction)
        assertEquals(setOf(PromptCacheMode.ImplicitPrefix), api.promptCaching.modes)

        val streamed = recordingClient(mutableListOf()) {
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],""" +
                    """"usageMetadata":{"promptTokenCount":200,"cachedContentTokenCount":100,"candidatesTokenCount":1}}""",
            )
        }
        assertEquals(0.5f, GeminiGenerateContentApi(LlmApiKeyProvider { "k" }, client = streamed).stream(prompt) {}.cacheHitFraction)
    }

    @Test
    fun compatibleChatReadsCachedTokensAndDeepSeekCacheHits() = runTest {
        val openAiShape = recordingClient(mutableListOf()) {
            respondJson(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":1000,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":900}}}""",
            )
        }
        assertEquals(
            0.9f,
            OpenAiCompatibleChatApi(LlmApiKeyProvider { "k" }, model = "m", baseUrl = "https://example.test/v1", client = openAiShape)
                .generate(prompt).cacheHitFraction,
        )

        val deepSeekShape = recordingClient(mutableListOf()) {
            sse(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":400,"completion_tokens":1,"prompt_cache_hit_tokens":100,"prompt_cache_miss_tokens":300}}""",
                "[DONE]",
            )
        }
        assertEquals(
            0.25f,
            OpenAiCompatibleChatApi(LlmApiKeyProvider { "k" }, model = "m", baseUrl = "https://example.test/v1", client = deepSeekShape)
                .stream(prompt) {}.cacheHitFraction,
        )

        val noCacheField = recordingClient(mutableListOf()) {
            respondJson("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":1}}""")
        }
        assertNull(
            OpenAiCompatibleChatApi(LlmApiKeyProvider { "k" }, model = "m", baseUrl = "https://example.test/v1", client = noCacheField)
                .generate(prompt).cacheHitFraction,
        )
    }

    @Test
    fun textProviderSendsTheStablePrefixFirstUnderTheRequestPolicyAndReportsTheCacheFraction() = runTest {
        val prompts = mutableListOf<CacheablePrompt>()
        val api = object : TextGenerationApi {
            override val promptCaching = PromptCacheCapabilities(setOf(PromptCacheMode.ExplicitBreakpoints), reportsCacheUsage = true)
            override suspend fun generate(prompt: String) = error("not used")
            override suspend fun stream(prompt: CacheablePrompt, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
                prompts += prompt
                onChunk(TextGenerationChunk.Text("work product"))
                return TextGenerationResult("work product", inputTokens = 200, outputTokens = 10, cachedInputTokens = 150)
            }
        }
        val provider = TextLlmProvider(AgentProviderId("cached"), "Cached", api)
        assertEquals(api.promptCaching, provider.capabilities().promptCaching)

        val request = AgentTaskRequest(
            taskRunId = TaskRunId("t"),
            objective = "Fix sync",
            roleInstructions = "Engineer",
            acceptanceCriteria = emptyList(),
            promptContext = PromptContext(
                stablePrefix = listOf(PromptContextBlock("Architecture", "Hexagonal")),
                dynamicContext = listOf(PromptContextBlock("Attempt", "2")),
                reusePolicy = PromptReusePolicy.PreferCache,
            ),
        )
        val events = provider.observe(provider.start(request).providerRunId).toList()

        val sent = prompts.single()
        assertEquals(PromptReusePolicy.PreferCache, sent.reusePolicy)
        assertTrue(sent.stablePrefix.startsWith("ROLE INSTRUCTIONS\nEngineer") && "ARCHITECTURE\nHexagonal" in sent.stablePrefix)
        assertFalse("Fix sync" in sent.stablePrefix || "ATTEMPT" in sent.stablePrefix, "volatile context stays out of the prefix")
        assertTrue(sent.dynamicSuffix.startsWith("TASK\nFix sync") && "ATTEMPT\n2" in sent.dynamicSuffix)
        assertTrue(sent.text.startsWith(sent.stablePrefix.trim()))

        val usage = events.filterIsInstance<AgentEvent.UsageReported>().single()
        assertEquals(200L, usage.inputTokens)
        assertEquals(0.75f, usage.cacheHitFraction)
    }

    @Test
    fun aMemoryContinuationKeepsTheStablePrefix() = runTest {
        val prompts = mutableListOf<CacheablePrompt>()
        lateinit var generation: InterruptibleGeneration
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String) = error("not used")
            override suspend fun stream(prompt: CacheablePrompt, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
                prompts += prompt
                onChunk(TextGenerationChunk.Thinking("The #ledger keeps drifting, so check how it was fixed before. "))
                onChunk(TextGenerationChunk.Text("answer"))
                return TextGenerationResult("answer")
            }
        }
        generation = InterruptibleGeneration(api, maxInterrupts = 1)
        generation.run(prompt) { generation.offer("${com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER} ledger: rebuilt") }

        assertEquals(2, prompts.size)
        assertEquals(prompt.stablePrefix, prompts[1].stablePrefix)
        assertEquals(prompt.reusePolicy, prompts[1].reusePolicy)
        assertTrue(prompts[1].dynamicSuffix.startsWith(prompt.dynamicSuffix) && "MEMORY" in prompts[1].dynamicSuffix)
    }

    @Test
    fun cacheHitFractionIsNullWithoutBothCounts() {
        assertNull(TextGenerationResult("x", inputTokens = 10).cacheHitFraction)
        assertNull(TextGenerationResult("x", cachedInputTokens = 10).cacheHitFraction)
        assertNull(TextGenerationResult("x", inputTokens = 0, cachedInputTokens = 0).cacheHitFraction)
        assertEquals(0f, TextGenerationResult("x", inputTokens = 10, cachedInputTokens = 0).cacheHitFraction)
    }

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.sse(vararg data: String): HttpResponseData = respond(
        content = data.joinToString("") { "data: $it\n\n" },
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )

    private fun recordingClient(
        bodies: MutableList<String>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(
        MockEngine { request: HttpRequestData ->
            when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> bodies += content.bytes().decodeToString()
                else -> Unit
            }
            handler(request)
        },
    ) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }
}
