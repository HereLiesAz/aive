package com.hereliesaz.geministrator

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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalGitRepositoryOperationClient(
    private val locateEntire: () -> EntireCli? = EntireCli::locate,
) : RepositoryOperationClient {
    private val runs = ConcurrentHashMap<String, ExternalExecutionRun>()

    override fun supports(project: Project): Boolean =
        project.repository?.source == RepositorySource.Local

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun =
        start(project, operation, "legacy:${project.id.value}:$operation")

    override suspend fun start(
        project: Project,
        operation: String,
        operationIdentity: String,
    ): ExternalExecutionRun {
        val repository = requireNotNull(project.repository) {
            "Repository operation requires a linked project repository"
        }
        require(repository.source == RepositorySource.Local) {
            "Local Git executor requires a Local Git repository"
        }
        val path = requireNotNull(repository.localPath).trim()
        require(path.isNotEmpty()) { "Local Git repository path is missing" }

        val root = resolveGitRoot(path)
        val parsed = LocalGitOperation.parse(operation)
        val runId = stableRunId(operationIdentity, operation)
        val marker = operationMarker(root, runId)

        readMarker(marker, runId)?.let { existing ->
            runs[runId] = existing
            return existing
        }
        if (marker.isFile) {
            val interrupted = ExternalExecutionRun(
                id = runId,
                status = ExternalExecutionStatus.Failed,
                message = "Local Git operation was interrupted after reservation; automatic replay is refused.",
            )
            runs[runId] = interrupted
            return interrupted
        }

        writeStartedMarker(marker, operation, parsed.label)
        val entire = if (parsed is LocalGitOperation.EntireTrail || parsed is LocalGitOperation.EntireMirror) locateEntire() else null
        val command = when (parsed) {
            is LocalGitOperation.Checkout -> checkoutCommand(root, parsed.ref)
            is LocalGitOperation.EntireTrail ->
                entire?.let { listOf(it.binary.absolutePath, "trail", "create", "--title", parsed.title) }
            is LocalGitOperation.EntireMirror ->
                entire?.let { listOf(it.binary.absolutePath, "repo", "mirror", "add", parsed.reference, "--no-wait") }
            else -> parsed.command(root)
        }
        val result = if (command == null) {
            GitCommandResult(-1, "The entire command is not installed. See $ENTIRE_INSTALL_URL")
        } else {
            // Entire works on the repository it runs in.
            runGit(command, directory = root)
        }
        // A worktree branch reports the new worktree's state; the user's checkout is untouched.
        val worktree = (parsed as? LocalGitOperation.WorktreeBranch)?.worktreeDirectory(root.path)?.let(::File)
        val snapshot = inspect(worktree ?: root)
        val now = System.currentTimeMillis()
        val output = result.output.ifBlank {
            if (result.exitCode == 0) "Git operation completed successfully."
            else "Git operation failed with no output."
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
                put("command", command?.joinToString(" ").orEmpty())
                put("exitCode", result.exitCode.toString())
                worktree?.let { put("worktreePath", it.absolutePath) }
                snapshot.branch?.let { put("branch", it) }
                snapshot.headCommit?.let { put("headCommit", it) }
                snapshot.dirtyFileCount?.let { put("dirtyFileCount", it.toString()) }
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
                snapshot.dirtyFileCount?.takeIf { it > 0 }?.let { count ->
                    append(" · ")
                    append(count)
                    append(if (count == 1) " changed file" else " changed files")
                }
                if (snapshot.dirtyFileCount == null) {
                    append(" · working-tree state unknown")
                }
            },
        )
        runs[runId] = execution
        writeCompletedMarker(marker, operation, parsed.label, result, snapshot, now)
        return execution
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        runs[runId]?.let { return it }
        val repository = project.repository
        val localPath = repository?.localPath
        if (repository?.source == RepositorySource.Local && !localPath.isNullOrBlank()) {
            val root = runCatching { resolveGitRoot(localPath) }.getOrNull()
            if (root != null) {
                val marker = operationMarker(root, runId)
                readMarker(marker, runId)?.let {
                    runs[runId] = it
                    return it
                }
                if (marker.isFile) {
                    return ExternalExecutionRun(
                        id = runId,
                        status = ExternalExecutionStatus.Failed,
                        message = "Local Git operation was interrupted after reservation; automatic replay is refused.",
                    )
                }
            }
        }
        return ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Failed,
            message = "Local Git operation $runId has no durable execution record",
        )
    }

    private suspend fun resolveGitRoot(path: String): File {
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

    private suspend fun inspect(root: File): GitSnapshot {
        val symbolic = runGit(listOf("git", "-C", root.absolutePath, "symbolic-ref", "--short", "-q", "HEAD"))
        val branchResult = if (symbolic.exitCode == 0) {
            symbolic
        } else {
            runGit(listOf("git", "-C", root.absolutePath, "rev-parse", "--abbrev-ref", "HEAD"))
        }
        val headResult = runGit(listOf("git", "-C", root.absolutePath, "rev-parse", "HEAD"))
        val dirtyResult = runGit(
            listOf("git", "-C", root.absolutePath, "status", "--porcelain", "--untracked-files=all"),
        )
        return GitSnapshot(
            branch = branchResult.output.takeIf { branchResult.exitCode == 0 }
                ?.lineSequence()?.lastOrNull()?.trim()?.takeIf(String::isNotEmpty),
            headCommit = headResult.output.takeIf { headResult.exitCode == 0 }
                ?.lineSequence()?.lastOrNull()?.trim()?.takeIf(String::isNotEmpty),
            dirtyFileCount = if (dirtyResult.exitCode == 0) {
                dirtyResult.output.lineSequence().count { it.isNotBlank() }
            } else {
                null
            },
        )
    }

    private suspend fun checkoutCommand(root: File, ref: String): List<String> {
        val localBranch = runGit(
            listOf("git", "-C", root.absolutePath, "show-ref", "--verify", "--quiet", "refs/heads/$ref"),
        ).exitCode == 0
        return if (localBranch) {
            listOf("git", "-C", root.absolutePath, "switch", ref)
        } else {
            listOf("git", "-C", root.absolutePath, "switch", "--detach", ref)
        }
    }

    private suspend fun runGit(command: List<String>, directory: File? = null): GitCommandResult = withContext(Dispatchers.IO) {
        try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            directory?.let(builder::directory)
            sanitizeGitEnvironment(builder)
            val process = builder.start()
            runCatching { process.outputStream.close() }
            val outputReader = Thread {
                // Read concurrently with waitFor so a full pipe cannot deadlock the subprocess.
            }
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            }
            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                destroyProcessTree(process)
            }
            val output = runCatching { outputFuture.get(10, TimeUnit.SECONDS) }.getOrDefault("")
            GitCommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                output = if (finished) output else "Git command timed out. $output".trim(),
            )
        } catch (failure: Throwable) {
            GitCommandResult(
                -1,
                failure.message ?: failure::class.simpleName.orEmpty().ifBlank { "Unable to execute Git" },
            )
        }
    }

    private fun destroyProcessTree(process: Process) {
        runCatching {
            process.toHandle().descendants().forEach { child -> runCatching { child.destroyForcibly() } }
        }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(10, TimeUnit.SECONDS) }
    }

    private suspend fun operationMarker(root: File, runId: String): File {
        val gitDir = runGit(listOf("git", "-C", root.absolutePath, "rev-parse", "--absolute-git-dir"))
            .also { require(it.exitCode == 0) { "Unable to locate Git metadata directory: ${it.output}" } }
            .output
            .trim()
        return File(File(gitDir), "haive-operations/$runId.properties")
    }

    private fun writeStartedMarker(marker: File, operation: String, label: String) {
        writeMarker(
            marker,
            Properties().apply {
                setProperty("state", "started")
                setProperty("operation", operation)
                setProperty("label", label)
                setProperty("startedAt", System.currentTimeMillis().toString())
            },
        )
    }

    private fun writeCompletedMarker(
        marker: File,
        operation: String,
        label: String,
        result: GitCommandResult,
        snapshot: GitSnapshot,
        timestamp: Long,
    ) {
        writeMarker(
            marker,
            Properties().apply {
                setProperty("state", if (result.exitCode == 0) "completed" else "failed")
                setProperty("operation", operation)
                setProperty("label", label)
                setProperty("exitCode", result.exitCode.toString())
                setProperty("output", result.output)
                setProperty("timestamp", timestamp.toString())
                snapshot.branch?.let { setProperty("branch", it) }
                snapshot.headCommit?.let { setProperty("headCommit", it) }
                snapshot.dirtyFileCount?.let { setProperty("dirtyFileCount", it.toString()) }
            },
        )
    }

    private fun writeMarker(marker: File, properties: Properties) {
        marker.parentFile?.mkdirs()
        val temporary = File(marker.parentFile, "${marker.name}.tmp")
        FileOutputStream(temporary).use { properties.store(it, "Aive Local Git operation") }
        if (marker.exists()) marker.delete()
        require(temporary.renameTo(marker)) { "Unable to persist Local Git operation record" }
    }

    private fun readMarker(marker: File, runId: String): ExternalExecutionRun? {
        if (!marker.isFile) return null
        val properties = Properties()
        FileInputStream(marker).use(properties::load)
        val state = properties.getProperty("state") ?: return null
        if (state == "started") return null
        val operation = properties.getProperty("operation").orEmpty()
        val label = properties.getProperty("label").orEmpty().ifBlank { "Local Git operation" }
        val output = properties.getProperty("output").orEmpty()
        val exitCode = properties.getProperty("exitCode")?.toIntOrNull() ?: -1
        val timestamp = properties.getProperty("timestamp")?.toLongOrNull() ?: marker.lastModified()
        val artifact = ArtifactRef(
            id = ArtifactId("$runId:git-output"),
            kind = ArtifactKind.CommandOutput,
            taskRunId = TaskRunId("$runId:external"),
            label = label,
            textContent = output.ifBlank { "Recovered durable Local Git operation record." },
            mediaType = "text/plain",
            metadata = buildMap {
                put("repositorySource", RepositorySource.Local.name)
                put("operation", operation)
                put("exitCode", exitCode.toString())
                properties.getProperty("branch")?.let { put("branch", it) }
                properties.getProperty("headCommit")?.let { put("headCommit", it) }
                properties.getProperty("dirtyFileCount")?.let { put("dirtyFileCount", it) }
            },
            createdAtEpochMillis = timestamp,
        )
        return ExternalExecutionRun(
            id = runId,
            status = if (state == "completed") ExternalExecutionStatus.Completed else ExternalExecutionStatus.Failed,
            artifacts = listOf(artifact),
            progress = if (state == "completed") 1f else null,
            message = "Local Git · $label · recovered",
        )
    }

    private fun stableRunId(operationIdentity: String, operation: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$operationIdentity\u0000$operation".encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "local-git-${digest.take(24)}"
    }

    private data class GitCommandResult(
        val exitCode: Int,
        val output: String,
    )

    private data class GitSnapshot(
        val branch: String?,
        val headCommit: String?,
        val dirtyFileCount: Int?,
    )

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 120L
    }
}

