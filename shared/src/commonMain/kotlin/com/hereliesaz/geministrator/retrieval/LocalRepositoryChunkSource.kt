package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder

/** A file in a local repository: committed ([blob] set) or read from disk ([blob] null). */
data class LocalRepositoryFile(val path: String, val size: Long, val blob: String? = null)

/**
 * Platform access to a local repository: the git command line on desktop, JGit on Android
 * (over the copy imported into app storage). Every method returns null when the folder is not a
 * Git repository or the object does not exist, never throws for that.
 */
interface LocalRepositoryReader {
    /** The commit [ref] names in the repository at [root]. */
    suspend fun resolveCommit(root: String, ref: String): String?

    /** Every file in [commit]'s tree. */
    suspend fun committedFiles(root: String, commit: String): List<LocalRepositoryFile>?

    suspend fun readBlob(root: String, blob: String): String?

    /** Files on disk under [root], for a folder that is not a Git repository. */
    suspend fun diskFiles(root: String): List<LocalRepositoryFile>

    suspend fun readDisk(root: String, path: String): String?
}

/**
 * Chunks of a local repository's files, for requests whose repository is a local folder, on
 * desktop and Android alike.
 *
 * Files are read at the commit an agent's worktree branch starts from, not from the working copy:
 * the latest `headCommit` an upstream artifact carries (a previous task's branch), else HEAD, as
 * the local workspace agent resolves it. Uncommitted edits are therefore never shown as if they
 * were the code being changed. Selection mirrors [GitHubRepositoryChunkSource]: the [maxFiles] text
 * files whose paths share the most words with the request (READMEs always qualify); each file's
 * chunks are cached by blob SHA. A folder that is not a Git repository is read from disk.
 */
class LocalRepositoryChunkSource(
    private val reader: LocalRepositoryReader,
    private val maxFiles: Int = 24,
    private val maxFileBytes: Long = 48_000,
    private val maxCachedFiles: Int = 512,
) : ContextChunkSource {
    private val trees = HashMap<String, List<LocalRepositoryFile>>()
    private val files = LinkedHashMap<String, List<String>>()

    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> {
        val repository = request.repository?.takeIf { it.source == RepositorySource.Local } ?: return emptyList()
        val root = repository.localPath?.takeIf(String::isNotBlank) ?: return emptyList()
        val queryWords = BuiltInTextEmbedder.tokens(request.retrievalQuery()).toSet()
        val upstream = request.contextArtifacts.mapNotNull { it.metadata["headCommit"]?.takeIf(String::isNotBlank) }.lastOrNull()
        val commit = listOfNotNull(upstream, "HEAD").firstNotNullOfOrNull { reader.resolveCommit(root, it) }
        val tree = if (commit != null) {
            trees.getOrPut("$root@$commit") { reader.committedFiles(root, commit).orEmpty() }
        } else {
            reader.diskFiles(root)
        }
        return tree
            .filter { it.size <= maxFileBytes && GitHubRepositoryChunkSource.isReadable(it.path) }
            .map { file -> file to GitHubRepositoryChunkSource.pathScore(file.path, queryWords) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<LocalRepositoryFile, Int>> { it.second }.thenBy { it.first.path })
            .take(maxFiles)
            .flatMap { (file, _) ->
                fileChunks(root, file).mapIndexed { i, text ->
                    TextChunk(
                        id = "local:$root@${commit ?: "disk"}:${file.path}#$i",
                        label = "Repository file ${file.path}" + if (i > 0) " (part ${i + 1})" else "",
                        text = text,
                    )
                }
            }
    }

    private suspend fun fileChunks(root: String, file: LocalRepositoryFile): List<String> {
        val key = file.blob ?: "$root/${file.path}:${file.size}"
        files[key]?.let { return it }
        val text = (if (file.blob != null) reader.readBlob(root, file.blob) else reader.readDisk(root, file.path)).orEmpty()
        val chunks = if ('\u0000' in text) emptyList() else chunkText(text)
        files[key] = chunks
        while (files.size > maxCachedFiles) files.remove(files.keys.first())
        return chunks
    }
}
