package com.hereliesaz.geministrator.providers.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkedPromptTest {
    private class Channel(private val replies: (Int, String) -> String) : ConversationChannel {
        val sent = mutableListOf<String>()
        override suspend fun send(message: String): String {
            sent += message
            return replies(sent.size, message)
        }
    }

    private fun prose(sentences: Int) = (1..sentences).joinToString(" ") { "This is sentence number $it of a long task." }

    @Test
    fun splittingKeepsEveryCharacterInOrderAndRespectsTheLimit() {
        val text = prose(200)
        val pieces = PromptChunker.split(text, 500)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 500 }, pieces.map { it.length }.toString())
        assertTrue(pieces.size > 1)
    }

    @Test
    fun aCutFallsAtTheKindestPlace() {
        val text = "First paragraph, fairly long.\n\n" + "x".repeat(40)
        val pieces = PromptChunker.split(text, 45)
        assertEquals("First paragraph, fairly long.\n\n", pieces.first())
        val words = "alpha beta gamma delta epsilon zeta eta theta"
        val cut = PromptChunker.split(words, 20)
        assertTrue(cut.dropLast(1).all { it.endsWith(" ") }, cut.toString())
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val emoji = "😀" // two UTF-16 units
        val text = emoji.repeat(50)
        val pieces = PromptChunker.split(text, 7)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { !it.last().isHighSurrogate() }, "a piece ends inside a pair")
    }

    @Test
    fun aPromptThatFitsGoesAsItIsWithNoWrapping() = runTest {
        val channel = Channel { _, _ -> "the answer" }
        assertEquals("the answer", ChunkedPrompt.send(channel, "short question", 1_800))
        assertEquals(listOf("short question"), channel.sent)
    }

    @Test
    fun aLongPromptGoesInPartsEachAnsweredWithContinueAndTheLastGetsTheAnswer() = runTest {
        val prompt = prose(300)
        val channel = Channel { count, message -> if ("(the last part" in message) "FINAL ANSWER" else "Continue." }
        val answer = ChunkedPrompt.send(channel, prompt, 1_800)
        assertEquals("FINAL ANSWER", answer)
        assertTrue(channel.sent.size > 2)
        assertTrue(channel.sent.all { it.length <= 1_800 }, channel.sent.map { it.length }.toString())
        // Only the first part says what is happening; every part says where it stands.
        assertTrue("comes in ${channel.sent.size} parts" in channel.sent.first())
        assertTrue(channel.sent.drop(1).none { "comes in" in it })
        assertTrue(channel.sent.last().startsWith("Part ${channel.sent.size} of ${channel.sent.size} (the last part"))
        // The parts, stripped of their wrappers, are the prompt.
        val rebuilt = channel.sent.mapIndexed { i, m ->
            m.substringAfter(":\n", "").let { body -> if (i == 0) m.substringAfter("Part 1 of ${channel.sent.size}:\n") else body }
        }.joinToString("")
        assertEquals(prompt, rebuilt)
    }

    @Test
    fun aSourceThatAnswersInsteadOfSayingContinueIsNudgedOnceThenGivenUpOn() = runTest {
        val channel = Channel { _, _ -> "Sure, here is my answer already." }
        val failure = assertFailsWith<ChunkedPromptException> { ChunkedPrompt.send(channel, prose(300), 1_800) }
        assertTrue("part 1 of" in failure.message!!, failure.message)
        // The first part, then one nudge; nothing more is sent to it.
        assertEquals(2, channel.sent.size)
        assertFalse(channel.sent[1].contains("sentence number"))
    }

    @Test
    fun aChattyButCompliantAcknowledgementIsAccepted() = runTest {
        var turn = 0
        val channel = Channel { _, message ->
            if ("(the last part" in message) "DONE" else if (turn++ == 0) "Understood. CONTINUE." else "continue"
        }
        assertEquals("DONE", ChunkedPrompt.send(channel, prose(300), 1_800))
    }

    @Test
    fun tooManyPartsIsRefusedBeforeAnythingIsSent() = runTest {
        val channel = Channel { _, _ -> "continue" }
        val failure = assertFailsWith<ChunkedPromptException> { ChunkedPrompt.send(channel, "x ".repeat(100_000), 1_800, maxParts = 5) }
        assertTrue("limit is 5 parts" in failure.message!!, failure.message)
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun aLimitTooSmallToHoldTheWrapperIsRefused() = runTest {
        val channel = Channel { _, _ -> "continue" }
        assertFailsWith<ChunkedPromptException> { ChunkedPrompt.send(channel, "x".repeat(1_000), 300) }
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun theAdapterOpensAFreshConversationPerCall() = runTest {
        var opened = 0
        val api = ChunkingTextGenerationApi(
            open = { opened++; Channel { _, message -> if ("(the last part" in message) "answer $opened" else "continue" } },
            maxMessageChars = 1_800,
        )
        assertEquals("answer 1", api.generate(prose(300)).text)
        assertEquals("answer 2", api.generate(prose(300)).text)
        assertEquals("short", Channel { _, _ -> "short" }.let { ChunkedPrompt.send(it, "q", 100) })
    }
}
