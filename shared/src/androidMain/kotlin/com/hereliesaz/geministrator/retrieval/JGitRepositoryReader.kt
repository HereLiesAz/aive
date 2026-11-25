package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.workflow.RepositoryFileProposal
import com.hereliesaz.geministrator.workflow.RepositoryProposedFile
import java.io.File
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * [LocalRepositoryReader] over JGit (Android, where there is no git binary). Repositories are the
 * copies imported into app storage, so reads never touch the folder the user picked.
 */
object JGitRepositoryReader : LocalRepositoryReader {
    override suspend fun resolveCommit(root: String, ref: String): String? = withRepository(root) { repository ->
        repository.resolve("$ref^{commit}")?.name
    }

    override suspend fun committedFiles(root: String, commit: String): List<LocalRepositoryFile>? =
        withRepository(root) { repository ->
            RevWalk(repository).use { walk ->
                val tree = walk.parseCommit(ObjectId.fromString(commit)).tree
                TreeWalk(repository).use { treeWalk ->
                    treeWalk.addTree(tree)
                    treeWalk.isRecursive = true
                    buildList {
                        while (treeWalk.next()) {
                            val id = treeWalk.getObjectId(0)
                            val size = repository.newObjectReader().use { it.getObjectSize(id, Constants.OBJ_BLOB) }
                            add(LocalRepositoryFile(treeWalk.pathString, size, blob = id.name))
                        }
                    }
                }
            }
        }

    override suspend fun readBlob(root: String, blob: String): String? = withRepository(root) { repository ->
        String(repository.open(ObjectId.fromString(blob), Constants.OBJ_BLOB).bytes, Charsets.UTF_8)
    }

    /** Read only committed regular blobs in the imported repository, never its working directory. */
    override suspend fun snapshot(root: String, paths: List<String>): RepositoryFileProposal = withContext(Dispatchers.IO) {
        RepositoryFileProposal.validateSnapshotPaths(paths)
        val builder = FileRepositoryBuilder().findGitDir(File(root)).setMustExist(true)
        require(builder.gitDir != null) { "Local snapshot requires a Git repository" }
        builder.build().use { repository ->
            val base = requireNotNull(repository.resolve("HEAD^{commit}")) { "Local snapshot requires a committed HEAD" }
            RevWalk(repository).use { rev ->
                val tree = rev.parseCommit(base).tree
                var remaining = RepositoryFileProposal.MAX_TOTAL_BYTES
                val files = paths.map { path ->
                    val parts = path.split('/')
                    var absent = false
                    for (length in 1 until parts.size) {
                        val ancestor = TreeWalk.forPath(repository, parts.take(length).joinToString("/"), tree)
                        if (ancestor == null) { absent = true; break }
                        ancestor.use {
                            require(it.getFileMode(0) == FileMode.TREE) { "A file path crosses a non-directory" }
                        }
                    }
                    val target = if (absent) null else TreeWalk.forPath(repository, path, tree)
                    if (target == null) RepositoryProposedFile(path, null, "") else target.use {
                        require(it.getFileMode(0) in setOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) {
                            "Snapshot target is not a regular file"
                        }
                        val blob = it.getObjectId(0)
                        val loader = repository.open(blob, Constants.OBJ_BLOB)
                        val limit = minOf(remaining, RepositoryFileProposal.MAX_FILE_BYTES)
                        require(loader.size <= limit.toLong()) { "Local snapshot exceeds content limit" }
                        val bytes = loader.getBytes(limit)
                        remaining -= bytes.size
                        RepositoryProposedFile(path, blob.name, bytes.decodeToString(throwOnInvalidSequence = true))
                    }
                }
                RepositoryFileProposal(base.name, files).also { it.validate() }
            }
        }
    }

    /**
     * Build a new commit from the approved immutable tree with an in-memory index. No working files
     * are materialized. A zero expected old object ID makes the final branch update create-only.
     */
    override suspend fun commitProposal(root: String, branch: String, proposal: RepositoryFileProposal): String =
        withContext(Dispatchers.IO) {
            RepositoryFileProposal.requireDedicatedBranch(branch)
            RepositoryFileProposal.validateSnapshotPaths(proposal.files.map { it.path })
            proposal.requireMatchingSnapshot(snapshot(root, proposal.files.map { it.path }))
            val builder = FileRepositoryBuilder().findGitDir(File(root)).setMustExist(true)
            require(builder.gitDir != null) { "Local commit requires a Git repository" }
            builder.build().use { repository ->
                val base = ObjectId.fromString(proposal.baseCommit)
                val ref = "refs/heads/$branch"
                require(repository.resolve("HEAD^{commit}") == base) { "Linked base changed; regenerate and approve the proposal" }
                require(repository.exactRef(ref) == null) { "Dedicated branch already exists" }
                RevWalk(repository).use { rev ->
                    val tree = rev.parseCommit(base).tree
                    repository.newObjectReader().use { reader ->
                        val cache = DirCache.read(reader, tree)
                        repository.newObjectInserter().use { inserter ->
                            val editor = cache.editor()
                            for (file in proposal.files) {
                                val existing = cache.getEntry(file.path)
                                require(existing?.objectId?.name == file.expectedBlob) { "Approved file blob changed" }
                                val mode = existing?.fileMode ?: FileMode.REGULAR_FILE
                                require(mode in setOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) { "Not a regular file" }
                                val blob = inserter.insert(Constants.OBJ_BLOB, file.content.encodeToByteArray())
                                editor.add(object : DirCacheEditor.PathEdit(file.path) {
                                    override fun apply(entry: DirCacheEntry) {
                                        entry.setFileMode(mode)
                                        entry.setObjectId(blob)
                                    }
                                })
                            }
                            editor.finish()
                            val identity = PersonIdent("The Aive", "aive@localhost")
                            val commit = CommitBuilder().apply {
                                setTreeId(cache.writeTree(inserter))
                                setParentId(base)
                                setAuthor(identity)
                                setCommitter(identity)
                                setMessage("Apply approved Aive file proposal")
                            }
                            val head = inserter.insert(commit)
                            inserter.flush()
                            require(repository.resolve("HEAD^{commit}") == base) { "Linked base changed; regenerate and approve the proposal" }
                            require(repository.exactRef(ref) == null) { "Dedicated branch already exists" }
                            val update = repository.updateRef(ref, true)
                            update.setExpectedOldObjectId(ObjectId.zeroId())
                            update.setNewObjectId(head)
                            check(update.update() == RefUpdate.Result.NEW) { "Unable to create dedicated branch; no existing branch is updated" }
                            head.name
                        }
                    }
                }
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

    private suspend fun <T> withRepository(root: String, block: (Repository) -> T?): T? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = FileRepositoryBuilder().findGitDir(File(root)).setMustExist(true)
            if (builder.gitDir == null) return@runCatching null
            builder.build().use(block)
        }.getOrNull()
    }
}
