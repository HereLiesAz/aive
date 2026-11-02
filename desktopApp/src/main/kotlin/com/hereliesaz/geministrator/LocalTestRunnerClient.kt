package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.workflow.ExternalExecutionRun
import com.hereliesaz.geministrator.workflow.ExternalExecutionStatus
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TestRunnerClient
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop test runner for `TaskExecutor.TestRunner` in a linked Local Git project.
 *
 * Running tests runs repository code on this computer, so a run starts only when a human-approval
 * task upstream of the test task was approved in this run. The command is split into an argument
 * list without a shell ([parseTestCommand]); shell syntax is refused, never interpreted. It runs in
 * the run's work: a `worktree-branch` worktree beside the repository, else a temporary detached
 * worktree at the newest upstream `headCommit` (a Local Git agent's `haive/...` commit), else the
 * linked folder. The child gets an allowlisted environment (no provider keys, no `GIT_*`), a
 * timeout, and has its whole process tree ended on timeout, cancellation or abandonment. Its output
 * is kept to the last [maxStreamChars] characters per stream and returned as a
 * [ArtifactKind.TestResult] report; exit code 0 completes the task, anything else fails it.
 *
 * Runs are asynchronous: [start] returns Running and the workflow's reconcile polls [getRun]. A run
 * nobody polls for [abandonAfterMillis] (its workflow was cancelled or replaced) is ended. Runs are
 * held in memory only, so after an app restart a run reports Failed rather than being replayed.
 */
internal class LocalTestRunnerClient(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxStreamChars: Int = DEFAULT_MAX_STREAM_CHARS,
    private val abandonAfterMillis: Long = DEFAULT_ABANDON_AFTER_MILLIS,
    private val watchdogIntervalMillis: Long = DEFAULT_WATCHDOG_INTERVAL_MILLIS,
    private val baseEnvironment: () -> Map<String, String> = { System.getenv() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TestRunnerClient, AutoCloseable {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = ConcurrentHashMap<String, TrackedRun>()
    private val watchdogLock = Any()
    private var watchdog: Job? = null

    private class TrackedRun(val id: String, initial: ExternalExecutionRun, polledAt: Long) {
        @Volatile var state: ExternalExecutionRun = initial
        @Volatile var lastPolledAt: Long = polledAt
        @Volatile var job: Job? = null

        /** Records the end of the run once; a later outcome (a run ended as it finished) is ignored. */
        fun finish(outcome: ExternalExecutionRun): Boolean {
            synchronized(this) {
                if (state.status != ExternalExecutionStatus.Running) return false
                state = outcome
                return true
            }
        }
    }

    override fun supports(project: Project): Boolean = project.repository?.let { repository ->
        repository.source == RepositorySource.Local && !repository.localPath.isNullOrBlank()
    } == true

    override suspend fun start(project: Project, command: String?): ExternalExecutionRun =
        throw UnsupportedOperationException(
            "The desktop test runner needs the task context: its approval and the run's upstream work",
        )

    override suspend fun start(context: TaskExecutorContext, command: String?): ExternalExecutionRun {
        val repository = requireNotNull(context.project.repository) {
            "Running tests needs a linked Local Git repository"
        }
        require(repository.source == RepositorySource.Local) {
            "The desktop test runner runs only in a linked Local Git repository"
        }
        val path = requireNotNull(repository.localPath?.trim()?.takeIf(String::isNotEmpty)) {
            "Local Git repository path is missing"
        }
        val linked = File(path).absoluteFile
        require(linked.isDirectory) { "Local Git path does not exist or is not a directory: $path" }
        require(context.hasApprovedHumanGate()) {
            "Running tests executes repository code on this computer, so the test task needs an " +
                "approved human-approval task before it"
        }
        // Parsed now, so a refused command fails the task before anything is checked out.
        val argv = command?.takeIf(String::isNotBlank)?.let(::parseTestCommand)
        val upstream = context.upstreamWorkspace()
        val runId = "local-test-${UUID.randomUUID()}"
        val running = ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Running,
            message = "Running tests on this computer",
        )
        val tracked = TrackedRun(runId, running, clock())
        runs[runId] = tracked
        ensureWatchdog()
        tracked.job = scope.launch {
            val outcome = try {
                execute(runId, context.taskRun.id, linked, argv, upstream)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                failedRun(runId, failure.message?.takeIf(String::isNotBlank) ?: "Test run failed")
            }
            tracked.finish(outcome)
        }
        return running
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        val tracked = runs[runId] ?: return failedRun(
            runId,
            "Test run $runId has no record on this computer; test runs do not survive a restart of the app",
        )
        tracked.lastPolledAt = clock()
        return tracked.state
    }

    /** Ends every running test and waits briefly for their worktrees to be removed. */
    override fun close() {
        val jobs = runs.values.mapNotNull { tracked ->
            tracked.finish(failedRun(tracked.id, "Test run stopped: the app closed"))
            tracked.job
        }
        scope.cancel()
        runBlocking { withTimeoutOrNull(CLOSE_GRACE_MILLIS) { jobs.joinAll() } }
    }

    private fun ensureWatchdog() {
        synchronized(watchdogLock) {
            if (watchdog?.isActive == true) return
            watchdog = scope.launch {
                while (isActive) {
                    delay(watchdogIntervalMillis)
                    sweep()
                }
            }
        }
    }

    /** Ends runs nobody polls any more, and forgets finished runs after a while. */
    private fun sweep() {
        val now = clock()
        runs.values.forEach { tracked ->
            val idle = now - tracked.lastPolledAt
            if (tracked.state.status == ExternalExecutionStatus.Running) {
                if (idle > abandonAfterMillis) {
                    val stopped = tracked.finish(
                        failedRun(
                            tracked.id,
                            "Test run stopped: its workflow stopped checking on it (cancelled or replaced), " +
                                "so its processes were ended",
                        ),
                    )
                    if (stopped) tracked.job?.cancel()
                }
            } else if (idle > RETAIN_FINISHED_MILLIS) {
                runs.remove(tracked.id)
            }
        }
    }

    private suspend fun execute(
        runId: String,
        taskRunId: TaskRunId,
        linked: File,
        explicitArgv: List<String>?,
        upstream: UpstreamWorkspace?,
    ): ExternalExecutionRun {
        val workspace = prepareWorkspace(linked, upstream)
        try {
            val argv = resolveProgram(
                explicitArgv ?: defaultTestCommand(workspace.directory, windows),
                workspace.directory,
            )
            val started = clock()
            val result = runBoundedProcess(
                command = argv,
                directory = workspace.directory,
                environment = testEnvironment(baseEnvironment()),
                timeoutMillis = timeoutMillis,
                maxChars = maxStreamChars,
            )
            return report(runId, taskRunId, argv, workspace, result, clock() - started)
        } finally {
            // Removing a temporary worktree must outlive the cancellation that ended the run.
            withContext(NonCancellable) { runCatching { workspace.cleanup() } }
        }
    }

    private class PreparedWorkspace(
        val directory: File,
        val description: String,
        val metadata: Map<String, String>,
        val cleanup: suspend () -> Unit = {},
    )

    private suspend fun prepareWorkspace(linked: File, upstream: UpstreamWorkspace?): PreparedWorkspace {
        val linkedFolder = PreparedWorkspace(linked, "linked folder", mapOf("workspace" to "linked-folder"))
        if (upstream == null) return linkedFolder
        val rootResult = git(linked, "rev-parse", "--show-toplevel")
        require(rootResult.exitCode == 0) {
            "Upstream work names a Git commit, but the linked folder is not a Git repository: " +
                rootResult.stdout.text.ifBlank { rootResult.stderr.text }.trim()
        }
        val rootPath = requireNotNull(rootResult.stdout.text.lineSequence().lastOrNull(String::isNotBlank)) {
            "Git did not report the repository root of ${linked.path}"
        }
        val root = File(rootPath.trim()).canonicalFile

        // A worktree made by the `worktree-branch` operation lives at `<repo>-worktrees/<branch>`;
        // any other path in an artifact's metadata is not trusted.
        upstream.worktreePath?.let { path ->
            val worktrees = File(root.path + "-worktrees").canonicalFile
            val candidate = File(path).canonicalFile
            if (candidate.isDirectory && candidate != worktrees && candidate.toPath().startsWith(worktrees.toPath())) {
                return PreparedWorkspace(
                    candidate,
                    "Local Git worktree",
                    mapOf("workspace" to "worktree", "worktreePath" to candidate.path),
                )
            }
        }

        val commit = requireNotNull(upstream.headCommit) {
            "Upstream work names ${upstream.worktreePath}, which is not a worktree beside this repository; refusing to run there"
        }
        val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("haive-test-").toFile() }
        val hooks = withContext(Dispatchers.IO) { Files.createTempDirectory("haive-test-hooks-").toFile() }
        val removeDirectories: suspend () -> Unit = {
            withContext(Dispatchers.IO) {
                runCatching { directory.deleteRecursively() }
                runCatching { hooks.deleteRecursively() }
            }
        }
        // Hooks (post-checkout) are disabled while checking out; the test command itself is the
        // repository code the approval allowed.
        val added = try {
            git(root, "-c", "core.hooksPath=${hooks.absolutePath}", "worktree", "add", "--detach", directory.absolutePath, commit)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { git(root, "worktree", "remove", "--force", directory.absolutePath) }
                removeDirectories()
            }
            throw failure
        }
        if (added.exitCode != 0) {
            withContext(NonCancellable) {
                runCatching { git(root, "worktree", "remove", "--force", directory.absolutePath) }
                removeDirectories()
            }
            error("Could not check out ${commit.take(12)} for testing: " + added.stderr.text.ifBlank { added.stdout.text }.trim())
        }
        return PreparedWorkspace(
            directory,
            "detached worktree at ${commit.take(12)}",
            mapOf("workspace" to "detached-worktree", "headCommit" to commit),
        ) {
            runCatching { git(root, "worktree", "remove", "--force", directory.absolutePath) }
            removeDirectories()
            runCatching { git(root, "worktree", "prune") }
        }
    }

    /** Git with the fsmonitor command disabled and a clean, non-interactive Git environment. */
    private suspend fun git(directory: File, vararg args: String): BoundedProcessResult = runBoundedProcess(
        command = listOf("git", "-C", directory.absolutePath, "-c", "core.fsmonitor=false") + args,
        directory = directory,
        environment = null,
        timeoutMillis = TimeUnit.SECONDS.toMillis(GIT_TIMEOUT_SECONDS),
        maxChars = maxStreamChars,
    )

    private fun report(
        runId: String,
        taskRunId: TaskRunId,
        argv: List<String>,
        workspace: PreparedWorkspace,
        result: BoundedProcessResult,
        durationMillis: Long,
    ): ExternalExecutionRun {
        val passed = result.startFailure == null && !result.timedOut && result.exitCode == 0
        val outcome = when {
            result.startFailure != null -> "could not start: ${result.startFailure}"
            result.timedOut -> "timed out after ${timeoutMillis / 1000.0}s; its processes were ended"
            passed -> "passed (exit 0)"
            else -> "failed (exit ${result.exitCode})"
        }
        val displayed = displayCommand(argv)
        val text = buildString {
            appendLine("Test command: $displayed")
            appendLine("Working directory: ${workspace.directory.path} (${workspace.description})")
            appendLine("Result: $outcome")
            appendLine("Duration: $durationMillis ms")
            appendStream("stdout", result.stdout)
            appendStream("stderr", result.stderr)
        }
        val artifact = ArtifactRef(
            id = ArtifactId("$runId:test-report"),
            kind = ArtifactKind.TestResult,
            taskRunId = taskRunId,
            label = if (passed) "Tests passed" else "Tests failed",
            textContent = text,
            mediaType = "text/plain",
            metadata = buildMap {
                put("repositorySource", RepositorySource.Local.name)
                put("command", displayed)
                put("workingDirectory", workspace.directory.path)
                putAll(workspace.metadata)
                put("exitCode", result.exitCode.toString())
                put("passed", passed.toString())
                put("timedOut", result.timedOut.toString())
                put("durationMillis", durationMillis.toString())
                put("stdoutChars", result.stdout.totalChars.toString())
                put("stderrChars", result.stderr.totalChars.toString())
                put("outputTruncated", (result.stdout.truncated || result.stderr.truncated).toString())
            },
            createdAtEpochMillis = clock(),
        )
        return ExternalExecutionRun(
            id = runId,
            status = if (passed) ExternalExecutionStatus.Completed else ExternalExecutionStatus.Failed,
            artifacts = listOf(artifact),
            progress = if (passed) 1f else null,
            message = "Tests $outcome · $displayed",
        )
    }

    private fun StringBuilder.appendStream(name: String, output: BoundedOutput) {
        appendLine()
        if (output.truncated) {
            appendLine("--- $name (last ${output.text.length} of ${output.totalChars} characters) ---")
        } else {
            appendLine("--- $name ---")
        }
        append(output.text)
        if (output.text.isNotEmpty() && !output.text.endsWith('\n')) appendLine()
    }

    private fun failedRun(runId: String, message: String) = ExternalExecutionRun(
        id = runId,
        status = ExternalExecutionStatus.Failed,
        message = message,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20L * 60 * 1000
        const val DEFAULT_MAX_STREAM_CHARS = 32_000
        /** The runtime polls every second, backing off to about 30 seconds after failures. */
        const val DEFAULT_ABANDON_AFTER_MILLIS = 2L * 60 * 1000
        const val DEFAULT_WATCHDOG_INTERVAL_MILLIS = 5_000L
        const val RETAIN_FINISHED_MILLIS = 60L * 60 * 1000
        const val CLOSE_GRACE_MILLIS = 10_000L
        const val GIT_TIMEOUT_SECONDS = 120L
    }
}

