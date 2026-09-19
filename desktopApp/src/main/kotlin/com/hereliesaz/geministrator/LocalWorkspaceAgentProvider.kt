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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible

/**
 * Desktop-only coding provider for linked Local Git projects.
 *
 * The model never receives shell access. It receives a bounded repository snapshot and returns a
 * unified diff. Haive validates that diff with `git apply --check`, applies it inside an isolated
 * Git worktree, and commits the resulting branch with hooks disabled. Repository code is never
 * executed on the desktop host; verification must use a genuinely sandboxed test-capable provider.
 */
internal class LocalWorkspaceAgentProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    private val api: TextGenerationApi,
) : AgentProvider {
    private enum class Phase { AwaitingApproval, Ready, Cancelled }
    private enum class TaskMode { Specification, Mutate }

    private data class Session(
        val request: AgentTaskRequest,
        val phase: MutableStateFlow<Phase>,
        var plan: WorkspacePlan? = null,
        var planUsage: TextGenerationResult? = null,
        var observationJob: Job? = null,
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

    private data class WorkspaceOutcome(
        val artifacts: List<ProviderArtifact>,
        val generationUsage: TextGenerationResult? = null,
        val failureReason: String? = null,
    )

    private companion object {
        const val MAX_TREE_PATHS = 700
        const val MAX_SELECTED_FILES = 28
        const val MAX_FILE_CHARS = 32_000
        const val MAX_SNAPSHOT_CHARS = 180_000
        const val MAX_CONTEXT_ARTIFACT_CHARS = 30_000
        const val PROCESS_TIMEOUT_SECONDS = 120L

        val sessionsMutex = Mutex()
        val sessionsByProvider = mutableMapOf<String, MutableMap<ProviderRunId, Session>>()
    }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
            AgentCapability.TestAuthoring,
        ),
    )

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository?.source == RepositorySource.Local && !repository.localPath.isNullOrBlank()

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        require(supportsRepository(request.repository)) {
            "$displayName workspace agent requires a linked Local Git repository"
        }
        val runId = ProviderRunId(
            "${id.value}/${request.taskRunId.value}/${UUID.randomUUID()}",
        )
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
        val observationJob = currentCoroutineContext().job
        session.observationJob = observationJob
        try {
            if (taskMode(session.request) == TaskMode.Specification) {
                emit(AgentEvent.Progress(runId, "Designing the pre-code verification contract"))
                val generated = api.generate(specificationPrompt(session.request))
                val artifactKinds = session.request.requiredArtifacts.ifEmpty {
                    setOf(
                        ArtifactKind.AcceptanceTestPlan,
                        ArtifactKind.BehavioralTest,
                        ArtifactKind.ContractTest,
                        ArtifactKind.FailureScenario,
                    )
                }
                artifactKinds.forEach { kind ->
                    emit(
                        AgentEvent.ArtifactProduced(
                            runId,
                            ProviderArtifact(
                                kind = kind,
                                label = kind.name.replace(Regex("([a-z])([A-Z])"), "\$1 \$2"),
                                textContent = generated.text.trim(),
                                mediaType = "text/markdown",
                                metadata = mapOf(
                                    "provider" to id.value,
                                    "mode" to "pre-code-specification",
                                ),
                            ),
                        ),
                    )
                }
                if (generated.inputTokens != null || generated.outputTokens != null) {
                    emit(
                        AgentEvent.UsageReported(
                            runId,
                            inputTokens = generated.inputTokens,
                            outputTokens = generated.outputTokens,
                        ),
                    )
                }
                emit(AgentEvent.Completed(runId))
                return@flow
            }

            val root = repositoryRoot(session.request)
            requireCleanWorkingTree(root)
            val baseCommit = resolveBaseCommit(session.request, root)
            val trackedFiles = trackedFiles(root)
            require(trackedFiles.isNotEmpty()) { "Local Git repository has no tracked files" }

            var plan = session.plan
            if (plan == null) {
                val generated = generatePlan(session.request, root, trackedFiles)
                plan = generated.first
                session.plan = plan
                session.planUsage = generated.second
            }
            val approvedPlan = requireNotNull(plan)

            if (session.request.requirePlanApproval && session.phase.value == Phase.AwaitingApproval) {
                emit(AgentEvent.PlanGenerated(runId, approvedPlan.text.take(8_000)))
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
            val outcome = executeInWorktree(
                request = session.request,
                root = root,
                baseCommit = baseCommit,
                trackedFiles = trackedFiles,
                plan = approvedPlan,
            )

            outcome.artifacts.forEach { artifact -> emit(AgentEvent.ArtifactProduced(runId, artifact)) }
            val inputTokens = listOfNotNull(
                session.planUsage?.inputTokens,
                outcome.generationUsage?.inputTokens,
            ).sumOrNull()
            val outputTokens = listOfNotNull(
                session.planUsage?.outputTokens,
                outcome.generationUsage?.outputTokens,
            ).sumOrNull()
            if (inputTokens != null || outputTokens != null) {
                emit(AgentEvent.UsageReported(runId, inputTokens = inputTokens, outputTokens = outputTokens))
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
        } finally {
            if (session.observationJob === observationJob) {
                session.observationJob = null
            }
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
        val session = sessionOrNull(runId) ?: return ProviderActionResult.Accepted
        session.phase.value = Phase.Cancelled
        session.observationJob?.cancel(CancellationException("$displayName workspace session cancelled"))
        return ProviderActionResult.Accepted
    }

    private fun taskMode(request: AgentTaskRequest): TaskMode {
        val specificationArtifacts = setOf(
            ArtifactKind.AcceptanceTestPlan,
            ArtifactKind.BehavioralTest,
            ArtifactKind.ContractTest,
            ArtifactKind.FailureScenario,
        )
        return if (request.requiredArtifacts.any(specificationArtifacts::contains)) {
            TaskMode.Specification
        } else {
            TaskMode.Mutate
        }
    }

    private fun specificationPrompt(request: AgentTaskRequest): String = buildString {
        appendLine("Design a pre-code verification contract. Do not inspect or infer implementation code.")
        appendLine("Task: ${request.objective.trim()}")
        appendLine("Role instructions: ${request.roleInstructions.trim()}")
        if (request.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            request.acceptanceCriteria.forEach { appendLine("- ${it.description.trim()}") }
        }
        val upstream = request.contextArtifacts
            .asSequence()
            .filter { it.kind != ArtifactKind.CodeChange && !it.textContent.isNullOrBlank() }
            .joinToString("\n\n") { "${it.kind}: ${it.label}\n${it.textContent}" }
            .take(MAX_CONTEXT_ARTIFACT_CHARS)
        if (upstream.isNotBlank()) {
            appendLine()
            appendLine("APPROVED UPSTREAM SPECIFICATION EVIDENCE")
            appendLine(upstream)
        }
        appendLine()
        appendLine(
            "Return concrete acceptance tests, behavioral tests, contract tests, invariants, edge cases, " +
                "and failure scenarios. Do not claim execution or implementation inspection.",
        )
    }

    private suspend fun executeInWorktree(
        request: AgentTaskRequest,
        root: File,
        baseCommit: String,
        trackedFiles: List<String>,
        plan: WorkspacePlan,
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

            val selectedFiles = selectFiles(plan, trackedFiles, request)
            val snapshot = buildSnapshot(workspace, selectedFiles)
            require(snapshot.isNotBlank()) { "No readable tracked source files were selected for the workspace task" }
            val generation = api.generate(patchPrompt(request, plan, trackedFiles, snapshot))
            val patch = extractUnifiedDiff(generation.text)
            require(patch.isNotBlank()) { "$displayName did not return a unified Git diff" }
            validatePatchPaths(patch)

            applyPatch(workspace, patch)
            val changed = git(workspace, "status", "--porcelain").requireSuccess("inspect workspace changes").output
            require(changed.isNotBlank()) { "$displayName patch did not change the working tree" }

            val firstObjectiveLine = request.objective.trim().lineSequence().firstOrNull().orEmpty()
            val commitMessage = "Haive: ${firstObjectiveLine.take(68)}".trimEnd()

            git(workspace, "add", "--all").requireSuccess("stage workspace changes")
            val hooksDir = withContext(Dispatchers.IO) {
                Files.createTempDirectory("haive-empty-hooks-").toFile()
            }
            val commit = try {
                git(
                    workspace,
                    "-c",
                    "core.hooksPath=${hooksDir.absolutePath}",
                    "-c",
                    "user.name=Haive",
                    "-c",
                    "user.email=haive@local.invalid",
                    "commit",
                    "-m",
                    commitMessage,
                ).requireSuccess("commit workspace changes")
            } finally {
                withContext(Dispatchers.IO) { runCatching { hooksDir.deleteRecursively() } }
            }
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
                add(
                    ProviderArtifact(
                        kind = ArtifactKind.CommandOutput,
                        label = "Host execution withheld",
                        textContent =
                            "Changes were committed on $branch. Repository code was not executed on the desktop host; " +
                                "verification requires a sandboxed test-capable provider.",
                        mediaType = "text/plain",
                        metadata = mapOf(
                            "branch" to branch,
                            "baseCommitId" to baseCommit,
                            "headCommit" to headCommit,
                            "executionPolicy" to "no-host-repository-code",
                        ),
                    ),
                )
            }
            WorkspaceOutcome(
                artifacts = artifacts,
                generationUsage = generation,
            )
        } finally {
            if (worktreeAdded) {
                try {
                    git(root, "worktree", "remove", "--force", workspace.absolutePath)
                } catch (_: Throwable) {
                    // Best-effort cleanup only. The branch/commit remain durable in the source repository.
                }
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
        val approvedText = result.text.trim().take(8_000)
        return WorkspacePlan(
            text = approvedText,
            requestedFiles = parseRequestedFiles(approvedText, trackedFiles),
        ) to result
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
        appendLine(plan.text)
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

    private suspend fun resolveBaseCommit(request: AgentTaskRequest, root: File): String {
        val upstreamCommit = request.contextArtifacts
            .asSequence()
            .mapNotNull { it.metadata["headCommit"]?.takeIf(String::isNotBlank) }
            .lastOrNull()
        if (upstreamCommit != null) {
            git(root, "cat-file", "-e", "$upstreamCommit^{commit}")
                .requireSuccess("resolve upstream dependency commit $upstreamCommit")
            return upstreamCommit
        }
        return git(root, "rev-parse", "HEAD")
            .requireSuccess("read repository HEAD")
            .output
            .trim()
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
        val lines = raw.trim().lines().toMutableList()
        if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(0)
        if (lines.lastOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(lines.lastIndex)
        val text = lines.joinToString("\n").trim()
        val start = text.indexOf("diff --git ")
        val diff = if (start >= 0) text.substring(start) else ""
        return if (diff.isBlank()) "" else diff.trimEnd() + "\n"
    }

    private fun validatePatchPaths(patch: String) {
        patch.lineSequence()
            .filter { it.startsWith("diff --git ") }
            .forEach { header ->
                val parts = header.split(' ')
                require(parts.size >= 4) { "Generated diff contains an invalid file header" }
                listOf(parts[2].removePrefix("a/"), parts[3].removePrefix("b/")).forEach { path ->
                    require(path.isNotBlank()) { "Generated diff contains an empty path" }
                    require(!path.startsWith('/') && !path.startsWith(".git/") && ".." !in path.split('/')) {
                        "Generated diff attempts to write outside the repository: $path"
                    }
                    require(path !in setOf(".gitattributes", ".gitmodules")) {
                        "Generated diff may not modify Git execution-control file: $path"
                    }
                }
            }
    }

    private fun workspaceBranch(request: AgentTaskRequest): String =
        "haive/${safeSegment(request.taskRunId.value).take(40)}-${UUID.randomUUID().toString().take(12)}"

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
                .apply { if (directory != null) this.directory(directory) }
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
        val timeoutSeconds = timeoutMinutes?.let(TimeUnit.MINUTES::toSeconds) ?: PROCESS_TIMEOUT_SECONDS
        val finished = try {
            runInterruptible(Dispatchers.IO) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }
        } catch (failure: CancellationException) {
            destroyProcessTree(process)
            throw failure
        }
        if (!finished) {
            destroyProcessTree(process)
        }
        ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.await().trim(),
            timedOut = !finished,
        )
    }

    private fun destroyProcessTree(process: Process) {
        runCatching {
            process.toHandle().descendants().forEach { child ->
                runCatching { child.destroyForcibly() }
            }
        }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(10, TimeUnit.SECONDS) }
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
