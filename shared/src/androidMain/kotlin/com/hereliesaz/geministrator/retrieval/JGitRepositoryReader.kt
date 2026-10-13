package com.hereliesaz.geministrator.retrieval

import java.io.File
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

    private suspend fun <T> withRepository(root: String, block: (Repository) -> T?): T? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = FileRepositoryBuilder().findGitDir(File(root)).setMustExist(true)
            if (builder.gitDir == null) return@runCatching null
            builder.build().use(block)
        }.getOrNull()
    }
}