/** Where the run's upstream work is: a worktree it created, or the commit it produced. */
internal data class UpstreamWorkspace(val worktreePath: String?, val headCommit: String?)

private val COMMIT_ID = Regex("[0-9a-fA-F]{7,64}")

/**
 * The newest artifact, among everything upstream of the task (through released dependencies, so a
 * human-approval task in between is looked through), that names a worktree or a commit.
 */
internal fun TaskExecutorContext.upstreamWorkspace(): UpstreamWorkspace? {
    val tasksById = definition.tasks.associateBy { it.id }
    val visited = mutableSetOf<TaskDefinitionId>()
    val pending = ArrayDeque(definition.releasedDependencies(task))
    val artifacts = mutableListOf<ArtifactRef>()
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!visited.add(id)) continue
        run.taskRuns[id]?.artifacts?.let(artifacts::addAll)
        tasksById[id]?.let { pending.addAll(definition.releasedDependencies(it)) }
    }
    return artifacts
        .mapNotNull { artifact ->
            val worktree = artifact.metadata["worktreePath"]?.trim()?.takeIf(String::isNotEmpty)
            val commit = artifact.metadata["headCommit"]?.trim()?.takeIf(COMMIT_ID::matches)
            if (worktree == null && commit == null) null else artifact.createdAtEpochMillis to UpstreamWorkspace(worktree, commit)
        }
        .maxByOrNull { it.first }
        ?.second
}

