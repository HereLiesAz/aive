package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-scan invariants for the queen's suggestions: only the keeper's tap in the app applies one, no gate or
 * delegation can see one, and the keeper's remote and scripted interfaces cannot reach it.
 */
class QueenSuggestionInvariantsTest {
    private val root = File("src/commonMain/kotlin/com/hereliesaz/geministrator")

    private fun sources(sub: String? = null): List<File> {
        val dir = if (sub == null) root else File(root, sub)
        assertTrue(dir.exists(), "source folder not found from ${File(".").absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun mentioning(files: List<File>, word: String) =
        files.filter { word in it.readText() }.map { it.name }.sorted()

    @Test
    fun queenAcceptIsNotReachableFromControlOrMcp() {
        assertEquals(emptyList<String>(), mentioning(sources("control"), "acceptQueenSuggestion"))
        assertEquals(emptyList<String>(), mentioning(sources("mcp"), "acceptQueenSuggestion"))
        assertEquals(emptyList<String>(), mentioning(sources("control"), "dismissQueenSuggestion"))
    }

    @Test
    fun onlyTheRuntimeAndTheAppCallAccept() {
        assertEquals(listOf("App.kt", "ApplicationRuntime.kt"), mentioning(sources(), "acceptQueenSuggestion"))
    }

    @Test
    fun queenSuggestionIsNotDelegable() {
        val delegation = sources("workflow").filter { it.name.contains("Delegation") || it.name.contains("ApprovalGate") }
        assertTrue(delegation.isNotEmpty())
        assertEquals(emptyList<String>(), mentioning(delegation, "QueenSuggestion"))
        assertEquals(emptyList<String>(), mentioning(delegation, "ImprovementSource.Queen"))
    }

    @Test
    fun theQueenNeverOffersToChangeAnApprovalOrSkipAStep() {
        val menu = File(root, "queen/QueenSuggestions.kt").readText().substringAfter("enum class QueenAction").substringBefore("/** The ledger row")
        for (word in listOf("Skip", "RemoveApproval", "RelaxApproval", "Waive")) {
            assertTrue(word !in menu, "the menu must not offer $word")
        }
    }
}
