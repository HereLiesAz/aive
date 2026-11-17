package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.MEMORY_PROTOCOL
import com.hereliesaz.geministrator.memory.MEMORY_PROTOCOL_PROMPT_LABEL
import com.hereliesaz.geministrator.memory.RECALL_UNTRUSTED_NOTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OnDeviceMemoryProtocolTest {
    private val role = "You answer from memory. ".repeat(3)
    private val protocol = PromptContextBlock(MEMORY_PROTOCOL_PROMPT_LABEL, MEMORY_PROTOCOL)

    private fun request(
        stable: List<PromptContextBlock> = emptyList(),
        dynamic: List<PromptContextBlock> = emptyList(),
        roleText: String = role,
    ) = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = "What is the cache size?",
        roleInstructions = roleText,
        acceptanceCriteria = listOf(AcceptanceCriterion("One sentence")),
        promptContext = PromptContext(stablePrefix = stable, dynamicContext = dynamic),
    )

    @Test
    fun protocolRendersInTheStablePartRightAfterTheRole() {
        listOf(request(dynamic = listOf(protocol)), request(stable = listOf(protocol))).forEach { r ->
            val p = OnDeviceTextAgentProvider.renderTaskParts(r)
            assertEquals("ROLE\n${role.trim()}\n\nMEMORY PROTOCOL\n${MEMORY_PROTOCOL.trim()}\n\n", p.stable)
            assertTrue(p.dynamic.startsWith("TASK"), p.dynamic)
            assertEquals(OnDeviceTextAgentProvider.renderTask(r), p.stable + p.dynamic)
        }
    }

    @Test
    fun absentProtocolRendersExactlyAsBefore() {
        val p = OnDeviceTextAgentProvider.renderTaskParts(request())
        assertEquals("ROLE\n${role.trim()}\n\n", p.stable)
        assertFalse(OnDeviceTextAgentProvider.renderTask(request()).contains("MEMORY PROTOCOL"))
    }

    @Test
    fun stablePartIsIdenticalAcrossCallsAndIgnoresRecall() {
        val note = PromptContextBlock("Relevant memory", "$RECALL_UNTRUSTED_NOTE\nfact")
        val a = OnDeviceTextAgentProvider.renderTaskParts(request(dynamic = listOf(protocol)))
        val b = OnDeviceTextAgentProvider.renderTaskParts(request(dynamic = listOf(protocol, note)))
        assertEquals(a.stable, b.stable)
        assertTrue(b.dynamic.contains("RELEVANT MEMORY"))
        assertFalse(b.dynamic.contains("MEMORY PROTOCOL"))
    }

    @Test
    fun protocolWithoutARoleStillEndsTheStablePartWithABlankLine() {
        val p = OnDeviceTextAgentProvider.renderTaskParts(request(dynamic = listOf(protocol), roleText = ""))
        assertTrue(p.stable.startsWith("MEMORY PROTOCOL\n") && p.stable.endsWith("\n\n"), p.stable)
    }

    @Test
    fun estimateCountsTheProtocolAndRecallCapDoesNotTouchIt() {
        val bare = OnDeviceTextAgentProvider.estimateRequestTokens(request())
        val withProtocol = OnDeviceTextAgentProvider.estimateRequestTokens(request(dynamic = listOf(protocol)))
        assertTrue(withProtocol > bare)
        assertTrue(
            OnDeviceTextAgentProvider.estimateTokens(MEMORY_PROTOCOL.length) <= withProtocol - bare + 2,
        )
        val big = PromptContextBlock("Relevant memory", RECALL_UNTRUSTED_NOTE + "\n" + (1..400).joinToString("\n") { "fact $it" })
        val text = OnDeviceTextAgentProvider.renderTask(request(dynamic = listOf(protocol, big)))
        assertTrue(text.contains(MEMORY_PROTOCOL.trim()))
    }

    @Test
    fun overTheLimitRecallGoesFirstAndTheProtocolLast() {
        val recall = PromptContextBlock("Relevant memory", "$RECALL_UNTRUSTED_NOTE\nfact")
        val r = request(dynamic = listOf(protocol, recall))
        val noRecall = OnDeviceTextAgentProvider.estimateRequestTokens(request(dynamic = listOf(protocol)))
        val keepsProtocol = OnDeviceTextAgentProvider.renderTask(r, maxInputTokens = noRecall)
        assertTrue(keepsProtocol.contains("MEMORY PROTOCOL") && !keepsProtocol.contains("RELEVANT MEMORY"), keepsProtocol)
        val bare = OnDeviceTextAgentProvider.estimateRequestTokens(request())
        val dropsBoth = OnDeviceTextAgentProvider.renderTask(r, maxInputTokens = bare)
        assertEquals(OnDeviceTextAgentProvider.renderTask(request()), dropsBoth)
    }

    /** A head that changes with the protocol text re-primes: both reuse caches key on the head text. */
    @Test
    fun aChangedProtocolReprimesTheHead() = runTest {
        val primed = mutableListOf<String>()
        val backend = object : OrtPrefixBackend {
            override fun encode(text: String): LongArray = LongArray(text.length) { text[it].code.toLong() }
            override fun prime(head: String): OrtPrimedHead {
                primed += head
                return object : OrtPrimedHead {
                    override fun generate(tail: String, maxNewTokens: Int) = OnDeviceTextGeneration("ok")
                    override fun close() = Unit
                }
            }
            override fun generateWhole(prompt: String, maxNewTokens: Int) = OnDeviceTextGeneration("whole")
        }
        val reuse = OrtPrefixReuse(backend)
        val first = OnDeviceTextAgentProvider.renderTaskParts(request(dynamic = listOf(protocol)))
        val second = OnDeviceTextAgentProvider.renderTaskParts(request(dynamic = listOf(protocol)))
        val changed = OnDeviceTextAgentProvider.renderTaskParts(
            request(dynamic = listOf(PromptContextBlock(MEMORY_PROTOCOL_PROMPT_LABEL, MEMORY_PROTOCOL + " Changed."))),
        )
        reuse.generate(first.stable, first.dynamic, 8)
        reuse.generate(second.stable, second.dynamic, 8)
        assertEquals(1, primed.size)
        reuse.generate(changed.stable, changed.dynamic, 8)
        assertEquals(2, primed.size)
        assertEquals(PromptPrefixKey.of(null, first.stable), PromptPrefixKey.of(null, second.stable))
        assertTrue(PromptPrefixKey.of(null, first.stable) != PromptPrefixKey.of(null, changed.stable))
    }
}