/** Whether a human-approval task upstream of this task (through dependsOn) was approved in this run. */
internal fun TaskExecutorContext.hasApprovedHumanGate(): Boolean {
    val tasksById = definition.tasks.associateBy { it.id }
    val visited = mutableSetOf<TaskDefinitionId>()
    fun visit(id: TaskDefinitionId): Boolean {
        if (!visited.add(id)) return false
        val current = tasksById[id] ?: return false
        return current.dependsOn.any { dependencyId ->
            val dependency = tasksById[dependencyId] ?: return@any false
            (dependency.executor is TaskExecutor.HumanApproval &&
                run.taskRuns[dependencyId]?.status == TaskRunStatus.Completed) ||
                visit(dependencyId)
        }
    }
    return visit(task.id)
}

/** Characters refused anywhere in a test command: they would mean something to a shell. */
private val REFUSED_ANYWHERE = setOf('\u0000', '\n', '\r', '`', '$')

/** Shell operators, globs, redirections and escapes, refused outside quotes. */
private val REFUSED_UNQUOTED = setOf('|', '&', ';', '<', '>', '(', ')', '*', '?', '[', ']', '{', '}', '~', '!', '#', '\\')

/**
 * Splits a test command into an argument list without a shell. Arguments are separated by
 * whitespace; single or double quotes group text into one argument and are removed, with no
 * escapes or expansion inside them. Anything a shell would interpret (`$`, backticks, pipes,
 * `&&`, `;`, redirections, globs, `~`, backslashes outside quotes, a leading `NAME=value`) is
 * refused rather than passed through literally, so a command cannot mean something other than
 * what it looks like.
 */
