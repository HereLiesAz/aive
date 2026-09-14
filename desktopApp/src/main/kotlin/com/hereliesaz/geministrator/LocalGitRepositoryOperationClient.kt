package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.workflow.ExternalExecutionRun
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import com.hereliesaz.geministrator.workflow.RepositoryOperationClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class LocalGitRepositoryOperationClient : RepositoryOperationClient {
    private val nextRunId = AtomicLong(1L)
    private val runs = ConcurrentHashMap<String, ExternalExecutionRun>()

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        val repository = requireNotNull(project.repository) {
            "Repository operation requires a linked project repository"
        }
        require(repository.source == RepositorySource.Local) {
            "Local Git executor requires a Local Git repository"
        }
        val path = requireNotNull(repository.localPath)?.trim().orEmpty()
        require(path.isNotEmpty()) { "Local Git repository path is missing" }

        val root = resolveGitRoot(path)
        val parsed = LocalGitOperation.parse(operation)
        val runId = "local-git-${nextRunId.getAndIncrement()}"
        val command = parsed.command(root)
        val result = runGit(command)
        val snapshot = inspect(root)
        val now = System.currentTimeMillis()
        val output = result.output.ifBlank {
            if (result.exitCode == 0) "Git operation completed successfully." else "Git operation failed with no output."
        }
        val artifact = ArtifactRef(
            id = ArtifactId("$runId:git-output"),
            kind = ArtifactKind.CommandOutput,
            taskRunId = TaskRunId("$runId:external"),
            label = parsed.label,
            textContent = output,
            mediaType = "text/plain",
            metadata = buildMap {
                put("repositorySource", RepositorySource.Local.name)
                put("operation", operation)
                put("command", command.joinToString(" "))
                put("exitCode", result.exitCode.toString())
                snapshot.branch?.let { put("branch", it) }
                snapshot.headCommit?.let { put("headCommit", it) }
                put("dirtyFileCount", snapshot.dirtyFileCount.toString())
            },
            createdAtEpochMillis = now,
        )
        val execution = ExternalExecutionRun(
            id = runId,
            status = if (result.exitCode == 0) ExternalExecutionStatus.Completed else ExternalExecutionStatus.Failed,
            artifacts = listOf(artifact),
            progress = if (result.exitCode == 0) 1f else null,
            message = buildString {
                append("Local Git · ")
                append(parsed.label)
                snapshot.branch?.let {
                    append(" · ")
                    append(it)
                }
                snapshot.headCommit?.let {
                    append(" @ ")
                    append(it.take(12))
                }
                if (snapshot.dirtyFileCount > 0) {
                    append(" · ")
                    append(snapshot.dirtyFileCount)
                    append(if (snapshot.dirtyFileCount == 1) " changed file" else " changed files")
                }
            },
        )
        runs[runId] = execution
        return execution
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        runs[runId] ?: ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Failed,
            message = "Local Git operation $runId is no longer available in this process",
        )

    private fun resolveGitRoot(path: String): File {
        val selected = File(path)
        require(selected.isDirectory) { "Local Git path does not exist or is not a directory: $path" }
        val result = runGit(listOf("git", "-C", selected.absolutePath, "rev-parse", "--show-toplevel"))
        require(result.exitCode == 0) {
            "Selected folder is not inside a Git repository: ${result.output.ifBlank { path }}"
        }
        val root = File(result.output.lineSequence().last().trim())
        require(root.isDirectory) { "Git repository root is not accessible: ${root.absolutePath}" }
        return root
    }

    private fun inspect(root: File): GitSnapshot {
        val branchResult = runGit(listOf("git", "-C", root.absolutePath, "rev-parse", "--abbrev-ref", "HEAD"))
        val headResult = runGit(listOf("git", "-C", root.absolutePath, "rev-parse", "HEAD"))
        val dirtyResult = runGit(listOf("git", "-C", root.absolutePath, "status", "--porcelain"))
        return GitSnapshot(
            branch = branchResult.output.takeIf { branchResult.exitCode == 0 }?.lineSequence()?.lastOrNull()?.trim()?.takeIf(String::isNotEmpty),
            headCommit = headResult.output.takeIf { headResult.exitCode == 0 }?.lineSequence()?.lastOrNull()?.trim()?.takeIf(String::isNotEmpty),
            dirtyFileCount = if (dirtyResult.exitCode == 0) dirtyResult.output.lineSequence().count { it.isNotBlank() } else 0,
        )
    }

    private fun runGit(command: List<String>): GitCommandResult = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        GitCommandResult(process.waitFor(), output)
    }.getOrElse { failure ->
        GitCommandResult(-1, failure.message ?: failure::class.simpleName.orEmpty().ifBlank { "Unable to execute Git" })
    }

    private data class GitCommandResult(
        val exitCode: Int,
        val output: String,
    )

    private data class GitSnapshot(
        val branch: String?,
        val headCommit: String?,
        val dirtyFileCount: Int,
    )
}

private sealed interface LocalGitOperation {
    val label: String
    fun command(root: File): List<String>

    data object Status : LocalGitOperation {
        override val label = "Repository status"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "status", "--short", "--branch")
    }

    data object Fetch : LocalGitOperation {
        override val label = "Fetch remotes"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "fetch", "--prune")
    }

    data object Pull : LocalGitOperation {
        override val label = "Pull fast-forward"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "pull", "--ff-only")
    }

    data object AddAll : LocalGitOperation {
        override val label = "Stage all changes"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "add", "--all")
    }

    data object Push : LocalGitOperation {
        override val label = "Push current branch"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "push")
    }

    data class Checkout(val ref: String) : LocalGitOperation {
        override val label = "Checkout $ref"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "switch", ref)
    }

    data class CreateBranch(val branch: String) : LocalGitOperation {
        override val label = "Create branch $branch"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "switch", "-c", branch)
    }

    data class Commit(val message: String) : LocalGitOperation {
        override val label = "Commit staged changes"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "commit", "-m", message)
    }

    data class PushSetUpstream(val branch: String) : LocalGitOperation {
        override val label = "Push $branch and set upstream"
        override fun command(root: File) = listOf("git", "-C", root.absolutePath, "push", "--set-upstream", "origin", branch)
    }

    companion object {
        fun parse(raw: String): LocalGitOperation {
            val operation = raw.trim()
            return when {
                operation == "status" -> Status
                operation == "fetch" -> Fetch
                operation == "pull" -> Pull
                operation == "add-all" -> AddAll
                operation == "push" -> Push
                operation.startsWith("checkout:") -> Checkout(requireSafeRef(operation.substringAfter(':')))
                operation.startsWith("create-branch:") -> CreateBranch(requireSafeRef(operation.substringAfter(':')))
                operation.startsWith("commit:") -> Commit(
                    operation.substringAfter(':').trim().also {
                        require(it.isNotEmpty()) { "Commit message is required" }
                    },
                )
                operation.startsWith("push-set-upstream:") -> PushSetUpstream(requireSafeRef(operation.substringAfter(':')))
                else -> error(
                    "Unsupported Local Git operation '$operation'. Supported operations: " +
                        "status, fetch, pull, add-all, push, checkout:<ref>, create-branch:<branch>, " +
                        "commit:<message>, push-set-upstream:<branch>",
                )
            }
        }

        private fun requireSafeRef(raw: String): String {
            val ref = raw.trim()
            require(ref.isNotEmpty()) { "Git ref is required" }
            require(!ref.startsWith('-')) { "Git refs may not begin with '-'" }
            require(ref.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Git ref contains invalid control characters" }
            return ref
        }
    }
}
