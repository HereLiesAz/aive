package com.hereliesaz.geministrator.prompts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptSuggestionsTest {
    private val history = listOf(PromptEntry("Fix the build", 9), PromptEntry("Older goal", 1))

    @Test
    fun firstObjectiveUsesLastObjectiveWhenPresent() {
        assertEquals("Fix the build", PromptSuggestions.firstObjective(history, "Starter"))
    }

    @Test
    fun firstObjectiveFallsBackToRepositoryStarter() {
        assertEquals("Starter", PromptSuggestions.firstObjective(emptyList(), "Starter"))
    }

    @Test
    fun firstObjectiveIsNullWithoutStarterOrHistory() {
        assertNull(PromptSuggestions.firstObjective(emptyList(), null))
        assertNull(PromptSuggestions.firstObjective(emptyList(), "  "))
    }

    @Test
    fun feedObjectiveSeedsFinishedRunObjectiveOnly() {
        assertEquals("Ship it", PromptSuggestions.feedObjective(" Ship it ", runFinished = true))
        assertNull(PromptSuggestions.feedObjective("", runFinished = true))
    }

    @Test
    fun feedObjectiveIsNullWhileRunning() {
        assertNull(PromptSuggestions.feedObjective("Ship it", runFinished = false))
    }

    @Test
    fun continueObjectiveIsTheParentGoal() {
        assertEquals("Ship it", PromptSuggestions.continueObjective(" Ship it "))
        assertNull(PromptSuggestions.continueObjective(null))
    }

    @Test
    fun retryReasonIsScrubbedAndTruncated() {
        val scrubbed = PromptSuggestions.retryReason("Request failed with sk-abcdefghijklmnopqrstuvwxyz123456 rejected")!!
        assertTrue("sk-abcdefghijklmnop" !in scrubbed)
        assertTrue("[redacted]" in scrubbed)
        val long = PromptSuggestions.retryReason("x".repeat(500))!!
        assertEquals(PromptSuggestions.MAX_REASON_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun retryReasonIsTheFirstNonBlankLine() {
        assertEquals("Tests failed", PromptSuggestions.retryReason("\n  Tests failed  \n  at line 4\n"))
        assertNull(PromptSuggestions.retryReason("  \n "))
        assertNull(PromptSuggestions.retryReason(null))
    }

    @Test
    fun repositoryLabelAndSource() {
        assertEquals("acme/app", PromptSuggestions.repositoryLabel("https://github.com/acme/app.git"))
        assertEquals("acme/app", PromptSuggestions.repositoryLabel("acme/app"))
        assertEquals("app", PromptSuggestions.repositoryLabel("/home/me/code/app/"))
        assertEquals(PromptSuggestions.LocatorSource.Local, PromptSuggestions.locatorSource("C:\\code\\app"))
        assertEquals(PromptSuggestions.LocatorSource.GitLab, PromptSuggestions.locatorSource("https://gitlab.com/g/r"))
        assertEquals(PromptSuggestions.LocatorSource.GitHub, PromptSuggestions.locatorSource("acme/app"))
        assertNull(PromptSuggestions.repositoryLabel("  "))
    }

    @Test
    fun repositoryEntriesFitTheSource() {
        val repos = listOf(PromptEntry("https://gitlab.com/g/r", 3), PromptEntry("acme/app", 2), PromptEntry("/home/me/app", 1))
        assertEquals(listOf("acme/app"), PromptSuggestions.repositoryEntries(repos, PromptSuggestions.LocatorSource.GitHub).map { it.text })
        assertEquals(listOf("/home/me/app"), PromptSuggestions.repositoryEntries(repos, PromptSuggestions.LocatorSource.Local).map { it.text })
    }

    @Test
    fun hiveNameUsesDraftedNameThenObjectiveDerived() {
        val derive = { s: String -> s.split(' ').take(2).joinToString(" ") }
        assertEquals("Drafted", PromptSuggestions.hiveName(" Drafted ", "fix the build", derive))
        assertEquals("fix the", PromptSuggestions.hiveName(null, "fix the build", derive))
        assertNull(PromptSuggestions.hiveName(null, " ", derive))
    }

    @Test
    fun swarmDraftUsesLastPromptThenStarter() {
        assertEquals("Fix the build", PromptSuggestions.swarmDraft(history, "Starter"))
        assertEquals("Starter", PromptSuggestions.swarmDraft(emptyList(), "Starter"))
        assertNull(PromptSuggestions.swarmDraft(emptyList(), null))
    }
}