internal fun parseTestCommand(command: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inArgument = false
    var quote: Char? = null
    for (c in command) {
        require(c !in REFUSED_ANYWHERE) {
            "Test command may not contain ${describeRefused(c)}: it runs without a shell"
        }
        when {
            quote != null -> if (c == quote) quote = null else current.append(c)
            c == '\'' || c == '"' -> {
                quote = c
                inArgument = true
            }
            c.isWhitespace() -> if (inArgument) {
                args += current.toString()
                current.clear()
                inArgument = false
            }
            else -> {
                require(c !in REFUSED_UNQUOTED) {
                    "Test command may not contain '$c' outside quotes: it runs without a shell"
                }
                current.append(c)
                inArgument = true
            }
        }
    }
    require(quote == null) { "Test command has an unterminated $quote quote" }
    if (inArgument) args += current.toString()
    require(args.isNotEmpty() && args.first().isNotBlank()) { "Test command is empty" }
    require('=' !in args.first()) {
        "Test command may not start with an environment assignment (${args.first()}): it runs without a shell"
    }
    require(!args.first().startsWith('-')) { "Test command must start with a program, not an option" }
    return args
}

private fun describeRefused(c: Char): String = when (c) {
    '\u0000' -> "a NUL character"
    '\n', '\r' -> "a line break"
    else -> "'$c'"
}

