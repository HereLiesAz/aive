package com.hereliesaz.geministrator.retrieval

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [LocalRepositoryReader] over the git command line (desktop). Never runs repository hooks. */
object GitCliRepositoryReader : LocalRepositoryReader {
    override suspend fun resolveCommit(root: String, ref: String): String? =
        git(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")?.trim()?.takeIf(String::isNotEmpty)

    override suspend fun committedFiles(root: String, commit: String): List<LocalRepositoryFile>? =
        // "<mode> <type> <sha> <size>\t<path>" per entry, NUL-separated.
        git(root, "ls-tree", "-r", "-l", "-z", commit)?.split('\u0000')?.mapNotNull { entry ->
            val (meta, path) = entry.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val fields = meta.split(' ').filter(String::isNotEmpty)
            val size = fields.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
            if (fields.getOrNull(1) == "blob") LocalRepositoryFile(path, size, blob = fields[2]) else null
        }

    override suspend fun readBlob(root: String, blob: String): String? = git(root, "cat-file", "blob", blob)

    override suspend fun diskFiles(root: String): List<LocalRepositoryFile> = withContext(Dispatchers.IO) {
        val base = File(root)
        base.walkTopDown()
            .onEnter { dir ->
                dir == base || (dir.name !in setOf(".git", ".gradle", ".idea", "build", "node_modules") &&
                    !Files.isSymbolicLink(dir.toPath()))
            }
            // A repository with no commit is read from disk: never follow a link out of the root.
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { LocalRepositoryFile(it.relativeTo(base).invariantSeparatorsPath, it.length()) }
            .toList()
    }

    override suspend fun readDisk(root: String, path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val base = File(root).toPath().toRealPath()
            val target = File(root, path).toPath()
            val real = target.toRealPath()
            // Refuse the file itself or any directory above it being a link that leaves the root.
            if (Files.isSymbolicLink(target) || !real.startsWith(base)) null else real.toFile().readText()
        }.getOrNull()
    }

    private suspend fun git(root: String, vararg args: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Output goes to a file, not a pipe: reading a pipe to EOF would block before the
            // timeout is ever evaluated, and a hung git would never be stopped.
            val outputFile = Files.createTempFile("aive-git", ".out").toFile()
            try {
                val process = ProcessBuilder(listOf("git", "-C", root) + args)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(outputFile)
                    .start()
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() == 0) {
                    outputFile.readText()
                } else {
                    null
                }
            } finally {
                outputFile.delete()
            }
        }.getOrNull()
    }
}
