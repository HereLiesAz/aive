package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.sanitizeGitEnvironment
import com.hereliesaz.geministrator.workflow.RepositoryFileProposal
import com.hereliesaz.geministrator.workflow.RepositoryProposedFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
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

    /**
     * Inspect each ancestor and target in the immutable HEAD tree using literal pathspecs.
     * Exact blob bytes are decoded strictly; a failed Git command is never a missing file.
     */
    override suspend fun snapshot(root: String, paths: List<String>): RepositoryFileProposal {
        RepositoryFileProposal.validateSnapshotPaths(paths)
        val base = requireNotNull(git(root, "rev-parse", "--verify", "HEAD^{commit}", maxBytes = 128)) {
            "Local snapshot requires a committed HEAD"
        }.trim()
        require(base.matches(Regex("[0-9a-f]{40}"))) { "Invalid local base commit SHA" }
        val entries = mutableMapOf<String, List<String>?>()
        suspend fun entry(path: String): List<String>? {
            if (entries.containsKey(path)) return entries[path]
            val raw = requireNotNull(git(root, "ls-tree", "--full-tree", "-l", "-z", base, "--", path, maxBytes = 4096)) {
                "Unable to read local snapshot tree"
            }
            val result = if (raw.isEmpty()) null else {
                require(raw.endsWith('\u0000') && raw.count { it == '\u0000' } == 1) { "Ambiguous local snapshot tree entry" }
                val fields = raw.removeSuffix("\u0000").split('\t', limit = 2)
                require(fields.size == 2 && fields[1] == path) { "Unexpected local snapshot tree path" }
                fields[0].split(' ').filter(String::isNotEmpty).also {
                    require(it.size == 4 && it[2].matches(Regex("[0-9a-f]{40}"))) { "Invalid local snapshot tree entry" }
                }
            }
            entries[path] = result
            return result
        }
        var remaining = RepositoryFileProposal.MAX_TOTAL_BYTES
        val files = paths.map { path ->
            val parts = path.split('/')
            var absent = false
            for (length in 1 until parts.size) {
                val ancestor = entry(parts.take(length).joinToString("/"))
                if (ancestor == null) { absent = true; break }
                require(ancestor[0] == "040000" && ancestor[1] == "tree") { "A file path crosses a non-directory" }
            }
            val target = if (absent) null else entry(path)
            if (target == null) RepositoryProposedFile(path, null, "") else {
                require(target[0] in setOf("100644", "100755") && target[1] == "blob") {
                    "Snapshot target is not a regular file"
                }
                val size = requireNotNull(target[3].toIntOrNull()) { "Invalid local blob size" }
                require(size in 0..minOf(remaining, RepositoryFileProposal.MAX_FILE_BYTES)) { "Local snapshot exceeds content limit" }
                val content = requireNotNull(git(root, "cat-file", "blob", target[2], maxBytes = size)) {
                    "Unable to read exact local snapshot blob"
                }
                require(content.encodeToByteArray().size == size) { "Local snapshot blob size changed" }
                remaining -= size
                RepositoryProposedFile(path, target[2], content)
            }
        }
        return RepositoryFileProposal(base, files).also { it.validate() }
    }

    /**
     * Git plumbing writes only objects and a private temporary index. A single ref transaction
     * verifies the original HEAD and creates the destination, refusing even a pre-existing branch
     * that points at the same commit. Neither checkout nor hooks run.
     */
    override suspend fun commitProposal(root: String, branch: String, proposal: RepositoryFileProposal): String {
        RepositoryFileProposal.requireDedicatedBranch(branch)
        RepositoryFileProposal.validateSnapshotPaths(proposal.files.map { it.path })
        proposal.requireMatchingSnapshot(snapshot(root, proposal.files.map { it.path }))
        val temporary = withContext(Dispatchers.IO) { Files.createTempDirectory("aive-host-index-").toFile() }
        try {
            val index = File(temporary, "index")
            val hooks = File(temporary, "hooks").also { check(it.mkdir()) }
            suspend fun command(vararg args: String, input: ByteArray? = null): String =
                requireNotNull(git(root, *args, maxBytes = 4096, input = input, index = index, hooks = hooks)) {
                    "Host Git operation failed: ${args.firstOrNull().orEmpty()}"
                }
            command("read-tree", proposal.baseCommit)
            for (file in proposal.files) {
                val mode = if (file.expectedBlob == null) "100644" else {
                    val entry = command("ls-tree", "--full-tree", "-z", proposal.baseCommit, "--", file.path)
                    val prefix = entry.substringBefore('\t').split(' ')
                    require(prefix.size == 3 && prefix[2] == file.expectedBlob && prefix[0] in setOf("100644", "100755")) {
                        "Approved file mode or blob changed"
                    }
                    prefix[0]
                }
                val blob = command("hash-object", "-w", "--stdin", input = file.content.encodeToByteArray()).trim()
                require(blob.matches(Regex("[0-9a-f]{40}"))) { "Invalid local blob SHA" }
                command("update-index", "--add", "--cacheinfo", "$mode,$blob,${file.path}")
            }
            val tree = command("write-tree").trim()
            require(tree.matches(Regex("[0-9a-f]{40}"))) { "Invalid local tree SHA" }
            val commit = command("commit-tree", tree, "-p", proposal.baseCommit,
                input = "Apply approved Aive file proposal\n".encodeToByteArray()).trim()
            require(commit.matches(Regex("[0-9a-f]{40}"))) { "Invalid local commit SHA" }
            command("update-ref", "--stdin", input =
                "verify HEAD ${proposal.baseCommit}\ncreate refs/heads/$branch $commit\n".encodeToByteArray())
            return commit
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { temporary.deleteRecursively() }
        }
    }

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

    private suspend fun git(
        root: String,
        vararg args: String,
        maxBytes: Int? = null,
        input: ByteArray? = null,
        index: File? = null,
        hooks: File? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Output goes to a file, not a pipe: reading a pipe to EOF would block before the
            // timeout is ever evaluated, and a hung git would never be stopped.
            val outputFile = Files.createTempFile("aive-git", ".out").toFile()
            var inputFile: File? = null
            try {
                val flags = buildList {
                    if (maxBytes != null) addAll(listOf("--no-replace-objects", "--literal-pathspecs"))
                    if (hooks != null) addAll(listOf("-c", "core.hooksPath=${hooks.absolutePath}",
                        "-c", "core.fsmonitor=false", "-c", "core.splitIndex=false", "-c", "commit.gpgsign=false",
                        "-c", "user.name=The Aive", "-c", "user.email=aive@localhost"))
                }
                val builder = ProcessBuilder(listOf("git", "-C", root) + flags + args)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(outputFile)
                sanitizeGitEnvironment(builder)
                index?.let { builder.environment()["GIT_INDEX_FILE"] = it.absolutePath }
                if (input != null) {
                    inputFile = Files.createTempFile("aive-git", ".in").toFile()
                    inputFile.writeBytes(input)
                    builder.redirectInput(inputFile)
                }
                val process = builder.start()
                try {
                    process.outputStream.close()
                    if (!runInterruptible { process.waitFor(30, TimeUnit.SECONDS) }) {
                        null
                    } else if (process.exitValue() == 0) {
                        if (maxBytes == null) outputFile.readText() else {
                            require(outputFile.length() <= maxBytes.toLong()) { "Git snapshot output exceeds its bound" }
                            outputFile.readBytes().decodeToString(throwOnInvalidSequence = true)
                        }
                    } else null
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            } finally {
                outputFile.delete()
                inputFile?.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
