package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The keeper-chosen artifact folder on the desktop (docs/Artifact-Folders.md): a plain folder on disk. [write] never leaves the
 * folder (every name is a plain file name, checked again against the resolved path) and never overwrites a file.
 */
internal class DesktopArtifactFolderService : ArtifactFolderService {
    override suspend fun chooseFolder(): ArtifactFolderRef? = withContext(Dispatchers.IO) {
        var chosen: File? = null
        // Swing's dialog belongs on its own thread; this waits for the keeper's answer.
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                dialogTitle = "Save artifacts to"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chosen = chooser.selectedFile
        }
        chosen?.takeIf { it.isDirectory }?.let { ArtifactFolderRef(it.absolutePath, it.name.ifBlank { it.absolutePath }) }
    }

    override suspend fun write(folder: String, subfolder: String, fileName: String, content: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(folder).canonicalFile
            check(root.isDirectory) { "The folder is gone" }
            val directory = File(root, subfolder).canonicalFile
            check(directory.toPath().startsWith(root.toPath())) { "Outside the folder" }
            directory.mkdirs()
            val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val stem = fileName.removeSuffix(extension)
            var candidate = fileName
            var number = 2
            while (true) {
                val target = File(directory, candidate).canonicalFile
                check(target.parentFile == directory) { "Outside the folder" }
                try {
                    // CREATE_NEW: a name that exists is never overwritten, whatever raced us to it.
                    Files.write(target.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    return@runCatching candidate
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    candidate = "$stem ($number)$extension"
                    number += 1
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()
    }
}
