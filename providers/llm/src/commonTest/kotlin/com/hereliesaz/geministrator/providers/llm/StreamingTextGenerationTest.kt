package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingTextGenerationTest {
    @Test
    fun anthropicStreamsSummarizedThinkingThenText() = runTest {
        val bodies = mutableListOf<String>()
        val client = sseClient(bodies) {
            sse(
                """{"type":"message_start","message":{"usage":{"input_tokens":11}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check the cache first."}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done."}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
            )
        }
        val chunks = mutableListOf<TextGenerationChunk>()
        val result = AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client).stream("go") { chunks += it }

        assertEquals(listOf(TextGenerationChunk.Thinking("Check the cache first."), TextGenerationChunk.Text("Done.")), chunks)
        assertEquals("Done.", result.text)
        assertEquals("Check the cache first.", result.thinking)
        assertEquals(11L, result.inputTokens)
        assertEquals(7L, result.outputTokens)
        assertTrue("\"adaptive\"" in bodies.single() && "\"summarized\"" in bodies.single() && "\"stream\":true" in bodies.single())
    }

    @Test
    fun aModelThatRefusesAdaptiveThinkingFallsBackToABudgetThenNone() = runTest {
        val bodies = mutableListOf<String>()
        val client = sseClient(bodies) { body ->
            when {
                "adaptive" in body -> respond("""{"error":{"message":"thinking.type adaptive is not supported"}}""", HttpStatusCode.BadRequest)
                "budget_tokens" in body -> respond("""{"error":{"message":"thinking is not supported on this model"}}""", HttpStatusCode.BadRequest)
                else -> sse("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"plain"}}""")
            }
        }
        val api = AnthropicMessagesApi(LlmApiKeyProvider { "k" }, client = client)
        assertEquals("plain", api.stream("go") {}.text)
        assertEquals(3, bodies.size)
        assertFalse("thinking" in bodies.last())
        api.stream("again") {}
        assertEquals(4, bodies.size, "once refused, thinking is not requested again")
    }

    @Test
    fun openAiStreamsReasoningSummaryAndFallsBackWhenTheOrgCannotHaveIt() = runTest {
        val bodies = mutableListOf<String>()
        val client = sseClient(bodies) { body ->
            if ("reasoning" in body) {
                respond("""{"error":{"message":"Your organization must be verified to generate reasoning summaries"}}""", HttpStatusCode.BadRequest)
            } else {
                sse(
                    """{"type":"response.output_text.delta","delta":"ok"}""",
                    """{"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
                )
            }
        }
        val result = OpenAiResponsesApi(LlmApiKeyProvider { "k" }, client = client).stream("go") {}
        assertEquals("ok", result.text)
        assertEquals(3L, result.inputTokens)
        assertEquals(2, bodies.size)

        val summarized = sseClient(mutableListOf()) {
            sse(
                """{"type":"response.reasoning_summary_text.delta","delta":"Plan the change."}""",
                """{"type":"response.output_text.delta","delta":"done"}""",
            )
        }
        val chunks = mutableListOf<TextGenerationChunk>()
        OpenAiResponsesApi(LlmApiKeyProvider { "k" }, client = summarized).stream("go") { chunks += it }
        assertEquals(TextGenerationChunk.Thinking("Plan the change."), chunks.first())
    }

    @Test
    fun geminiSeparatesThoughtPartsFromText() = runTest {
        val bodies = mutableListOf<String>()
        val client = sseClient(bodies) {
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"Weighing options.","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Answer."}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}""",
            )
        }
        val result = GeminiGenerateContentApi(LlmApiKeyProvider { "k" }, client = client).stream("go") {}
        assertEquals("Answer.", result.text)
        assertEquals("Weighing options.", result.thinking)
        assertTrue("includeThoughts" in bodies.single())
    }

    @Test
    fun compatibleChatReadsReasoningFieldsAndInlineThinkTags() = runTest {
        val client = sseClient(mutableListOf()) {
            sse(
                """{"choices":[{"delta":{"reasoning_content":"First, "}}]}""",
                """{"choices":[{"delta":{"content":"<thi"}}]}""",
                """{"choices":[{"delta":{"content":"nk>then this</think>Final"}}]}""",
                """{"choices":[{"delta":{"content":" answer"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}""",
                "[DONE]",
            )
        }
        val result = OpenAiCompatibleChatApi(LlmApiKeyProvider { "k" }, model = "m", baseUrl = "https://example.test/v1", client = client)
            .stream("go") {}
        assertEquals("Final answer", result.text)
        assertEquals("First, then this", result.thinking)
        assertEquals(4L, result.inputTokens)
    }

    @Test
    fun memoryInterruptsAThoughtAndTheGenerationResumesWithTheRecall() = runTest {
        val prompts = mutableListOf<String>()
        lateinit var generation: InterruptibleGeneration
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String) = error("not used")
            override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
                prompts += prompt
                onChunk(TextGenerationChunk.Thinking("The #ledger keeps drifting, so check how it was fixed before. "))
                onChunk(TextGenerationChunk.Text("answer ${prompts.size}"))
                return TextGenerationResult("answer ${prompts.size}")
            }
        }
        generation = InterruptibleGeneration(api, maxInterrupts = 1)
        val thoughts = mutableListOf<String>()
        val result = generation.run("TASK") { thought ->
            thoughts += thought
            generation.offer("$MEMORY_MESSAGE_MARKER ledger: rebuilt from the journal last week")
        }
        assertEquals(2, prompts.size, "one interrupt, then the run finishes")
        assertTrue(prompts[1].startsWith("TASK"))
        assertTrue("YOUR REASONING SO FAR" in prompts[1] && "rebuilt from the journal" in prompts[1])
        assertEquals("answer 2", result.text)
        assertEquals(2, thoughts.size)
        assertFalse(generation.offer("$MEMORY_MESSAGE_MARKER late"), "nothing is taken once the run ends")
    }

    @Test
    fun aTextProviderEmitsThinkingAndTakesMemoryMidStream() = runTest {
        val prompts = mutableListOf<String>()
        lateinit var provider: TextLlmProvider
        var runId: ProviderRunId? = null
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String) = error("not used")
            override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
                prompts += prompt
                onChunk(TextGenerationChunk.Thinking("Looking at the sync worker and what changed in it recently, step by step."))
                return TextGenerationResult("result").also { onChunk(TextGenerationChunk.Text(it.text)) }
            }
        }
        provider = TextLlmProvider(AgentProviderId("text"), "Text", api)
        val handle = provider.start(AgentTaskRequest(taskRunId = TaskRunId("t"), objective = "Fix sync", roleInstructions = "Engineer", acceptanceCriteria = emptyList()))
        runId = handle.providerRunId
        val events = mutableListOf<AgentEvent>()
        provider.observe(handle.providerRunId).collect { event ->
            events += event
            if (event is AgentEvent.Thinking && prompts.size == 1) {
                assertIs<ProviderActionResult.Accepted>(provider.sendMessage(runId!!, "$MEMORY_MESSAGE_MARKER #sync was rewritten last week"))
            }
        }
        assertEquals(2, prompts.size)
        assertEquals(2, events.count { it is AgentEvent.Thinking })
        assertIs<AgentEvent.Completed>(events.last())
        assertIs<ProviderActionResult.Rejected>(provider.sendMessage(handle.providerRunId, "plain follow-up"))
    }

    private fun MockRequestHandleScope.sse(vararg data: String): HttpResponseData = respond(
        content = data.joinToString("") { "data: $it\n\n" },
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )

    private fun sseClient(
        bodies: MutableList<String>,
        handler: suspend MockRequestHandleScope.(String) -> HttpResponseData,
    ): HttpClient = HttpClient(
        MockEngine { request: HttpRequestData ->
            val body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> error("unexpected body ${content::class.simpleName} for ${request.method.value} ${request.url} ${request.headers.entries()}")
            }
            bodies += body
            handler(body)
        },
    ) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }
}
