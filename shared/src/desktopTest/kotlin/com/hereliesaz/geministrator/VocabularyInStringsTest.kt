package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner renamed five words in everything the keeper reads: Timeline -> Story, Provider -> Source,
 * Inbox -> Messages, Hall Monitor -> Nanny, Drifter -> Orphan, the Build view of a swarm -> Edit, and workflow -> swarm (a swarm is the saved workflow, a hive is the goal; docs/Vocabulary.md).
 * The rulings of the decisions page add: the creature meter is Integrity (never HP or Mood), a creature does a step (never a job), a project is a neighborhood,
 * trusted steps are Trust (not Delegations), the local queen is "Queen on this device", the app's own notes are "Good to know" (not Keeper note), the Stable is the Nursery, the Roster is Breeds,
 * the Azphalt Store is the Shop. A project is a neighborhood; MCP servers keep their engineering name. This scans the user-visible
 * string resources (every `values*` folder of the shared module and of the Android app) for the old words, like
 * [PlaceNamesInStringsTest]. Code identifiers, resource keys, persisted keys and protocol terms are not user text
 * and are not scanned: only the text between `<string ...>` and `</string>` is.
 */
class VocabularyInStringsTest {
    private val retired = Regex("""\b(timelines?|providers?|inbox(es)?|hall[ -]monitors?|drifters?|build|workflows?|hp|mood|stable|roster|azphalt store|jobs?|projects?|delegations?|local planner|keeper note)\b""", RegexOption.IGNORE_CASE)

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
        // "stable" as an ordinary adjective: a device id that does not change, never the Nursery.
        "settings_stable_device_id" to "adjective: a device ID that stays the same",
        // "job" as a compute job (work sent to another machine), never a creature's step (decisions page, D1).
        "settings_maximum_simultaneous_remote_jobs" to "a compute job sent to another machine",
        "settings_compute_node_jobs" to "a compute job sent to another machine",
        // The engineering-names switch names the retired words on purpose: that is what it shows (D35).
        "settings_engineering_names_explain" to "lists the engineering names the switch reveals",
        // "project" in a path the keeper types.
        "repositories_entire_reference_hint" to "a path example the keeper types",
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
