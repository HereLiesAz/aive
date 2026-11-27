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
        // "project" as the plain word for a repository's group in the directory: immediately understandable, not interpreted (owner, 2026-11-27).
        "directory_projects" to "the plain word for a repository's group in the directory",
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

    /**
     * D3 (the keeper's ruling): the noun for one execution of a swarm is "swarm", never "run". "Run" stays only as a verb
     * (execute, operate), in a typed bot command, and as the label of an action. Resource name -> why "run" legitimately
     * stays in that string. A string that names one execution must say swarm instead.
     */
    private val runNoun = Regex("""\bruns?\b""", RegexOption.IGNORE_CASE)

    private val runAllowed: Map<String, String> = buildMap {
        for (key in listOf(
            "keeper_connections_lead",
            "keeper_group_hosts_lead",
            "keeper_device_lead",
            "keeper_device_lead_wide",
            "keeper_a2a_agent_explain",
            "keeper_orphan_unverified_explain",
            "mcp_servers_lead",
            "mcp_server_command_note",
            "compute_coordinate_where_work_runs_assign_the",
            "compute_runs_on_the_originating_device",
            "inspector_executor_ref",
            "district_state_idle",
            "hive_can_select_environment",
            "keeper_local_agents_lead",
            "local_agents_note",
            "schedule_note",
            "hive_not_yet_run",
            "plan_size_reason_parallel",
            "flowchart_detail_compute_unavailable",
            "flowchart_detail_skipped",
            "job_runs_on",
            "build_add_from_steps",
            "memory_runs_on",
            "memory_habits_hint",
            "memory_refinement_empty",
            "memory_habits_proposed",
            "memory_summaries_categories_and_associations_that_agents",
            "memory_this_platform_has_no_on_device",
            "overview_paste_the_repository_url_or_shorthand",
            "settings_connect_as_many_providers_as_you",
            "settings_app_integration_adapter",
            "settings_on_device_small_tasks_runtime",
            "hive_card_share_explain",
            "hive_card_pinned",
            "hive_card_left_out",
            "hive_card_err_nothing_usable",
            "settings_remote_access_description",
            "settings_remote_access_warning",
            "settings_runs_released_orchestration_utility_specialists_on",
            "store_aive_calls_this_model_directly_the",
            "swarm_python_runs_through_a_github_actions",
            "swarm_keeper_instructions_hint",
            "swarm_the_role_runs_as_an_ai",
            "swarm_these_are_aive_s_semantic_orchestration",
            "queen_say_wait_escalation",
            "queen_say_watch_budget",
            "queen_say_nanny_froze",
            "queen_say_research_consulted",
            "queen_say_research_skipped",
            "queen_level_helpful_line",
            "memory_habits_replay_summary",
            "memory_habits_replay_approved",
            "confirm_reject_plan_body",
            "remote_access_advanced_explain",
            "job_approval_off_body",
            "gemini_bridge_disclosure_body",
        )) put(key, "verb: something runs on a host or model, or a person runs it (execute, operate), not one execution")
        put("chat_bots_note", "the chat bot's typed commands /runs and /run are protocol, not user prose")
        for (key in listOf(
            "app_rerun_failed",
            "build_step_tests",
            "hive_draft_hint",
            "improvement_run_cycle",
            "improvement_standing_never",
            "improvement_standing_overdue",
            "lineage_rerun_from_here",
            "lineage_rerun_start",
            "lineage_rerun_start_following",
            "local_agents_login_note",
            "local_agents_never_tested",
            "runs_task_rerun",
            "schedule_run_now",
            "schedule_run_now_note",
            "settings_run_diagnostic_bundle",
        )) put(key, "the verb on an action (Run now, Run a cycle now, Run again, Run tests, Run a review)")
    }

    @Test
    fun noUserStringUsesRunAsTheNounForOneExecution() {
        val hits = resourceFiles().flatMap { file ->
            stringElement.findAll(file.readText()).mapNotNull { m ->
                val key = m.groupValues[1]
                val text = m.groupValues[2]
                if (key in runAllowed) null
                else runNoun.find(text)?.let { "${file.name}: $key: '${it.value}' in ${text.trim().take(80)}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun theRunAllowlistHoldsNothingStale() {
        val live = resourceFiles().flatMap { file ->
            stringElement.findAll(file.readText()).filter { runNoun.containsMatchIn(it.groupValues[2]) }.map { it.groupValues[1] }.toList()
        }.toSet()
        assertEquals(emptySet<String>(), runAllowed.keys - live, "allowlisted strings that no longer contain run")
    }
}