/** The test command when the task names none: the Gradle wrapper, else `npm test`. */
internal fun defaultTestCommand(directory: File, windows: Boolean): List<String> {
    val gradlew = File(directory, if (windows) "gradlew.bat" else "gradlew")
    return when {
        gradlew.isFile -> listOf(gradlew.absolutePath, "test")
        File(directory, "package.json").isFile -> listOf(if (windows) "npm.cmd" else "npm", "test")
        else -> throw IllegalArgumentException(
            "No test command was given, and none was found in ${directory.path} (looked for the Gradle wrapper and package.json)",
        )
    }
}

/**
 * A program given as a path (`./gradlew`, `scripts/test.sh`) is resolved against the working
 * directory and must exist; a bare name (`npm`, `make`) is looked up on PATH.
 */
internal fun resolveProgram(argv: List<String>, directory: File): List<String> {
    val program = argv.first()
    if ('/' !in program && '\\' !in program) return argv
    val file = File(program).let { if (it.isAbsolute) it else File(directory, program) }.toPath().normalize().toFile()
    require(file.isFile) { "Test program not found: $program" }
    return listOf(file.absolutePath) + argv.drop(1)
}

/** Environment variables a build or test tool needs; everything else (API keys, tokens) is dropped. */
private val TEST_ENVIRONMENT_ALLOWLIST = listOf(
    "PATH", "HOME", "USER", "USERNAME", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
    "TMPDIR", "TMP", "TEMP", "JAVA_HOME", "GRADLE_USER_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT",
    "SYSTEMROOT", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "WINDIR", "USERPROFILE", "APPDATA",
    "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "NUMBER_OF_PROCESSORS",
    "PROCESSOR_ARCHITECTURE", "OS",
)

