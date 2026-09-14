package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.TextGenerationResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop-only coding provider for linked Local Git projects.
 *
 * The model never receives shell access. It receives a bounded repository snapshot and returns a
 * unified diff. Haive validates that diff with `git apply --check`, applies it inside an isolated
 * Git worktree, runs a bounded recognized test command, commits the resulting branch, and emits
 * the concrete patch/test evidence back into the workflow.
 */
internal class LocalWorkspaceAgentProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    private val api: TextGenerationApi,
) : AgentProvider {
    private enum class Phase { AwaitingApproval, Ready, Cancelled }

    private data class Session(
        val request: AgentTaskRequest,
        val phase: MutableStateFlow<Phase>,
        var plan: WorkspacePlan? = null,
        var planUsage: TextGenerationResult? = null,
    )

    private data class WorkspacePlan(
        val text: String,
        val requestedFiles: List<String>,
    )

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    )

    private data class TestExecution(
        val command: List<String>?,
        val result: ProcessResult?,
    )

    private val mutex = Mutex()
    private val sequence = AtomicLong(1L)

    private companion object {
        const val MAX_TREE_PATHS = 700
        const val MAX_SELECTED_FILES = 28
        const val MAX_FILE_CHARS = 32_000
        const val MAX_SNAPSHOT_CHARS = 180_000
        const val MAX_CONTEXT_ARTIFACT_CHARS = 30_000
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val TEST_TIMEOUT_MINUTES = 12L

        val sessionsMutex = Mutex()
        val sessionsByProvider = mutableMapOf<String, MutableMap<ProviderRunId, Session>>()
    }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
            AgentCapability.ShellExecution,
            AgentCapability.Testing,
            AgentCapability.TestAuthoring,
        ),
        requiresEnvironmentPlanning = true,
    )

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository?.source == RepositorySource.Local && !repository.localPath.isNullOrBlank()

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        require(supportsRepository(request.repository)) {
            "$displayName workspace agent requires a linked Local Git repository"
        }
        val runId = ProviderRunId("${id.value}/${request.taskRunId.value}/${sequence.getAndIncrement()}")
        val session = Session(
            request = request,
            phase = MutableStateFlow(if (request.requirePlanApproval) Phase.AwaitingApproval else Phase.Ready),
        )
        sessionsMutex.withLock {
            sessionsByProvider.getOrPut(id.value) { mutableMapOf() }[runId] = session
        }
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = session(runId)
        try {
            val root = repositoryRoot(session.request)
            requireCleanWorkingTree(root)
            val baseCommit = git(root, "rev-parse", "HEAD").requireSuccess("read repository HEAD").output.trim()
            val trackedFiles = trackedFiles(root)
            require(trackedFiles.isNotEmpty()) { "Local Git repository has no tracked files" }

            val plan = session.plan ?: generatePlan(session.request, root, trackedFiles).also {
                session.plan = it.first
                session.planUsage = it.second
            }.first

            if (session.request.requirePlanApproval && session.phase.value == Phase.AwaitingApproval) {
                emit(AgentEvent.PlanGenerated(runId, plan.text.take(8_000)))
                val phase = session.phase.filter { it != Phase.AwaitingApproval }.first()
                if (phase == Phase.Cancelled) {
                    emit(AgentEvent.Failed(runId, "$displayName workspace session cancelled"))
                    return@flow
                }
                emit(AgentEvent.PlanApproved(runId))
            }

            if (session.phase.value == Phase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName workspace session cancelled"))
                return@flow
            }

            emit(AgentEvent.Progress(runId, "Preparing isolated Local Git workspace"))
            val mode = taskMode(session.request)
            val outcome = executeInWorktree(
                runId = runId,
                request = session.request,
                root = root,
                baseCommit = baseCommit,
                trackedFiles = trackedFiles,
                plan = plan,
                mode = mode,
            )

            outcome.artifacts.forEach { artifact ->
                emit(AgentEvent.ArtifactProduced(runId, artifact))
            }
            val planUsage = session.planUsage
            val inputTokens = listOfNotNull(planUsage?.inputTokens, outcome.generationUsage?.inputTokens).sumOrNull()
            val outputTokens = listOfNotNull(planUsage?.outputTokens, outcome.generationUsage?.outputTokens).sumOrNull()
            if (inputTokens != null || outputTokens != null) {
                emit(
                    AgentEvent.UsageReported(
                        runId = runId,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                    ),
                )
            }

            if (outcome.failureReason != null) {
                emit(AgentEvent.Failed(runId, outcome.failureReason))
            } else {
                emit(AgentEvent.Completed(runId))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            emit(
                AgentEvent.Failed(
                    runId,
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty().ifBlank { "Local workspace execution failed" },
                ),
            )
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Rejected(
            "$displayName local workspace sessions are one-shot governed tasks; start a new task for follow-up work.",
        )

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val session = sessionOrNull(runId)
            ?: return ProviderActionResult.Rejected("Workspace session ${runId.value} is not available")
        if (session.phase.value == Phase.Cancelled) {
            return ProviderActionResult.Rejected("Workspace session ${runId.value} is cancelled")
        }
        session.phase.value = Phase.Ready
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        sessionOrNull(runId)?.phase?.value = Phase.Cancelled
        return ProviderActionResult.Accepted
    }

    private data class WorkspaceOutcome(
        val artifacts: List<ProviderArtifact>,
        val generationUsage: TextGenerationResult? = null,
        val failureReason: String? = null,
    )

    private enum class TaskMode { Mutate, Verify }

    private fun taskMode(request: AgentTaskRequest): TaskMode {
        val instructions = request.roleInstructions.lowercase()
        val verificationSignals = listOf("attempt to falsify", "verifies acceptance", "verify acceptance", "qa engineer")
        val mutationSignals = listOf("implement", "author test", "test author", "write code", "modify")
        return if (
            verificationSignals.any(instructions::contains) &&
            mutationSignals.none(instructions::contains)
        ) {
            TaskMode.Verify
        } else {
            TaskMode.Mutate
        }
    }

    private suspend fun executeInWorktree(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        root: File,
        baseCommit: String,
        trackedFiles: List<String>,
        plan: WorkspacePlan,
        mode: TaskMode,
    ): WorkspaceOutcome {
        val branch = workspaceBranch(request)
        val workspace = withContext(Dispatchers.IO) {
            Files.createTempDirectory("haive-${safeSegment(request.taskRunId.value)}-").toFile()
        }
        var worktreeAdded = false
        return try {
            git(
                root,
                "worktree",
                "add",
                "-b",
                branch,
                workspace.absolutePath,
                baseCommit,
            ).requireSuccess("create isolated Git worktree")
            worktreeAdded = true

            if (mode == TaskMode.Verify) {
                val test = runRecognizedTests(workspace)
                if (test.command == null || test.result == null) {
                    return WorkspaceOutcome(
                        artifacts = listOf(
                            ProviderArtifact(
                                kind = ArtifactKind.TestResult,
                                label = "No recognized test runner",
                                textContent = "Haive could not identify a bounded test command for this Local Git project.",
                                mediaType = "text/plain",
                                metadata = mapOf("branch" to branch, "baseCommitId" to baseCommit),
                            ),
                        ),
                        failureReason = "No recognized test runner is available for Local Git verification",
                    )
                }
                val artifact = testArtifact(test, branch, baseCommit)
                return WorkspaceOutcome(
                    artifacts = listOf(artifact),
                    failureReason = if (test.result.exitCode == 0 && !test.result.timedOut) null else "Local Git verification failed",
                )
            }

            val selectedFiles = selectFiles(plan, trackedFiles, request)
            val snapshot = buildSnapshot(workspace, selectedFiles)
            require(snapshot.isNotBlank()) { "No readable tracked source files were selected for the workspace task" }
            val generation = api.generate(patchPrompt(request, plan, trackedFiles, snapshot))
            val patch = extractUnifiedDiff(generation.text)
            require(patch.isNotBlank()) { "$displayName did not return a unified Git diff" }

            applyPatch(workspace, patch)
            val changed = git(workspace, "status", "--porcelain").requireSuccess("inspect workspace changes").output
            require(changed.isNotBlank()) { "$displayName patch did not change the working tree" }

            val test = runRecognizedTests(workspace)
            val testsPassed = test.result?.let { it.exitCode == 0 && !it.timedOut }
            val commitMessage = if (testsPassed == false) {
                "Haive WIP: ${request.objective.trim().lineSequence().firstOrNull().orEmpty().take(64)}"
            } else {
                "Haive: ${request.objective.trim().lineSequence().firstOrNull().orEmpty().take(68)}"
            }.trimEnd()

            git(workspace, "add", "--all").requireSuccess("stage workspace changes")
            val commit = git(
                workspace,
                "-c",
                "user.name=Haive",
                "-c",
                "user.email=haive@local.invalid",
                "commit",
                "-m",
                commitMessage,
            ).requireSuccess("commit workspace changes")
            val headCommit = git(workspace, "rev-parse", "HEAD").requireSuccess("read workspace commit").output.trim()
            val committedPatch = git(workspace, "show", "--format=", "--binary", headCommit)
                .requireSuccess("capture committed patch")
                .output

            val artifacts = buildList {
                add(
                    ProviderArtifact(
                        kind = ArtifactKind.CodeChange,
                        label = commitMessage,
                        textContent = committedPatch,
                        mediaType = "text/x-diff",
                        metadata = mapOf(
                            "provider" to id.value,
                            "source" to RepositorySource.Local.name,
                            "branch" to branch,
                            "baseCommitId" to baseCommit,
                            "headCommit" to headCommit,
                            "suggestedCommitMessage" to commitMessage,
                            "commitOutput" to commit.output.take(2_000),
                        ),
                    ),
                )
                if (test.command != null && test.result != null) {
                    add(testArtifact(test, branch, baseCommit, headCommit))
                } else {
                    add(
                        ProviderArtifact(
                            kind = ArtifactKind.CommandOutput,
                            label = "No recognized test runner",
                            textContent = "Code was committed on $branch, but Haive did not identify a bounded test command for this project.",
                            mediaType = "text/plain",
                            metadata = mapOf(
                                "branch" to branch,
                                "baseCommitId" to baseCommit,
                                "headCommit" to headCommit,
                            ),
                        ),
                    )
                }
            }
            WorkspaceOutcome(
                artifacts = artifacts,
                generationUsage = generation,
                failureReason = if (testsPassed == false) "Workspace changes were preserved on $branch, but tests failed" else null,
            )
        } finally {
            if (worktreeAdded) {
                runCatching { git(root, "worktree", "remove", "--force", workspace.absolutePath) }
            }
            withContext(Dispatchers.IO) {
                runCatching { workspace.deleteRecursively() }
            }
        }
    }

    private suspend fun generatePlan(
        request: AgentTaskRequest,
        root: File,
        trackedFiles: List<String>,
    ): Pair<WorkspacePlan, TextGenerationResult> {
        val result = api.generate(planPrompt(request, root, trackedFiles))
        val plan = WorkspacePlan(
            text = result.text.trim(),
            requestedFiles = parseRequestedFiles(result.text, trackedFiles),
        )
        return plan to result
    }

    private fun planPrompt(request: AgentTaskRequest, root: File, trackedFiles: List<String>): String = buildString {
        appendLine("You are planning a real code change in a Local Git repository. Do not invent files or claim changes yet.")
        appendLine("Repository root: ${root.name}")
        appendLine("Task: ${request.objective.trim()}")
        appendLine("Role instructions: ${request.roleInstructions.trim()}")
        if (request.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            request.acceptanceCriteria.forEach { appendLine("- ${it.description.trim()}") }
        }
        appendLine()
        appendLine("Tracked files:")
        trackedFiles.take(MAX_TREE_PATHS).forEach { appendLine(it) }
        if (trackedFiles.size > MAX_TREE_PATHS) appendLine("... ${trackedFiles.size - MAX_TREE_PATHS} more tracked files")
        appendLine()
        appendLine("Return a concise implementation plan, then a line containing exactly FILES:, followed by up to $MAX_SELECTED_FILES tracked file paths to inspect, one per line. Do not use Markdown code fences.")
    }

    private fun patchPrompt(
        request: AgentTaskRequest,
        plan: WorkspacePlan,
        trackedFiles: List<String>,
        snapshot: String,
    ): String = buildString {
        appendLine("You are editing a real isolated Git worktree. Produce only a unified Git diff that can be applied with `git apply`.")
        appendLine("Do not wrap the diff in Markdown fences. Do not describe the change outside the diff.")
        appendLine("Use paths relative to the repository root and standard `diff --git a/... b/...` headers.")
        appendLine()
        appendLine("TASK")
        appendLine(request.objective.trim())
        appendLine()
        appendLine("ROLE INSTRUCTIONS")
        appendLine(request.roleInstructions.trim())
        if (request.acceptanceCriteria.isNotEmpty()) {
            appendLine()
            appendLine("ACCEPTANCE CRITERIA")
            request.acceptanceCriteria.forEach { appendLine("- ${it.description.trim()}") }
        }
        appendLine()
        appendLine("APPROVED PLAN")
        appendLine(plan.text.take(16_000))
        val artifactContext = request.contextArtifacts
            .asSequence()
            .filter { !it.textContent.isNullOrBlank() }
            .joinToString("\n\n") { "${it.label}\n${it.textContent}" }
            .take(MAX_CONTEXT_ARTIFACT_CHARS)
        if (artifactContext.isNotBlank()) {
            appendLine()
            appendLine("UPSTREAM EVIDENCE")
            appendLine(artifactContext)
        }
        appendLine()
        appendLine("REPOSITORY TREE")
        trackedFiles.take(MAX_TREE_PATHS).forEach { appendLine(it) }
        appendLine()
        appendLine("SELECTED FILE CONTENT")
        append(snapshot)
    }

    private fun selectFiles(
        plan: WorkspacePlan,
        trackedFiles: List<String>,
        request: AgentTaskRequest,
    ): List<String> {
        val tracked = trackedFiles.toSet()
        val requested = plan.requestedFiles.filter { it in tracked }.distinct().take(MAX_SELECTED_FILES)
        if (requested.isNotEmpty()) return requested

        val tokens = (request.objective + " " + request.roleInstructions)
            .lowercase()
            .split(Regex("[^a-z0-9_.-]+"))
            .filter { it.length >= 3 }
            .toSet()
        val preferredNames = setOf(
            "README.md", "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
            "gradle.properties", "package.json", "pyproject.toml", "Cargo.toml", "pom.xml",
        )
        return trackedFiles
            .sortedByDescending { path ->
                val lower = path.lowercase()
                tokens.count(lower::contains) * 10 + if (path.substringAfterLast('/') in preferredNames) 4 else 0
            }
            .take(MAX_SELECTED_FILES)
    }

    private fun parseRequestedFiles(plan: String, trackedFiles: List<String>): List<String> {
        val tracked = trackedFiles.toSet()
        val lines = plan.lines()
        val marker = lines.indexOfFirst { it.trim().equals("FILES:", ignoreCase = true) }
        if (marker < 0) return emptyList()
        return lines.drop(marker + 1)
            .map { it.trim().removePrefix("-").trim().trim('`') }
            .filter { it.isNotBlank() && it in tracked }
            .distinct()
            .take(MAX_SELECTED_FILES)
    }

    private suspend fun buildSnapshot(root: File, paths: List<String>): String = withContext(Dispatchers.IO) {
        val canonicalRoot = root.canonicalFile
        var remaining = MAX_SNAPSHOT_CHARS
        buildString {
            for (path in paths) {
                if (remaining <= 0) break
                val file = File(canonicalRoot, path).canonicalFile
                if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile) continue
                val bytes = runCatching { Files.readAllBytes(file.toPath()) }.getOrNull() ?: continue
                if (bytes.any { it == 0.toByte() }) continue
                val text = runCatching { bytes.decodeToString() }.getOrNull() ?: continue
                val content = text.take(minOf(MAX_FILE_CHARS, remaining))
                appendLine("===== FILE: $path =====")
                appendLine(content)
                appendLine("===== END FILE =====")
                remaining -= content.length
            }
        }
    }

    private suspend fun trackedFiles(root: File): List<String> {
        val output = git(root, "ls-files", "-z").requireSuccess("list tracked files").output
        return output.split('\u0000').filter(String::isNotBlank)
    }

    private suspend fun requireCleanWorkingTree(root: File) {
        val status = git(root, "status", "--porcelain").requireSuccess("inspect Local Git working tree")
        require(status.output.isBlank()) {
            "Local Git working tree has uncommitted changes. Commit or stash them before starting repository-writing agent work."
        }
    }

    private fun repositoryRoot(request: AgentTaskRequest): File {
        val repository = requireNotNull(request.repository) { "Local workspace task has no linked repository" }
        require(repository.source == RepositorySource.Local) { "Local workspace provider only supports Local Git" }
        val path = requireNotNull(repository.localPath).trim()
        require(path.isNotEmpty()) { "Local Git path is missing" }
        val root = File(path)
        require(root.isDirectory) { "Local Git path is not accessible: $path" }
        return root
    }

    private suspend fun applyPatch(workspace: File, patch: String) {
        runProcess(
            command = listOf("git", "-C", workspace.absolutePath, "apply", "--check", "--whitespace=nowarn", "-"),
            stdin = patch,
        ).requireSuccess("validate generated patch")
        runProcess(
            command = listOf("git", "-C", workspace.absolutePath, "apply", "--whitespace=nowarn", "-"),
            stdin = patch,
        ).requireSuccess("apply generated patch")
    }

    private fun extractUnifiedDiff(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.lineSequence().drop(1).dropLastWhile { it.trim().startsWith("```") }.joinToString("\n")
        }
        val start = text.indexOf("diff --git ")
        return if (start >= 0) text.substring(start).trim() else ""
    }

    private suspend fun runRecognizedTests(workspace: File): TestExecution {
        val command = recognizedTestCommand(workspace) ?: return TestExecution(null, null)
        return TestExecution(
            command = command,
            result = runProcess(command, directory = workspace, timeoutMinutes = TEST_TIMEOUT_MINUTES),
        )
    }

    private fun recognizedTestCommand(root: File): List<String>? {
        val windows = System.getProperty("os.name", "").lowercase().contains("win")
        return when {
            File(root, "gradlew").isFile || File(root, "gradlew.bat").isFile -> if (windows) {
                listOf("cmd", "/c", "gradlew.bat", "test")
            } else {
                listOf("sh", "gradlew", "test")
            }
            File(root, "mvnw").isFile || File(root, "mvnw.cmd").isFile -> if (windows) {
                listOf("cmd", "/c", "mvnw.cmd", "test")
            } else {
                listOf("sh", "mvnw", "test")
            }
            File(root, "Cargo.toml").isFile -> listOf("cargo", "test")
            File(root, "pyproject.toml").isFile || File(root, "pytest.ini").isFile -> listOf("python", "-m", "pytest")
            else -> null
        }
    }

    private fun testArtifact(
        test: TestExecution,
        branch: String,
        baseCommit: String,
        headCommit: String? = null,
    ): ProviderArtifact {
        val result = requireNotNull(test.result)
        val command = requireNotNull(test.command)
        return ProviderArtifact(
            kind = ArtifactKind.TestResult,
            label = if (result.exitCode == 0 && !result.timedOut) "Workspace tests passed" else "Workspace tests failed",
            textContent = result.output.take(120_000),
            mediaType = "text/plain",
            metadata = buildMap {
                put("command", command.joinToString(" "))
                put("exitCode", result.exitCode.toString())
                put("timedOut", result.timedOut.toString())
                put("branch", branch)
                put("baseCommitId", baseCommit)
                headCommit?.let { put("headCommit", it) }
            },
        )
    }

    private fun workspaceBranch(request: AgentTaskRequest): String =
        "haive/${safeSegment(request.taskRunId.value).take(40)}-${sequence.getAndIncrement()}"

    private fun safeSegment(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "task" }

    private suspend fun git(root: File, vararg args: String): ProcessResult =
        runProcess(listOf("git", "-C", root.absolutePath) + args)

    private suspend fun runProcess(
        command: List<String>,
        directory: File? = null,
        stdin: String? = null,
        timeoutMinutes: Long? = null,
    ): ProcessResult = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .apply { if (directory != null) directory(directory) }
                .redirectErrorStream(true)
                .start()
        }
        if (stdin != null) {
            withContext(Dispatchers.IO) {
                process.outputStream.bufferedWriter().use { writer -> writer.write(stdin) }
            }
        } else {
            runCatching { process.outputStream.close() }
        }
        val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
        val timeout = timeoutMinutes?.let { TimeUnit.MINUTES.toSeconds(it) } ?: PROCESS_TIMEOUT_SECONDS
        val finished = withContext(Dispatchers.IO) { process.waitFor(timeout, TimeUnit.SECONDS) }
        if (!finished) {
            process.destroyForcibly()
            withContext(Dispatchers.IO) { process.waitFor(10, TimeUnit.SECONDS) }
        }
        ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.await().trim(),
            timedOut = !finished,
        )
    }

    private fun ProcessResult.requireSuccess(operation: String): ProcessResult {
        require(!timedOut) { "Unable to $operation: command timed out" }
        require(exitCode == 0) {
            "Unable to $operation: ${output.take(4_000).ifBlank { "exit code $exitCode" }}"
        }
        return this
    }

    private suspend fun session(runId: ProviderRunId): Session =
        sessionOrNull(runId) ?: error("$displayName workspace session ${runId.value} is not available")

    private suspend fun sessionOrNull(runId: ProviderRunId): Session? = sessionsMutex.withLock {
        sessionsByProvider[id.value]?.get(runId)
    }

    private fun List<Long>.sumOrNull(): Long? = if (isEmpty()) null else sum()
}
