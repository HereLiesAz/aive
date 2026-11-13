package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner renamed five words in everything the keeper reads: Timeline -> Story, Provider -> Source,
 * Inbox -> Messages, Hall Monitor -> Nanny, Drifter -> Orphan (docs/Vocabulary.md). This scans the user-visible
 * string resources (every `values*` folder of the shared module and of the Android app) for the old words, like
 * [PlaceNamesInStringsTest]. Code identifiers, resource keys, persisted keys and protocol terms are not user text
 * and are not scanned: only the text between `<string ...>` and `</string>` is.
 */
class VocabularyInStringsTest {
    private val retired = Regex("""\b(timelines?|providers?|inbox(es)?|hall[ -]monitors?|drifters?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Resource name -> why the old word legitimately stays in that string. Empty today: every use in the
     * resources named the app's own concept, so all were renamed. Add an entry only for a word that names
     * something else (an external product's own term, a protocol field the keeper must type), and say why.
     */
    private val allowed: Map<String, String> = emptyMap()

    private val stringElement = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun resourceFiles(): List<File> {
        val roots = buildList {
            add(File("src/commonMain/composeResources"))
            File("../androidApp/src").listFiles()?.forEach { add(File(it, "res")) }
        }
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList()
        }
    }

    @Test
    fun noUserStringUsesAWordTheOwnerRetired() {
        val files = resourceFiles()
        assertTrue(files.any { it.name == "strings.xml" }, "resources not found from ${File(".").absolutePath}")
        val hits = files.flatMap { file ->
            stringElement.findAll(file.readText()).mapNotNull { m ->
                val key = m.groupValues[1]
                val text = m.groupValues[2]
                if (key in allowed) null
                else retired.find(text)?.let { "${file.name}: $key: '${it.value}' in ${text.trim().take(80)}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun theAllowlistHoldsNothingStale() {
        val live = resourceFiles().flatMap { file ->
            stringElement.findAll(file.readText()).filter { retired.containsMatchIn(it.groupValues[2]) }.map { it.groupValues[1] }.toList()
        }.toSet()
        assertEquals(emptySet<String>(), allowed.keys - live, "allowlisted strings that no longer contain a retired word")
    }
}
