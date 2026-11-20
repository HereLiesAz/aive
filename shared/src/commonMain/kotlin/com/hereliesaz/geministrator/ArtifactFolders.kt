package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * A project's artifacts, saved to a folder the keeper picks (docs/Artifact-Folders.md).
 *
 * Off until the keeper chooses a folder for a project. Then each artifact can be saved by hand (Save, or Save all on a run), and the
 * keeper can switch on saving as they are made. The folder is per project and per device (a path or a document-tree address means
 * nothing on another device), so it lives in this device's settings and never in the project, its exports or a .ive bundle. A
 * repository is not needed. Files go in one subfolder per run, named by creature; nothing is ever overwritten.
 */

/** A folder a platform can write into: [ref] is the platform's own address for it, [displayName] what the keeper reads. */
data class ArtifactFolderRef(val ref: String, val displayName: String)

/** What a platform provides for saving files into a keeper-chosen folder. Null of it (the web) means no such folder. */
interface ArtifactFolderService {
    /** Opens the platform's folder picker; null when the keeper cancels. The folder stays writable across restarts. */
    suspend fun chooseFolder(): ArtifactFolderRef?

    /**
     * Writes [content] as a file named [fileName] inside [subfolder] of [folder] (both created as needed) and returns the name it
     * was given. A name already taken is never overwritten: the file gets a number ("name (2).md"). Null when it could not be written.
     */
    suspend fun write(folder: String, subfolder: String, fileName: String, content: String): String?
}

/** Set once at start-up by the platform that has [ArtifactFolderService]. */
object ArtifactFolderHost {
    @kotlin.concurrent.Volatile
    var service: ArtifactFolderService? = null
}

/** Each project's folder and switch, on this device. */
object ArtifactFolderSettings {
    // Null where the platform has no settings store yet (a unit test without an Android context): the feature is then simply off.
    private val settings: Settings? by lazy { runCatching { Settings() }.getOrNull() }

    /** Bumped on every change, so a screen reading the settings redraws. */
    var changes: Int by mutableStateOf(0)
        private set

    fun folder(projectId: String): ArtifactFolderRef? {
        val ref = settings?.getStringOrNull(key(projectId, "ref"))?.takeIf(String::isNotBlank) ?: return null
        return ArtifactFolderRef(ref, settings?.getStringOrNull(key(projectId, "name")).orEmpty().ifBlank { ref })
    }

    fun setFolder(projectId: String, folder: ArtifactFolderRef?) {
        if (folder == null) {
            settings?.remove(key(projectId, "ref"))
            settings?.remove(key(projectId, "name"))
            settings?.remove(key(projectId, "auto"))
        } else {
            settings?.putString(key(projectId, "ref"), folder.ref)
            settings?.putString(key(projectId, "name"), folder.displayName)
        }
        changes += 1
    }

    /** Whether artifacts are saved as they are made (only ever true with a folder). */
    fun automatic(projectId: String): Boolean = folder(projectId) != null && settings?.getBoolean(key(projectId, "auto"), false) == true

    fun setAutomatic(projectId: String, on: Boolean) {
        settings?.putBoolean(key(projectId, "auto"), on)
        changes += 1
    }

    /** The artifacts of [runId] already written, so saving as they are made never writes one twice. */
    fun saved(runId: String): Set<String> =
        settings?.getStringOrNull("aive.artifacts.saved.$runId").orEmpty().split('\n').filter(String::isNotBlank).toSet()

    fun markSaved(runId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        settings?.putString("aive.artifacts.saved.$runId", (saved(runId) + ids).joinToString("\n"))
    }

    /** Forgets what was written for [runId] (so a test, or a keeper who emptied the folder, starts clean). */
    internal fun forgetSaved(runId: String) = settings?.remove("aive.artifacts.saved.$runId")

    private fun key(projectId: String, part: String) = "aive.artifacts.folder.$projectId.$part"
}

/** One file to write: the artifact it holds, where, under what name. */
internal data class PlannedArtifactFile(val artifactId: String, val subfolder: String, val fileName: String, val content: String)

