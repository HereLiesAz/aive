package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chunks of a local Git repository's files, for requests whose repository is a local folder.
 *
 * Files are read at the commit an agent's worktree branch starts from, not from the working copy:
 * the latest `headCommit` an upstream artifact carries (a previous task's branch), else HEAD, as
 * the local workspace agent resolves it. Uncommitted edits in the user's checkout are therefore
 * never shown as if they were the code being changed. Selection mirrors
 * [GitHubRepositoryChunkSource]: the [maxFiles] text files whose paths share the most words with
 * the request (READMEs always qualify); each file's chunks are cached by blob SHA. A folder that is
 * not a Git repository is read from disk instead.
 */
class LocalRepositoryChunkSource(
    private val maxFiles: Int = 24,
    private val maxFileBytes: Long = 48_000,
    private val maxCachedFiles: Int = 512,
) : ContextChunkSource {
    private val trees = HashMap<String, List<TreeFile>>()
    private val files = LinkedHashMap<String, List<String>>()

    private data class TreeFile(val path: String, val blob: String?)

    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> = withContext(Dispatchers.IO) {
        val repository = request.repository?.takeIf { it.source == RepositorySource.Local } ?: return@withContext emptyList()
        val root = repository.localPath?.let(::File)?.takeIf(File::isDirectory)?.canonicalFile ?: return@withContext emptyList()
        val queryWords = BuiltInTextEmbedder.tokens(request.retrievalQuery()).toSet()
        val commit = baseCommit(root, request)
        val tree = if (commit != null) committedFiles(root, commit) else workingFiles(root)
        tree
            .map { file -> file to GitHubRepositoryChunkSource.pathScore(file.path, queryWords) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<TreeFile, Int>> { it.second }.thenBy { it.first.path })
            .take(maxFiles)
            .flatMap { (file, _) ->
                fileChunks(root, file).mapIndexed { i, text ->
                    TextChunk(
                        id = "local:${root.path}@${commit ?: "worktree"}:${file.path}#$i",
                        label = "Repository file ${file.path}" + if (i > 0) " (part ${i + 1})" else "",
                        text = text,
                    )
                }
            }
    }

    /** The upstream task's branch head when it exists here, else HEAD; null outside Git. */
    private fun baseCommit(root: File, request: AgentTaskRequest): String? {
        val upstream = request.contextArtifacts.mapNotNull { it.metadata["headCommit"]?.takeIf(String::isNotBlank) }.lastOrNull()
        return listOfNotNull(upstream, "HEAD").firstNotNullOfOrNull { ref ->
            git(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    private fun committedFiles(root: File, commit: String): List<TreeFile> = trees.getOrPut("${root.path}@$commit") {
        // "<mode> blob <sha> <size>\t<path>" per entry, NUL-separated.
        git(root, "ls-tree", "-r", "-l", "-z", commit).orEmpty()
            .split('\u0000')
            .mapNotNull { entry ->
                val (meta, path) = entry.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val fields = meta.split(' ').filter(String::isNotEmpty)
                val size = fields.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
                if (fields.getOrNull(1) != "blob" || size > maxFileBytes || !GitHubRepositoryChunkSource.isReadable(path)) null
                else TreeFile(path, fields[2])
            }
    }

    private fun workingFiles(root: File): List<TreeFile> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in setOf(".git", ".gradle", ".idea", "build", "node_modules") }
            .filter { it.isFile && it.length() <= maxFileBytes }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter(GitHubRepositoryChunkSource::isReadable)
            .map { TreeFile(it, blob = null) }
            .toList()

    private fun fileChunks(root: File, file: TreeFile): List<String> {
        val key = file.blob ?: File(root, file.path).let { "${it.path}:${it.length()}:${it.lastModified()}" }
        files[key]?.let { return it }
        val text = if (file.blob != null) git(root, "cat-file", "blob", file.blob).orEmpty()
        else runCatching { File(root, file.path).readText() }.getOrDefault("")
        val chunks = if ('\u0000' in text) emptyList() else chunkText(text)
        files[key] = chunks
        while (files.size > maxCachedFiles) files.remove(files.keys.first())
        return chunks
    }

    /** Standard output of a successful `git` command in [root], or null. Never runs repository hooks. */
    private fun git(root: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
    }.getOrNull()
}
