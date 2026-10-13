package com.hereliesaz.geministrator.retrieval

import java.io.File
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
            .onEnter { dir -> dir == base || dir.name !in setOf(".git", ".gradle", ".idea", "build", "node_modules") }
            .filter(File::isFile)
            .map { LocalRepositoryFile(it.relativeTo(base).invariantSeparatorsPath, it.length()) }
            .toList()
    }

    override suspend fun readDisk(root: String, path: String): String? = withContext(Dispatchers.IO) {
        runCatching { File(root, path).readText() }.getOrNull()
    }

    private suspend fun git(root: String, vararg args: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(listOf("git", "-C", root) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        }.getOrNull()
    }
}
