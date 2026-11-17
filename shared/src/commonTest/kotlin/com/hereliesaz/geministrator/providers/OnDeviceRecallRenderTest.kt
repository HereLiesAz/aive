package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.RECALL_UNTRUSTED_NOTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnDeviceRecallRenderTest {
    private fun request(
        dynamic: List<PromptContextBlock>,
        role: String = "You answer from memory.",
    ) = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = "What is the cache size?",
        roleInstructions = role,
        acceptanceCriteria = listOf(AcceptanceCriterion("One sentence")),
        contextArtifacts = listOf(
            ArtifactRef(
                id = ArtifactId("a"),
                kind = ArtifactKind.Research,
                taskRunId = TaskRunId("up"),
                label = "Notes",
                textContent = "artifact text",
                createdAtEpochMillis = 0L,
            ),
        ),
        promptContext = PromptContext(dynamicContext = dynamic),
    )

    private val relevant = PromptContextBlock("Relevant memory", "$RECALL_UNTRUSTED_NOTE\nThe cache size is 40 MB.")

    @Test
    fun memoryRendersAfterCriteriaAndBeforeArtifacts() {
        val text = OnDeviceTextAgentProvider.renderTask(request(listOf(relevant)))
        val criteria = text.indexOf("ACCEPTANCE CRITERIA")
        val memory = text.indexOf("RELEVANT MEMORY\n")
        val artifact = text.indexOf("NOTES\n")
        assertTrue(criteria in 0 until memory, text)
        assertTrue(memory < artifact, text)
        assertTrue(text.contains("RELEVANT MEMORY\n$RECALL_UNTRUSTED_NOTE\nThe cache size is 40 MB.\n\nNOTES"), text)
    }

    @Test
    fun habitsAndUnrelatedBlocksAreExcluded() {
        val text = OnDeviceTextAgentProvider.renderTask(
            request(
                listOf(
                    PromptContextBlock("Habits", "habit text"),
                    PromptContextBlock("Memory protocol", "protocol text"),
                    PromptContextBlock("Swarm preamble", "preamble text"),
                    relevant,
                ),
            ),
        )
        assertFalse(text.contains("habit text"))
        // The protocol is rules, rendered in the stable part before TASK (see OnDeviceMemoryProtocolTest).
        assertTrue(text.contains("MEMORY PROTOCOL\nprotocol text\n\nTASK"), text)
        assertFalse(text.contains("preamble text"))
        assertTrue(text.contains("cache size is 40 MB"))
    }

    @Test
    fun blockWithoutTheNoteGetsIt() {
        val text = OnDeviceTextAgentProvider.renderTask(request(listOf(PromptContextBlock("Memory", "#cache #size"))))
        assertTrue(text.contains("MEMORY\n$RECALL_UNTRUSTED_NOTE\n#cache #size\n\n"), text)
    }

    @Test
    fun capCutsAtALineBoundaryAndKeepsTheNote() {
        val lines = (1..200).joinToString("\n") { "line $it of recalled text" }
        val blocks = OnDeviceTextAgentProvider.recallBlocks(
            request(listOf(PromptContextBlock("Relevant memory", "$RECALL_UNTRUSTED_NOTE\n$lines"))),
        )
        val (label, content) = blocks.single()
        assertTrue(content.startsWith(RECALL_UNTRUSTED_NOTE))
        assertTrue(label.length + 3 + content.length <= OnDeviceTextAgentProvider.MAX_RECALL_CHARS)
        assertTrue(content.lines().drop(1).all { Regex("line \\d+ of recalled text").matches(it) }, content)
        assertTrue(content.length > RECALL_UNTRUSTED_NOTE.length)
    }

    @Test
    fun firstBlocksWinAndLaterOnesAreDropped() {
        val big = PromptContextBlock("Relevant memory", RECALL_UNTRUSTED_NOTE + "\n" + (1..300).joinToString("\n") { "fact number $it" })
        val later = PromptContextBlock("Memory timeline", "$RECALL_UNTRUSTED_NOTE\nlater fact")
        val blocks = OnDeviceTextAgentProvider.recallBlocks(request(listOf(big, later)))
        assertEquals(listOf("Relevant memory"), blocks.map { it.first })
    }

    @Test
    fun stablePrefixDoesNotChangeWithRecall() {
        val a = OnDeviceTextAgentProvider.renderTaskParts(request(emptyList()))
        val b = OnDeviceTextAgentProvider.renderTaskParts(request(listOf(relevant)))
        assertEquals(a.stable, b.stable)
        assertTrue(b.dynamic.startsWith("TASK"))
        assertTrue(b.dynamic.length > a.dynamic.length)
    }

    @Test
    fun partsConcatenateToRenderTaskWithRecall() {
        val r = request(listOf(relevant))
        listOf(false, true).forEach { planOnly ->
            val p = OnDeviceTextAgentProvider.renderTaskParts(r, planOnly)
            assertEquals(OnDeviceTextAgentProvider.renderTask(r, planOnly), p.stable + p.dynamic)
        }
    }

    @Test
    fun estimateIncludesRecallAndRecallIsDroppedFirstWhenTooBig() {
        val without = request(emptyList())
        val with = request(listOf(relevant))
        val bare = OnDeviceTextAgentProvider.estimateRequestTokens(without)
        val full = OnDeviceTextAgentProvider.estimateRequestTokens(with)
        assertTrue(full > bare)
        // A limit between the two: recall goes, criteria and artifacts stay.
        val text = OnDeviceTextAgentProvider.renderTask(with, maxInputTokens = bare)
        assertFalse(text.contains("RELEVANT MEMORY"), text)
        assertTrue(text.contains("ACCEPTANCE CRITERIA") && text.contains("NOTES\nartifact text"), text)
        assertEquals(OnDeviceTextAgentProvider.renderTask(without), text)
        // A roomy limit keeps it.
        assertTrue(OnDeviceTextAgentProvider.renderTask(with, maxInputTokens = full).contains("RELEVANT MEMORY"))
    }
}
