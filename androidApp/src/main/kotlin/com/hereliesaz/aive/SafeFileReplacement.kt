package com.hereliesaz.aive

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [content] to a synced temporary file and replaces [destination] without deleting the last
 * good copy first.
 *
 * The preferred path is an atomic replace. Filesystems that do not support atomic moves fall back
 * to a reversible swap: the old destination is moved to a backup, the new file is installed, and
 * the backup is restored if installation fails.
 */
internal fun writeTextReplacingSafely(
    destination: File,
    content: String,
) {
    val directory = requireNotNull(destination.parentFile) { "Destination must have a parent directory" }
    check(directory.exists() || directory.mkdirs()) { "Could not create ${directory.absolutePath}" }
    val temporary = File.createTempFile(destination.name + ".", ".tmp", directory)
    try {
        FileOutputStream(temporary).use { output ->
            output.writer(Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
                output.fd.sync()
            }
        }
        replaceFileSafely(temporary, destination)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

internal fun replaceFileSafely(
    temporary: File,
    destination: File,
    atomicReplace: (File, File) -> Unit = { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
    replaceMove: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    },
) {
    try {
        atomicReplace(temporary, destination)
        return
    } catch (_: AtomicMoveNotSupportedException) {
        // Fall through to a rollback-safe non-atomic swap.
    }

    if (!destination.exists()) {
        replaceMove(temporary, destination)
        return
    }

    val backup = File(destination.parentFile, destination.name + ".bak")
    if (backup.exists() && !backup.delete()) {
        error("Could not clear stale backup ${backup.name}")
    }

    replaceMove(destination, backup)
    try {
        replaceMove(temporary, destination)
    } catch (installFailure: Throwable) {
        try {
            replaceMove(backup, destination)
        } catch (restoreFailure: Throwable) {
            installFailure.addSuppressed(restoreFailure)
        }
        throw installFailure
    }

    if (backup.exists() && !backup.delete()) {
        // The new destination is already installed; leaving a stale backup is safer than deleting
        // or rolling back the successful save.
        backup.deleteOnExit()
    }
}
