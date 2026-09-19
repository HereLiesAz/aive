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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalGitRepositoryOperationClient : RepositoryOperationClient {
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
        val command = when (parsed) {
            is LocalGitOperation.Checkout -> checkoutCommand(root, parsed.ref)
            else -> parsed.command(root)
        }
        val result = runGit(command)
        val snapshot = inspect(root)
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
                put("command", command.joinToString(" "))
                put("exitCode", result.exitCode.toString())
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
        if (repository?.source == RepositorySource.Local && !repository.localPath.isNullOrBlank()) {
            val root = runCatching { resolveGitRoot(repository.localPath) }.getOrNull()
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

    private suspend fun runGit(command: List<String>): GitCommandResult = withContext(Dispatchers.IO) {
        try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
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

    private fun sanitizeGitEnvironment(builder: ProcessBuilder) {
        val environment = builder.environment()
        environment.keys
            .filter { key ->
                key.startsWith("GIT_", ignoreCase = true) ||
                    key.equals("SSH_ASKPASS", ignoreCase = true) ||
                    key.equals("GCM_INTERACTIVE", ignoreCase = true)
            }
            .toList()
            .forEach(environment::remove)
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["GCM_INTERACTIVE"] = "Never"
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

private sealed interface LocalGitOperation {
    val label: String
    fun command(root: File): List<String>

    data object Status : LocalGitOperation {
        override val label = "Repository status"
        override fun command(root: File) =
            listOf("git", "-C", root.absolutePath, "status", "--short", "--branch", "--untracked-files=all")
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
        override fun command(root: File) = error("Checkout command is resolved against the repository")
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
        override fun command(root: File) =
            listOf("git", "-C", root.absolutePath, "push", "--set-upstream", "origin", branch)
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
                operation.startsWith("push-set-upstream:") ->
                    PushSetUpstream(requireSafeRef(operation.substringAfter(':')))
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
            require(ref.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "Git ref contains invalid control characters"
            }
            return ref
        }
    }
}
