package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.mcp.McpToolOffer
import com.hereliesaz.geministrator.mcp.McpToolOutcome
import com.hereliesaz.geministrator.mcp.McpToolSource
import com.hereliesaz.geministrator.mcp.McpToolbox
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ModelPrice
import com.hereliesaz.geministrator.providers.ModelPriceTable
import com.hereliesaz.geministrator.providers.spentTokens
import com.hereliesaz.geministrator.workflow.AmbientSpendLedger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a hosted provider reports as spent: priced when its model has a price, never free when it has not, kept when a run fails. */
class TextLlmProviderSpendTest {
    private val request = AgentTaskRequest(
        taskRunId = TaskRunId("task-spend"),
        objective = "Report",
        roleInstructions = "Be brief.",
        acceptanceCriteria = emptyList(),
    )

    private fun api(
        model: String?,
        billed: Boolean = true,
        result: () -> TextGenerationResult,
    ) = object : TextGenerationApi {
        override val modelId: String? = model
        override val billsPerToken: Boolean = billed
        override suspend fun generate(prompt: String): TextGenerationResult = result()
    }

    private suspend fun usageOf(provider: TextLlmProvider): AgentEvent.UsageReported =
        provider.observe(provider.start(request).providerRunId).toList().filterIsInstance<AgentEvent.UsageReported>().single()

    @Test
    fun aModelWithNoPriceIsReportedAsUnknownCostNotAsFree() = runTest {
        val provider = TextLlmProvider(
            AgentProviderId("hosted"), "Hosted",
            api("mystery-1") { TextGenerationResult("ok", inputTokens = 1_000, outputTokens = 100) },
        )

        val usage = usageOf(provider)

        assertEquals(1_000L, usage.inputTokens)
        assertNull(usage.costUsd, "no price is known, so no cost is claimed")
        assertTrue(usage.costUnknown, "and a dollar cap is told it cannot count this")
    }

    @Test
    fun theShippedPriceListPricesTheVerifiedDefaultsAndStillFailsClosedForTheOthers() = runTest {
        // Claude's default is on the list: 1M in and 100k out at the listed $2 and $10 per million, 200k of the input cached at $0.20.
        val claude = TextLlmProvider(
            AgentProviderId("anthropic"), "Claude",
            api("claude-sonnet-5") { TextGenerationResult("ok", inputTokens = 1_000_000, outputTokens = 100_000, cachedInputTokens = 200_000) },
        )
        val priced = usageOf(claude)
        assertEquals(false, priced.costUnknown)
        assertEquals(0.8 * 2.0 + 0.2 * 0.20 + 0.1 * 10.0, assertNotNull(priced.costUsd), 1e-9)

        // The OpenAI default has no verified price: its usage is billed, uncounted, and a dollar cap is told so.
        val openAi = TextLlmProvider(
            AgentProviderId("openai"), "OpenAI",
            api("gpt-5.6") { TextGenerationResult("ok", inputTokens = 1_000, outputTokens = 100) },
        )
        val unpriced = usageOf(openAi)
        assertNull(unpriced.costUsd)
        assertTrue(unpriced.costUnknown)
    }

    @Test
    fun aPricedModelReportsTheCostOfItsTokensWithCachedInputAtTheCachedPrice() = runTest {
        val prices = ModelPriceTable(mapOf(ModelPriceTable.key("hosted", "m1") to ModelPrice(3.0, 15.0, cachedInputUsdPerMillion = 0.30)))
        val provider = TextLlmProvider(
            AgentProviderId("hosted"), "Hosted",
            api("m1") { TextGenerationResult("ok", inputTokens = 1_000, outputTokens = 100, cachedInputTokens = 200) },
            pricing = prices,
        )

        val usage = usageOf(provider)

        assertEquals(false, usage.costUnknown)
        // 800 fresh input at $3, 200 cached input at $0.30 and 100 output at $15, per million tokens.
        assertEquals(0.00396, assertNotNull(usage.costUsd), 1e-9)
    }

