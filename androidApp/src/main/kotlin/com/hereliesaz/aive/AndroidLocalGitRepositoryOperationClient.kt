package com.hereliesaz.aive

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.workflow.ExternalExecutionRun
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import com.hereliesaz.geministrator.workflow.LocalGitOperation
import com.hereliesaz.geministrator.workflow.RepositoryOperationClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Local Git operations on Android, through JGit, over repositories imported into app storage
 * ([AndroidLocalRepositoryStore]). Same grammar as desktop ([LocalGitOperation]); differences:
 * - `worktree-branch` makes a local clone on the new branch at the same `<repo>-worktrees/<branch>`
 *   place (JGit has no linked worktrees); the import's checkout is untouched either way.
 * - `fetch`, `pull`, `push` and `push-set-upstream` are refused: an imported copy has no
 *   credentials for its remotes. Export the branch and push from wherever the folder lives.
 * - `export[:<branch>]` writes the branch's committed files back to the picked folder.
 * A run is recorded once per operation identity; asking again returns the first result.
 */
internal class AndroidLocalGitRepositoryOperationClient(
    private val export: suspend (root: String, files: Map<String, ByteArray>) -> Unit,
) : RepositoryOperationClient {
    private val runs = ConcurrentHashMap<String, ExternalExecutionRun>()

    override fun supports(project: Project): Boolean = project.repository?.source == RepositorySource.Local

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun =
        start(project, operation, "legacy:${project.id.value}:$operation")

    override suspend fun start(project: Project, operation: String, operationIdentity: String): ExternalExecutionRun {
        val repository = requireNotNull(project.repository) { "Repository operation requires a linked project repository" }
        require(repository.source == RepositorySource.Local) { "Local Git executor requires a Local Git repository" }
        val root = File(requireNotNull(repository.localPath?.trim()?.takeIf(String::isNotEmpty)) { "Local Git repository path is missing" })
        val parsed = LocalGitOperation.parse(operation)
        val runId = "local-git-" + MessageDigest.getInstance("SHA-256")
            .digest("$operationIdentity\n$operation".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        runs[runId]?.let { return it }

        val outcome = runCatching { withContext(Dispatchers.IO) { run(root, parsed) } }
        val worktree = (parsed as? LocalGitOperation.WorktreeBranch)?.worktreeDirectory(root.path)?.let(::File)
        val state = runCatching { withContext(Dispatchers.IO) { inspect(worktree ?: root) } }.getOrNull()
        val succeeded = outcome.isSuccess
        val output = outcome.fold({ it }, { it.message ?: it::class.simpleName.orEmpty() })
        val execution = ExternalExecutionRun(
            id = runId,
            status = if (succeeded) ExternalExecutionStatus.Completed else ExternalExecutionStatus.Failed,
            progress = if (succeeded) 1f else null,
            message = "Local Git · ${parsed.label}" + (state?.branch?.let { " · $it" } ?: "") +
                (state?.headCommit?.let { " @ ${it.take(12)}" } ?: ""),
            artifacts = listOf(
                ArtifactRef(
                    id = ArtifactId("$runId:git-output"),
                    kind = ArtifactKind.CommandOutput,
                    taskRunId = TaskRunId("$runId:external"),
                    label = parsed.label,
                    textContent = output.ifBlank { if (succeeded) "Git operation completed successfully." else "Git operation failed." },
                    mediaType = "text/plain",
                    metadata = buildMap {
                        put("repositorySource", RepositorySource.Local.name)
                        put("operation", operation)
                        put("exitCode", if (succeeded) "0" else "1")
                        worktree?.let { put("worktreePath", it.absolutePath) }
                        state?.branch?.let { put("branch", it) }
                        state?.headCommit?.let { put("headCommit", it) }
                        state?.dirtyFileCount?.let { put("dirtyFileCount", it.toString()) }
                    },
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            ),
        )
        runs[runId] = execution
        return execution
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        runs[runId] ?: ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Failed,
            message = "Local Git operation $runId has no execution record in this session",
        )

    /** Performs [operation] on [root]; returns its text output or throws with the reason. */
    internal suspend fun run(root: File, operation: LocalGitOperation): String {
        if (operation is LocalGitOperation.Export) return exportBranch(root, operation.branch)
        return Git.open(root).use { git ->
            when (operation) {
                LocalGitOperation.Status -> git.status().call().let { status ->
                    buildString {
                        appendLine("## ${git.repository.branch}")
                        status.added.forEach { appendLine("A  $it") }
                        status.changed.forEach { appendLine("M  $it") }
                        status.modified.forEach { appendLine(" M $it") }
                        status.removed.forEach { appendLine("D  $it") }
                        status.missing.forEach { appendLine(" D $it") }
                        status.untracked.forEach { appendLine("?? $it") }
                    }
                }
                LocalGitOperation.Fetch, LocalGitOperation.Pull, LocalGitOperation.Push, is LocalGitOperation.PushSetUpstream ->
                    error("${operation.label} needs the remote's credentials, which an imported repository on Android does not have. Export the branch and push from the original folder.")
                LocalGitOperation.AddAll -> {
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call()
                    "Staged all changes."
                }
                is LocalGitOperation.Checkout -> git.checkout().setName(operation.ref).call().let { "Checked out ${operation.ref}." }
                is LocalGitOperation.CreateBranch ->
                    git.checkout().setCreateBranch(true).setName(operation.branch).call().let { "Created and switched to ${operation.branch}." }
                is LocalGitOperation.WorktreeBranch -> {
                    val directory = File(operation.worktreeDirectory(root.path))
                    require(!directory.exists()) { "Worktree ${directory.path} already exists" }
                    val base = git.repository.resolve("${operation.base ?: "HEAD"}^{commit}")
                        ?: error("Unknown base ${operation.base ?: "HEAD"}")
                    Git.cloneRepository().setURI(root.toURI().toString()).setDirectory(directory).setNoCheckout(true).call().use { clone ->
                        clone.checkout().setCreateBranch(true).setName(operation.branch).setStartPoint(base.name).call()
                    }
                    "Created ${operation.branch} from ${base.name.take(12)} in ${directory.path}."
                }
                is LocalGitOperation.Commit -> git.commit()
                    .setMessage(operation.message)
                    .setAuthor(AndroidLocalRepositoryStore.IDENTITY)
                    .setCommitter(AndroidLocalRepositoryStore.IDENTITY)
                    .call()
                    .let { "Committed ${it.name.take(12)}: ${operation.message}" }
                is LocalGitOperation.EntireTrail, is LocalGitOperation.EntireMirror ->
                    error("${operation.label} runs through the entire command, which only the desktop app has.")
                is LocalGitOperation.Export -> error("unreachable")
            }
        }
    }

    /** The committed files of [branch] (current branch when null), from the import or its worktree. */
    private suspend fun exportBranch(root: File, branch: String?): String {
        val sources = listOfNotNull(
            root,
            branch?.let { File(LocalGitOperation.WorktreeBranch(it, null).worktreeDirectory(root.path)) }?.takeIf { File(it, ".git").exists() },
        )
        val files = sources.firstNotNullOfOrNull { source ->
            Git.open(source).use { git ->
                val commit = git.repository.resolve("${branch ?: Constants.HEAD}^{commit}") ?: return@use null
                committedFiles(git, commit)
            }
        } ?: error("Branch ${branch ?: "HEAD"} not found")
        export(root.path, files)
        return "Exported ${files.size} files from ${branch ?: "the current branch"} to the picked folder."
    }

    private fun committedFiles(git: Git, commit: ObjectId): Map<String, ByteArray> {
        val repository = git.repository
        val tree = RevWalk(repository).use { it.parseCommit(commit).tree }
        return TreeWalk(repository).use { walk ->
            walk.addTree(tree)
            walk.isRecursive = true
            buildMap { while (walk.next()) put(walk.pathString, repository.open(walk.getObjectId(0)).bytes) }
        }
    }

    private data class State(val branch: String?, val headCommit: String?, val dirtyFileCount: Int?)

    private fun inspect(directory: File): State = Git.open(directory).use { git ->
        val status = git.status().call()
        State(
            branch = git.repository.branch,
            headCommit = git.repository.resolve(Constants.HEAD)?.name,
            dirtyFileCount = (status.uncommittedChanges + status.untracked).size,
        )
    }
}
