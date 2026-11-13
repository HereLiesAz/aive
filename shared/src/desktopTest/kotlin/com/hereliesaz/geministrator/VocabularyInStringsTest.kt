package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner renamed five words in everything the keeper reads: Timeline -> Story, Provider -> Source,
 * Inbox -> Messages, Hall Monitor -> Nanny, Drifter -> Orphan, the Build view of a swarm -> Edit, and workflow -> swarm (a swarm is the saved workflow, a hive is the goal; docs/Vocabulary.md). This scans the user-visible
 * string resources (every `values*` folder of the shared module and of the Android app) for the old words, like
 * [PlaceNamesInStringsTest]. Code identifiers, resource keys, persisted keys and protocol terms are not user text
 * and are not scanned: only the text between `<string ...>` and `</string>` is.
 */
class VocabularyInStringsTest {
    private val retired = Regex("""\b(timelines?|providers?|inbox(es)?|hall[ -]monitors?|drifters?|build|workflows?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Resource name -> why the old word legitimately stays in that string. Add an entry only for a word that names
     * something else (an external product's own term, a protocol field the keeper must type), and say why.
     */
    private val allowed: Map<String, String> = mapOf(
        // "build" as an ordinary verb or a build version, never the view of a swarm (which is Edit).
        "district_empty" to "verb: build a swarm",
        "hive_new_build_title" to "verb: build it yourself (the tray of roles)",
        "hive_new_build_title_only" to "verb: build it yourself (the tray of roles)",
        "prompt_starter_swarm" to "verb: the starter prompt asks the swarm to build and review code",
        "memory_memory_is_not_available_in_this" to "the app's build version",
        // "workflow" as GitHub's own term: a GitHub Actions workflow file the keeper names.
        "swarm_the_workflow_receives_the_role_task" to "a GitHub Actions workflow (GitHub's term)",
        "swarm_workflow_file_or_id" to "a GitHub Actions workflow file or id (GitHub's term)",
    )

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
