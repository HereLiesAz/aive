package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.mcp.McpToolOffer
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.hereliesaz.geministrator.providers.lfm.LfmChatPrompt
import com.hereliesaz.geministrator.providers.lfm.LfmModelProfile
import com.hereliesaz.geministrator.providers.lfm.LfmReply
import com.hereliesaz.geministrator.providers.lfm.LfmToolCallParser
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * An on-device LFM3 model as a text API with tools: the prompt is written in the model's own chat format ([LfmChatPrompt]),
 * the model runs through [complete] (LiteRT-LM), and what it writes is read back ([LfmToolCallParser]) into text and tool
 * calls. Stateless like every [ToolCallingTextGenerationApi]: each call carries the whole exchange so far, so the shared
 * tool loop ([McpToolLoop]) drives it unchanged.
 *
 * It costs nothing per token, so [billsPerToken] is false; its token counts are estimates, since the engine reports none.
 */
class LfmLocalChatApi(
    private val profile: LfmModelProfile,
    /** Runs the formatted prompt: (prompt, most new tokens, context tokens, stop markers) to the model's raw text. */
    private val complete: suspend (String, Int, Int, List<String>) -> String,
    override val modelId: String? = "lfm3",
) : ToolCallingTextGenerationApi {
    override val billsPerToken: Boolean get() = false

    override val outputTokenCap: Int get() = MAX_NEW_TOKENS

    override suspend fun generate(prompt: String): TextGenerationResult = generateWithBudget(prompt, MAX_NEW_TOKENS)

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        turnToResult(run(prompt, emptyList(), emptyList(), allowTools = false, maxNewTokens = maxOutputTokens.coerceIn(1, MAX_NEW_TOKENS)))

    override suspend fun converse(
        prompt: String,
        tools: List<McpToolOffer>,
        rounds: List<ToolRound>,
        allowTools: Boolean,
    ): ToolTurn = run(prompt, tools, rounds, allowTools, MAX_NEW_TOKENS).first

    private suspend fun run(
        prompt: String,
        tools: List<McpToolOffer>,
        rounds: List<ToolRound>,
        allowTools: Boolean,
        maxNewTokens: Int,
    ): Pair<ToolTurn, LfmReply> {
        val offered = if (allowTools) tools else emptyList()
        val text = LfmChatPrompt.format(
            messages = messagesFor(prompt, rounds),
            tools = offered.map { LfmChatPrompt.ToolSpec(it.name, it.description.ifBlank { null }, it.inputSchema) },
            enableThinking = profile.thinking,
        )
        val raw = complete(text, maxNewTokens, profile.contextTokens, LfmChatPrompt.STOP_MARKERS)
        val reply = LfmToolCallParser.parse(raw, openedThought = profile.thinking)
        val calls = reply.toolCalls.mapIndexed { index, call ->
            ToolCall(
                id = "lfm-${rounds.size}-$index",
                name = call.name,
                arguments = LfmToolCallParser.argumentsJson(call, offered.firstOrNull { it.name == call.name }?.inputSchema),
            )
        }
        val answer = reply.text.ifBlank { if (calls.isEmpty()) reply.thought.orEmpty() else "" }
        val turn = ToolTurn(
            text = answer,
            calls = calls,
            native = JsonNull,
            inputTokens = OnDeviceTextAgentProvider.estimateTokens(text.length).toLong(),
            outputTokens = OnDeviceTextAgentProvider.estimateTokens(raw.length).toLong(),
        )
        return turn to reply
    }

    private fun messagesFor(prompt: String, rounds: List<ToolRound>): List<LfmChatPrompt.Message> = buildList {
        add(LfmChatPrompt.Message.User(prompt))
        rounds.forEachIndexed { index, round ->
            add(
                LfmChatPrompt.Message.Assistant(
                    content = round.turn.text,
                    toolCalls = round.turn.calls.map { LfmChatPrompt.ToolCall(it.name, it.arguments ?: JsonObject(emptyMap())) },
                ),
            )
            round.results.forEach { result ->
                add(LfmChatPrompt.Message.Tool(if (result.isError) "Error: ${result.content}" else result.content, "round-$index"))
            }
        }
    }

    private fun turnToResult(pair: Pair<ToolTurn, LfmReply>) = TextGenerationResult(
        text = pair.first.text,
        inputTokens = pair.first.inputTokens,
        outputTokens = pair.first.outputTokens,
        thinking = pair.second.thought?.takeIf(String::isNotBlank),
    )

    companion object {
        const val MAX_NEW_TOKENS = 1_024
    }
}
