package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.MemoryWeb
import com.hereliesaz.geministrator.memory.MemoryWebSource

/*
 * The Memory Web written out as an Obsidian vault (docs/Memory-Web.md): one markdown file per memory, with front matter and
 * `[[links]]`, into a folder the keeper picks (the artifact-folder service, with a folder of its own). Each export goes in a
 * subfolder of its own, so a second export never numbers a file and breaks the links of the first. A read-only copy: the bank stays
 * the source of truth, and Obsidian's edits do not flow back.
 */
internal object MemoryWebExport {
    /** The key the Memory Web's folder is kept under in [ArtifactFolderSettings] (it belongs to no project). */
    const val FOLDER_KEY = "memory-web"

    /** [epochMillis] as "2026-11-20 18-05" (UTC): the part of an export's folder name that tells one export from the next. */
    fun stampOf(epochMillis: Long): String {
        val days = kotlin.math.floor(epochMillis / 86_400_000.0).toLong()
        val minutes = (epochMillis - days * 86_400_000L) / 60_000L
        // Civil date from a day count (Howard Hinnant's algorithm), so no date library is needed.
        val z = days + 719_468L
        val era = kotlin.math.floor(z / 146_097.0).toLong()
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val day = doy - (153L * mp + 2L) / 5L + 1L
        val month = if (mp < 10L) mp + 3L else mp - 9L
        val year = yoe + era * 400L + if (month <= 2L) 1L else 0L
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "$year-${two(month)}-${two(day)} ${two(minutes / 60L)}-${two(minutes % 60L)}"
    }

    /** "Memory Web 2026-11-20 18-05": the subfolder of one export. */
    fun subfolderFor(stamp: String): String = ArtifactFilePlanner.safeName("Memory Web $stamp", "Memory Web")

    /** Writes every note of [source] as `<title>.md` in [subfolder] of the chosen folder; says how many were and were not written. */
    suspend fun export(source: MemoryWebSource, subfolder: String): ArtifactSaveResult {
        val service = ArtifactFolderHost.service
        val folder = ArtifactFolderSettings.folder(FOLDER_KEY)
        if (service == null || folder == null) return ArtifactSaveResult(0, 0, noFolder = true)
        var written = 0
        var failed = 0
        MemoryWeb.notes(source).forEach { note ->
            val name = runCatching { service.write(folder.ref, subfolder, "${note.node.title}.md", MemoryWeb.markdown(note)) }.getOrNull()
            if (name != null) written += 1 else failed += 1
        }
        return ArtifactSaveResult(written, failed)
    }
}