internal object ArtifactFilePlanner {
    private const val MAX_NAME = 90
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]+")

    /** A file or folder name that is safe on every platform: no separators or reserved characters, no leading dots, not empty. */
    fun safeName(raw: String, fallback: String): String {
        val cleaned = raw.replace(illegal, "-").replace(Regex("\\s+"), " ").trim().trim('.', ' ', '-')
        return cleaned.take(MAX_NAME).trim('.', ' ', '-').ifEmpty { fallback }
    }

    /** "run-12 Feature delivery": the run's own folder. [runNumber] is null where it is not known; the run's id then names it. */
    fun runFolder(definition: WorkflowDefinition, run: WorkflowRun, runNumber: Int?): String {
        val mark = runNumber?.let { "run-$it" } ?: "run-" + run.id.value.takeLast(8)
        return safeName("$mark ${definition.name}", mark)
    }

    /** The extension for [artifact]'s text, from its media type. Text with no better word is markdown. */
    fun extension(artifact: ArtifactRef): String {
        val type = artifact.mediaType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            "markdown" in type -> "md"
            "json" in type -> "json"
            "html" in type -> "html"
            "xml" in type -> "xml"
            "csv" in type -> "csv"
            "yaml" in type || "yml" in type -> "yaml"
            "diff" in type || "patch" in type -> "diff"
            type == "text/plain" -> "txt"
            else -> "md"
        }
    }

    /** What is written for [artifact]: its text, else a note naming where it lives (a link, a pull request). Null with neither. */
    fun contentOf(artifact: ArtifactRef): String? =
        artifact.textContent?.takeIf(String::isNotBlank)
            ?: artifact.uri?.takeIf(String::isNotBlank)?.let { link ->
                buildString {
                    append("# ").append(artifact.label).append("\n\n")
                    append(artifact.kind.name).append(": ").append(link).append('\n')
                }
            }

    /**
     * The files for [entries] of [run]: "<creature> - <label>.<ext>" in the run's own folder. A name that repeats within the run gets a
     * number. Entries with nothing to write are left out.
     */
    fun plan(definition: WorkflowDefinition, run: WorkflowRun, runNumber: Int?, entries: List<ArtifactEntry>): List<PlannedArtifactFile> {
        val folder = runFolder(definition, run, runNumber)
        val taken = mutableSetOf<String>()
        return entries.mapNotNull { entry ->
            val content = contentOf(entry.artifact) ?: return@mapNotNull null
            val base = safeName("${entry.creature} - ${entry.artifact.label}", "artifact")
            val extension = extension(entry.artifact)
            val withExtension = if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
            var name = withExtension
            var number = 2
            while (!taken.add(name.lowercase())) {
                name = withExtension.removeSuffix(".$extension") + " ($number).$extension"
                number += 1
            }
            PlannedArtifactFile(entry.artifact.id.value, folder, name, content)
        }
    }
}

/** How a save went: files written, files that could not be, and whether there was nowhere to write. */
data class ArtifactSaveResult(val written: Int, val failed: Int, val noFolder: Boolean = false) {
    val ok: Boolean get() = written > 0 && failed == 0
}

internal object ArtifactSaver {
    private val lock = Mutex()

    /**
     * Writes [entries] of [run] into [projectId]'s folder. [onlyUnsaved] skips what was already written (saving as they are made);
     * a keeper's own Save writes again, into a numbered file, rather than quietly doing nothing.
     */
    suspend fun save(
        projectId: String,
        definition: WorkflowDefinition,
        run: WorkflowRun,
        runNumber: Int?,
        entries: List<ArtifactEntry>,
        onlyUnsaved: Boolean = false,
    ): ArtifactSaveResult = lock.withLock {
        val service = ArtifactFolderHost.service
        val folder = ArtifactFolderSettings.folder(projectId)
        if (service == null || folder == null) return ArtifactSaveResult(0, 0, noFolder = true)
        val already = if (onlyUnsaved) ArtifactFolderSettings.saved(run.id.value) else emptySet()
        // The plan names the whole run's files so a number is stable, then only what is wanted is written.
        val wanted = entries.filter { it.artifact.id.value !in already }.map { it.artifact.id.value }.toSet()
        val files = ArtifactFilePlanner.plan(definition, run, runNumber, entries).filter { it.artifactId in wanted }
        var written = 0
        var failed = 0
        val done = mutableListOf<String>()
        files.forEach { file ->
            val name = runCatching { service.write(folder.ref, file.subfolder, file.fileName, file.content) }.getOrNull()
            if (name != null) {
                written += 1
                done += file.artifactId
            } else {
                failed += 1
            }
        }
        ArtifactFolderSettings.markSaved(run.id.value, done)
        ArtifactSaveResult(written, failed)
    }
}
