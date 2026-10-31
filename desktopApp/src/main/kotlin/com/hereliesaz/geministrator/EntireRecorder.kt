package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
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
        prepareWorktreeSettings(root, workspace)?.let { refusal -> return EntireCli.Result(-1, "", refusal) }
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
    private fun prepareWorktreeSettings(root: File, workspace: File): String? {
        // The worktree is the repository's content: it can track `.entire`, or a file in it, as a
        // symlink. Writing through one would land outside the worktree (the keeper's own settings, or
        // anywhere writable), so nothing here follows a link. Null when ready, else why not.
        val worktree = workspace.toPath().toRealPath()
        val target = worktree.resolve(".entire")
        val refusal = "Not recording: the repository's .entire settings are a symlink"
        if (Files.isSymbolicLink(target)) return refusal
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return refusal
        Files.createDirectories(target)
        if (!target.toRealPath().startsWith(worktree)) return refusal
        val settings = target.resolve("settings.json")
        val localSettings = target.resolve("settings.local.json")
        if (Files.isSymbolicLink(settings) || Files.isSymbolicLink(localSettings)) return refusal
        // The keeper's own settings, read only when they are plain files.
        val source = root.toPath().resolve(".entire")
        source.resolve("settings.json")
            .takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.exists(settings, LinkOption.NOFOLLOW_LINKS) }
            ?.let { Files.copy(it, settings, LinkOption.NOFOLLOW_LINKS) }
        val local = source.resolve("settings.local.json")
            .takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { Files.readString(it) }
            ?.let { with(EntireCli) { it.parseObject() } }
            ?: JsonObject(emptyMap())
        Files.writeString(
            localSettings,
            JsonObject(local + ("external_agents" to JsonPrimitive(true))).toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return null
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
