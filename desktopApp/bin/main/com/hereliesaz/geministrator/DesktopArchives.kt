package com.hereliesaz.geministrator

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Extracts a .tar.gz into [destination], refusing any entry that would land outside it. */
internal fun extractTarGzSafely(archive: File, destination: File) {
    val root = destination.canonicalFile
    TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            val output = File(root, entry.name).canonicalFile
            check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                "Unsafe archive entry: ${entry.name}"
            }
            when {
                entry.isDirectory -> output.mkdirs()
                entry.isFile -> {
                    output.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(output)).use { tar.copyTo(it) }
                }
            }
        }
    }
}
