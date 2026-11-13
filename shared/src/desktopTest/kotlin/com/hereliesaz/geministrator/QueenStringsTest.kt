package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorScope
import com.hereliesaz.geministrator.queen.QueenVoice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The queen's words are string resources: every voice, scope and action has one, in plain words. */
class QueenStringsTest {
    private val strings = File("src/commonMain/composeResources/values/strings.xml").readText()
    private val values = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(strings).associate { it.groupValues[1] to it.groupValues[2] }

    private fun snake(name: String) = name.replace(Regex("(?<!^)([A-Z])"), "_$1").lowercase()

    @Test
    fun everyVoiceHasWords() {
        val missing = QueenVoice.entries.map { "queen_say_" + snake(it.name) }.filter { it !in values }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun scopesAndActionsAreInPlainWordsNotEnumNames() {
        HallMonitorScope.entries.forEach {
            val text = values["queen_scope_" + it.name.lowercase()]
            assertTrue(text != null && !text.equals(it.name, ignoreCase = true), "scope ${it.name}")
        }
        HallMonitorAction.entries.forEach {
            val text = values["queen_action_" + it.name.lowercase()]
            assertTrue(text != null && !text.equals(it.name, ignoreCase = true), "action ${it.name}")
        }
    }

    @Test
    fun noSentenceCarriesMarkupOrALink() {
        val bad = values.filterKeys { it.startsWith("queen_say_") }.filterValues { it.contains("http") || it.contains("<") || it.contains("*") }
        assertEquals(emptyMap<String, String>(), bad)
    }
}