/** The child's environment: allowlisted variables only, no `GIT_*`, no prompts, and `CI=true`. */
internal fun testEnvironment(inherited: Map<String, String>): Map<String, String> {
    val kept = inherited
        .filterKeys { key -> TEST_ENVIRONMENT_ALLOWLIST.any { it.equals(key, ignoreCase = true) } }
        .toMutableMap()
    sanitizeGitEnvironment(kept)
    kept["CI"] = "true"
    return kept
}

private fun displayCommand(argv: List<String>): String = argv.joinToString(" ") { argument ->
    if (argument.isEmpty() || argument.any(Char::isWhitespace)) "'$argument'" else argument
}

/** The last characters a stream produced, and how many it produced in all. */
internal class BoundedOutput(val text: String, val totalChars: Long) {
    val truncated: Boolean get() = totalChars > text.length
}

internal data class BoundedProcessResult(
    val exitCode: Int,
    val stdout: BoundedOutput,
    val stderr: BoundedOutput,
    val timedOut: Boolean,
    val startFailure: String? = null,
)

/**
 * Runs [command] (an argument list, never a shell string) in [directory]. [environment] replaces
 * the inherited environment; null inherits it with Git's variables cleared. Both streams are
 * drained on their own threads and keep only their last [maxChars] characters. On timeout or
 * cancellation the whole process tree is ended.
 */
internal suspend fun runBoundedProcess(
    command: List<String>,
    directory: File,
    environment: Map<String, String>?,
    timeoutMillis: Long,
    maxChars: Int,
): BoundedProcessResult {
    // Held outside the start's suspension: a cancellation as it resumes would otherwise drop a
    // process that is already running, and leave it unsupervised.
    val started = AtomicReference<Process?>()
    val process = try {
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(command).directory(directory)
            if (environment == null) {
                sanitizeGitEnvironment(builder)
            } else {
                builder.environment().clear()
                builder.environment().putAll(environment)
            }
            builder.start().also {
                started.set(it)
                runCatching { it.outputStream.close() }
            }
        }
    } catch (failure: CancellationException) {
        started.get()?.let(::destroyProcessTree)
        throw failure
    } catch (failure: Exception) {
        val empty = BoundedOutput("", 0)
        return BoundedProcessResult(-1, empty, empty, timedOut = false, startFailure = failure.message ?: "Unable to start ${command.first()}")
    }
    val out = BoundedDrain(process.inputStream, maxChars, "aive-test-stdout")
    val err = BoundedDrain(process.errorStream, maxChars, "aive-test-stderr")
    val finished = try {
        runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
    } catch (failure: CancellationException) {
        destroyProcessTree(process)
        throw failure
    }
    if (!finished) destroyProcessTree(process)
    return BoundedProcessResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = out.await(),
        stderr = err.await(),
        timedOut = !finished,
    )
}

private fun destroyProcessTree(process: Process) {
    runCatching { process.toHandle().descendants().forEach { child -> runCatching { child.destroyForcibly() } } }
    runCatching { process.destroyForcibly() }
}

/** Reads a stream to its end on its own thread, keeping only the last [maxChars] characters. */
private class BoundedDrain(stream: InputStream, private val maxChars: Int, name: String) {
    private val buffer = StringBuilder()
    private var total = 0L
    private val thread = Thread({
        runCatching {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(8_192)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    synchronized(buffer) {
                        buffer.append(chunk, 0, read)
                        total += read
                        if (buffer.length.toLong() > maxChars.toLong() * 2) buffer.delete(0, buffer.length - maxChars)
                    }
                }
            }
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    /** What the stream produced; waits briefly for the reader (a surviving grandchild may hold the pipe). */
    fun await(): BoundedOutput {
        thread.join(TimeUnit.SECONDS.toMillis(10))
        return synchronized(buffer) {
            val text = if (buffer.length > maxChars) buffer.substring(buffer.length - maxChars) else buffer.toString()
            BoundedOutput(text, total)
        }
    }
}
