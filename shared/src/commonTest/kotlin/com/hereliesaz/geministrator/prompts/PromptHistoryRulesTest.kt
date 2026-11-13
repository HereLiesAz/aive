package com.hereliesaz.geministrator.prompts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptHistoryRulesTest {
    private fun record(history: List<PromptEntry>, text: String, now: Long) = PromptHistoryRules.record(history, text, now)

    @Test
    fun recordMovesRepeatToFrontAndBumpsCount() {
        var h = record(emptyList(), "fix the build", 1)
        h = record(h, "add tests", 2)
        h = record(h, "fix the build", 3)
        assertEquals(listOf("fix the build", "add tests"), h.map { it.text })
        assertEquals(2, h.first().count)
    }

    @Test
    fun recordDedupesCaseAndWhitespaceInsensitively() {
        var h = record(emptyList(), "Fix   the build", 1)
        h = record(h, "  fix the BUILD ", 2)
        assertEquals(1, h.size)
        assertEquals("fix the BUILD", h.single().text)
    }

    @Test
    fun recordKeepsAtMostTwelveEntries() {
        var h = emptyList<PromptEntry>()
        repeat(30) { h = record(h, "objective number $it", it.toLong()) }
        assertEquals(PromptHistoryRules.MAX_ENTRIES, h.size)
        assertEquals("objective number 29", h.first().text)
    }

    @Test
    fun recordRefusesBlankAndTooShort() {
        assertEquals(emptyList(), record(emptyList(), "   ", 1))
        assertEquals(emptyList(), record(emptyList(), "ab", 1))
    }

    @Test
    fun recordRefusesTextLongerThanMaxEntryChars() {
        assertFalse(PromptHistoryRules.isRecordable("a".repeat(PromptHistoryRules.MAX_ENTRY_CHARS + 1)))
        assertTrue(PromptHistoryRules.isRecordable("a".repeat(PromptHistoryRules.MAX_ENTRY_CHARS)))
    }

    @Test
    fun recordRefusesTextThatLooksLikeAKey() {
        listOf(
            "sk-abcdefghijklmnopqrstuvwxyz123456",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "use Bearer abcdefghijklmnop",
            "password=hunter2hunter2",
            "https://user:pw@github.com/acme/app",
        ).forEach { secret ->
            assertEquals(emptyList(), record(emptyList(), secret, 1), secret)
        }
    }

    @Test
    fun recordNeverStoresMaskedCopy() {
        val h = record(emptyList(), "token=abcd1234efgh", 1)
        assertTrue(h.isEmpty())
    }

    @Test
    fun serializedHistoryStaysUnderSingleValueLimit() {
        var h = emptyList<PromptEntry>()
        repeat(40) { h = record(h, "word$it ".repeat(80).trim().take(PromptHistoryRules.MAX_ENTRY_CHARS), it.toLong()) }
        assertTrue(PromptHistoryRules.encode(h).length <= PromptHistoryRules.MAX_SERIALIZED_CHARS)
        assertTrue(h.isNotEmpty())
    }

    private val sample = listOf(
        PromptEntry("Fix the flaky login test", 3),
        PromptEntry("Add dark mode", 2, count = 5),
        PromptEntry("Review the login flow", 1),
    )

    @Test
    fun suggestionsFilterByPrefixAndWordPrefix() {
        assertEquals(listOf("Fix the flaky login test"), PromptHistoryRules.suggestions(sample, "fix"))
        assertEquals(listOf("Fix the flaky login test", "Review the login flow"), PromptHistoryRules.suggestions(sample, "log"))
    }

    @Test
    fun suggestionsExcludeExactCurrentValue() {
        assertEquals(emptyList(), PromptHistoryRules.suggestions(sample, "add dark mode"))
    }

    @Test
    fun suggestionsOrderByRecencyThenCount() {
        assertEquals(sample.map { it.text }, PromptHistoryRules.suggestions(sample, ""))
        val tie = listOf(PromptEntry("aaa one", 1, 1), PromptEntry("bbb two", 1, 4))
        assertEquals(listOf("bbb two", "aaa one"), PromptHistoryRules.suggestions(tie, ""))
    }

    @Test
    fun suggestionsAreCapped() {
        val many = (1..20).map { PromptEntry("entry $it", it.toLong()) }
        assertEquals(5, PromptHistoryRules.suggestions(many, "").size)
    }

    @Test
    fun roundTripJsonSurvivesUnknownFields() {
        val encoded = """[{"text":"hello there","usedAtEpochMillis":5,"count":2,"future":true}]"""
        assertEquals(listOf(PromptEntry("hello there", 5, 2)), PromptHistoryRules.decode(encoded))
        assertEquals(sample, PromptHistoryRules.decode(PromptHistoryRules.encode(sample)))
    }

    @Test
    fun corruptJsonYieldsEmptyHistoryNotACrash() {
        assertEquals(emptyList(), PromptHistoryRules.decode("{not json"))
        assertEquals(emptyList(), PromptHistoryRules.decode(null))
    }

    @Test
    fun secretFieldsAndDisabledHistoryNeverOffer() {
        assertFalse(PromptHistoryRules.shouldOffer(secret = true, enabled = true))
        assertFalse(PromptHistoryRules.shouldOffer(secret = false, enabled = false))
        assertTrue(PromptHistoryRules.shouldOffer(secret = false, enabled = true))
    }
}