    @Test
    fun anAnonymousKeylessEndpointCostsNothing() = runTest {
        val provider = TextLlmProvider(
            AgentProviderId("free-tier"), "Free tier",
            api("free-model", billed = false) { TextGenerationResult("ok", inputTokens = 500, outputTokens = 50) },
        )

        val usage = usageOf(provider)

        assertEquals(0.0, usage.costUsd)
        assertEquals(false, usage.costUnknown)
    }

    @Test
    fun aReplyCutOffAtTheLimitStillReportsWhatItSpent() = runTest {
        val provider = TextLlmProvider(
            AgentProviderId("hosted"), "Hosted",
            api("m1") { throw OutputTruncatedException("truncated", 600L, 4_000L) },
        )

        val events = provider.observe(provider.start(request).providerRunId).toList()

        // Before: the tokens of the cut-off reply were lost with the exception, so every failover paid for nothing counted.
        val usage = events.filterIsInstance<AgentEvent.UsageReported>().single()
        assertEquals(600L, usage.inputTokens)
        assertEquals(4_000L, usage.outputTokens)
        assertTrue(usage.costUnknown)
        assertIs<AgentEvent.Failed>(events.last())
    }

    @Test
    fun aPlanDraftReportsWhatDraftingItSpent() = runTest {
        val provider = TextLlmProvider(
            AgentProviderId("hosted"), "Hosted",
            api("m1") { TextGenerationResult("1. Read\n2. Write", inputTokens = 300, outputTokens = 40) },
        )

        val draft = provider.draftPlanWithUsage(request.copy(requirePlanApproval = true))

        assertEquals("1. Read\n2. Write", draft.text)
        assertEquals(300L, draft.inputTokens)
        assertEquals(40L, draft.outputTokens)
        assertTrue(draft.costUnknown)
    }

    @Test
    fun aPlanDraftThatFailsAfterBeingBilledCarriesItsTokens() = runTest {
        val provider = TextLlmProvider(
            AgentProviderId("hosted"), "Hosted",
            api("m1") { throw OutputTruncatedException("truncated", 300L, 4_000L) },
        )

        val failure = assertFailsWith<IllegalStateException> { provider.draftPlanWithUsage(request.copy(requirePlanApproval = true)) }

        assertEquals(4_000L, failure.spentTokens()?.spentOutputTokens)
    }

    @Test
    fun aToolRunThatBreaksInALaterRoundStillCountsTheEarlierOnes() = runTest {
        val toolbox = LoopToolbox()
        val model = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult = error("not used")
            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn {
                if (rounds.isEmpty()) {
                    return ToolTurn("", listOf(ToolCall("c0", LOOP_OFFER.name, buildJsonObject { put("city", "Lyon") })), JsonArray(emptyList()), 100, 20)
                }
                throw OutputTruncatedException("truncated in round two", 500L, 4_000L)
            }
        }

        val failure = assertFailsWith<SpentGenerationFailure> { McpToolLoop().run(model, "prompt", toolbox) }

