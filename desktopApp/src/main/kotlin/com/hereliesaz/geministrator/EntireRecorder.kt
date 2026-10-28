package com.hereliesaz.geministrator

import java.io.File
import java.util.prefs.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * The Aive's runs as Entire checkpoints, and Entire's checkpoints as context for the next run
 * (docs/Entire.md). Only for repositories where Entire is set up, and only while the keeper has
 * recording on. Every failure here is reported, never fatal: the run's commit stands either way.
 */
internal class EntireRecorder(
    private val locate: () -> EntireCli? = EntireCli::locate,
    private val preferences: EntirePreferences = EntirePreferences(),
    val sessions: AiveEntireSessions = AiveEntireSessions(),
) {
    /** The CLI when recording is on, else null. */
    fun recordingCli(): EntireCli? = if (preferences.recordRuns) locate() else null

    /**
     * Links session [sessionId] to the commit at HEAD of [workspace], a worktree of [root], with
     * `entire session attach` (it adds the `Entire-Checkpoint` trailer, so HEAD changes). Null when Entire
     * isn't set up in [root]; otherwise the outcome, to show the keeper.
     */
    suspend fun attach(cli: EntireCli, root: File, workspace: File, sessionId: String): EntireCli.Result? {
        if (!cli.enabled(root)) return null
        val launcherDir = installEntireAgentLauncher()
            ?: return EntireCli.Result(-1, "", "Could not install entire-agent-aive in ~/.aive/bin")
        prepareWorktreeSettings(root, workspace)
        val path = launcherDir.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty()
        return cli.run(
            listOf("session", "attach", sessionId, "--agent", "aive", "--force"),
            directory = workspace,
            environment = mapOf("PATH" to path, AiveEntireSessions.DIRECTORY_VARIABLE to sessions.directory.absolutePath),
        )
    }

    /**
     * Recent checkpoints on [root]'s current branch, as text for a drifter's prompt: who worked,
     * what they were asked, which files they touched. Null when there are none, or no Entire.
     */
    suspend fun checkpointContext(root: File, limit: Int = 8): String? {
        val cli = locate() ?: return null
        if (!cli.enabled(root)) return null
        return entireCheckpoints(cli, root, limit).takeIf { it.isNotEmpty() }?.joinToString("\n") { it.line() }
    }

    /**
     * Entire reads settings from the worktree it runs in, and a fresh worktree has none of the
     * keeper's untracked ones. Copy them in, and allow external agents there (only there: the
     * keeper's own checkout is left alone) so `entire session attach --agent aive` finds this app.
     */
    private fun prepareWorktreeSettings(root: File, workspace: File) {
        val source = File(root, ".entire")
        val target = File(workspace, ".entire").apply { mkdirs() }
        File(source, "settings.json").takeIf { it.isFile && !File(target, "settings.json").exists() }
            ?.copyTo(File(target, "settings.json"))
        val local = File(source, "settings.local.json").takeIf(File::isFile)?.readText()?.let {
            with(EntireCli) { it.parseObject() }
        } ?: JsonObject(emptyMap())
        File(target, "settings.local.json").writeText(JsonObject(local + ("external_agents" to JsonPrimitive(true))).toString())
    }
}

/** One checkpoint on a branch: who worked, on which commit, and what they were asked to do. */
internal data class EntireCheckpoint(
    val id: String,
    val agent: String?,
    val date: String?,
    val message: String?,
    val intent: String? = null,
    val summary: String? = null,
) {
    /** One line for a drifter's prompt. */
    fun line(): String = buildString {
        append("- ").append(id.take(12))
        agent?.let { append(" (").append(it).append(")") }
        message?.let { append(" ").append(it.lineSequence().first().take(120)) }
        intent?.let { append(" | asked: ").append(it.take(400)) }
        summary?.let { append(" | did: ").append(it.take(400)) }
    }
}

/**
 * The checkpoints on [root]'s current branch, newest first (`entire checkpoint explain --json`).
 * The first [detailed] also carry their intent and summary, read from each one's explanation.
 */
internal suspend fun entireCheckpoints(cli: EntireCli, root: File, limit: Int, detailed: Int = 3): List<EntireCheckpoint> {
    val result = cli.run(listOf("checkpoint", "explain", "--json"), root, timeoutSeconds = 60)
    if (!result.ok) return emptyList()
    val items = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(result.output.trim()) }.getOrNull()
        as? JsonArray ?: return emptyList()
    return items.take(limit).mapNotNull { it as? JsonObject }.mapIndexedNotNull { index, item ->
        with(EntireCli) {
            val checkpoint = EntireCheckpoint(
                id = item.string("checkpoint_id") ?: return@mapIndexedNotNull null,
                agent = item.string("agent"),
                date = item.string("date"),
                message = item.string("message"),
            )
            if (index >= detailed) return@mapIndexedNotNull checkpoint
            val explained = cli.run(listOf("checkpoint", "explain", checkpoint.id), root, timeoutSeconds = 60)
            if (!explained.ok) return@mapIndexedNotNull checkpoint
            checkpoint.copy(
                intent = explained.output.section("Intent"),
                summary = explained.output.section("Summary")?.takeUnless { it.startsWith("*Not generated") },
            )
        }
    }
}

/** The text under `## [name]` in an explanation, up to the next heading or rule. */
private fun String.section(name: String): String? = lineSequence()
    .dropWhile { it.trim() != "## $name" }
    .drop(1)
    .takeWhile { line -> !line.startsWith("## ") && !line.startsWith("──") }
    .joinToString(" ") { it.trim() }
    .trim()
    .takeIf(String::isNotEmpty)

/** The keeper's Entire choices, kept on this computer. */
internal class EntirePreferences(private val node: Preferences = Preferences.userRoot().node("com/hereliesaz/aive/entire")) {
    var recordRuns: Boolean
        get() = node.getBoolean("recordRuns", false)
        set(value) = node.putBoolean("recordRuns", value)
}