/** The git command line for each [LocalGitOperation]; Checkout is resolved against the repository. */
private fun LocalGitOperation.command(root: File): List<String> {
    val git = listOf("git", "-C", root.absolutePath)
    return when (this) {
        LocalGitOperation.Status -> git + listOf("status", "--short", "--branch", "--untracked-files=all")
        LocalGitOperation.Fetch -> git + listOf("fetch", "--prune")
        LocalGitOperation.Pull -> git + listOf("pull", "--ff-only")
        LocalGitOperation.AddAll -> git + listOf("add", "--all")
        LocalGitOperation.Push -> git + "push"
        is LocalGitOperation.Checkout -> error("Checkout command is resolved against the repository")
        is LocalGitOperation.CreateBranch -> git + listOf("switch", "-c", branch)
        is LocalGitOperation.WorktreeBranch ->
            git + listOf("worktree", "add", "-b", branch, worktreeDirectory(root.path).let(::File).absolutePath, base ?: "HEAD")
        is LocalGitOperation.Commit -> git + listOf("commit", "-m", message)
        is LocalGitOperation.PushSetUpstream -> git + listOf("push", "--set-upstream", "origin", branch)
        is LocalGitOperation.Export -> error("Export applies to imported repositories (Android) only")
        is LocalGitOperation.EntireTrail, is LocalGitOperation.EntireMirror -> error("Entire operations run the entire command")
    }
}