        assertEquals(600L, failure.spentInputTokens, "round one's 100 and the cut-off round's 500")
        assertEquals(4_020L, failure.spentOutputTokens)
    }

    @Test
    fun cancellingBetweenToolRoundsStopsTheLoopBeforeAnotherRequestOrToolCall() = runTest {
        val toolbox = LoopToolbox()
        var requests = 0
        val model = object : ToolCallingTextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult = error("not used")
            override suspend fun converse(prompt: String, tools: List<McpToolOffer>, rounds: List<ToolRound>, allowTools: Boolean): ToolTurn {
                requests += 1
                return ToolTurn("", listOf(ToolCall("c$requests", LOOP_OFFER.name, buildJsonObject { put("city", "Lyon") })), JsonArray(emptyList()), 10, 2)
            }
        }
        lateinit var provider: TextLlmProvider
        var runId: ProviderRunId? = null
        toolbox.onCall = { provider.cancel(runId!!) }
        provider = TextLlmProvider(AgentProviderId("hosted"), "Hosted", model, tools = McpToolSource { toolbox })
        runId = provider.start(request).providerRunId

        val events = provider.observe(runId).toList()

        // Before: the loop ran all of its eight rounds of tool calls after the cancel.
        assertEquals(1, requests)
        assertEquals(1, toolbox.calls)
        assertEquals("Hosted session cancelled", assertIs<AgentEvent.Failed>(events.last()).reason)
        assertTrue(events.none { it is AgentEvent.ArtifactProduced })
    }

    @Test
    fun cancellingWhileAGenerationStreamsStopsItAtTheNextPiece() = runTest {
        var delivered = 0
        lateinit var provider: TextLlmProvider
        var runId: ProviderRunId? = null
        val streaming = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult = error("streams")
            override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
                repeat(50) {
                    delivered += 1
                    onChunk(TextGenerationChunk.Text("piece $it "))
                    if (it == 1) provider.cancel(runId!!)
                }
                return TextGenerationResult("never delivered", inputTokens = 1, outputTokens = 1)
            }
        }
        provider = TextLlmProvider(AgentProviderId("hosted"), "Hosted", streaming)
        runId = provider.start(request).providerRunId

        val events = provider.observe(runId).toList()

        // Before: all fifty pieces were read (and billed) before the cancel was noticed.
        assertTrue(delivered <= 3, "delivered $delivered pieces")
        assertEquals("Hosted session cancelled", assertIs<AgentEvent.Failed>(events.last()).reason)
    }

    @Test
    fun theMeteredPlannerCountsBilledGenerationsAndFailedOnesTowardTheHourlyCap() = runTest {
        val recorded = mutableListOf<Pair<Double?, Boolean>>()
        val sink = SpendSink { cost, unknown -> recorded += cost to unknown }
        val prices = ModelPriceTable(mapOf(ModelPriceTable.key("planner", "p1") to ModelPrice(2.0, 10.0)))
        var next: () -> TextGenerationResult = { TextGenerationResult("plan", inputTokens = 1_000_000, outputTokens = 100_000) }
        val inner = api("p1") { next() }

        val metered = MeteredTextGenerationApi(inner, "planner", prices, sink)
        metered.generate("plan it")
        next = { throw OutputTruncatedException("truncated", 1_000_000L, 0L) }
        assertFailsWith<OutputTruncatedException> { metered.generate("plan it again") }
        val unpriced = MeteredTextGenerationApi(api("other") { TextGenerationResult("x", inputTokens = 5, outputTokens = 5) }, "planner", prices, sink)
        unpriced.generate("x")

        assertEquals(listOf<Pair<Double?, Boolean>>(3.0 to false, 2.0 to false, null to true), recorded)
    }

    @Test
    fun theAmbientLedgerKeepsTheLastHourOnly() = runTest {
        var now = 10_000_000L
        val ledger = AmbientSpendLedger { now }
        ledger.record(1.0)
        now += 61 * 60_000L
        ledger.record(2.0)
        assertEquals(2.0, ledger.hourlySpend(now).knownUsd)
    }

    private companion object {
        val LOOP_OFFER = McpToolOffer(
            name = "m1_get_weather",
            description = "Weather",
            inputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            serverId = "mcp-weather01",
            serverName = "Weather",
            toolName = "get_weather",
        )
    }

    private class LoopToolbox : McpToolbox {
        override val offers = listOf(LOOP_OFFER)
        var calls = 0
        var onCall: suspend () -> Unit = {}

        override suspend fun call(offerName: String, arguments: JsonObject): McpToolOutcome {
            calls += 1
            onCall()
            return McpToolOutcome(offerName, LOOP_OFFER.serverId, "Weather", "get_weather", "Sunny", isError = false)
        }
    }
}
