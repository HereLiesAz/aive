package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface GitLabWorkspaceTokenProvider {
    suspend fun getToken(): String
}

/**
 * Governed coding provider for linked GitLab repositories.
 *
 * The model receives a bounded tree and selected file contents, then returns structured full-file
 * actions. Haive validates those actions and commits them atomically to a dedicated GitLab branch.
 * No shell or test capability is claimed because this provider does not execute repository code.
 */
class GitLabWorkspaceAgentProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    private val api: TextGenerationApi,
    private val tokenProvider: GitLabWorkspaceTokenProvider,
    private val httpClient: HttpClient = defaultGitLabWorkspaceHttpClient(),
    private val json: Json = gitLabWorkspaceJson,
) : AgentProvider {
    private enum class Phase { AwaitingApproval, Ready, Cancelled }

    private data class WorkspacePlan(
        val text: String,
        val requestedFiles: List<String>,
    )

    private data class WorkspaceSnapshot(
        val text: String,
        val completePaths: Set<String>,
    )

    private data class Session(
        val request: AgentTaskRequest,
        val phase: MutableStateFlow<Phase>,
        var plan: WorkspacePlan? = null,
        var planUsage: TextGenerationResult? = null,
    )

    private data class RepositoryContext(
        val repository: RepositoryRef,
        val apiBase: String,
        val projectPath: String,
        val defaultBranch: String,
        val webUrl: String,
        val trackedFiles: List<String>,
    )

    private companion object {
        const val MAX_TREE_PATHS = 700
        const val MAX_TREE_PAGES = 7
        const val TREE_PAGE_SIZE = 100
        const val MAX_SELECTED_FILES = 24
        const val MAX_FILE_CHARS = 40_000
        const val MAX_SNAPSHOT_CHARS = 180_000
        const val MAX_ACTIONS = 24
        const val MAX_ACTION_CONTENT_CHARS = 120_000
        const val MAX_TOTAL_ACTION_CONTENT_CHARS = 500_000
        const val MAX_CONTEXT_ARTIFACT_CHARS = 30_000
        const val MAX_PLAN_PREVIEW_CHARS = 8_000

        val sessionsMutex = Mutex()
        val sessionsByProvider = mutableMapOf<String, MutableMap<ProviderRunId, Session>>()
        val nextSequenceByProvider = mutableMapOf<String, Long>()
    }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.TestAuthoring,
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
        ),
    )

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository?.source == RepositorySource.GitLab

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        require(supportsRepository(request.repository)) {
            "$displayName GitLab workspace agent requires a linked GitLab repository"
        }
        val runId = sessionsMutex.withLock {
            val nextSequence = (nextSequenceByProvider[id.value] ?: 0L) + 1L
            nextSequenceByProvider[id.value] = nextSequence
            ProviderRunId("${id.value}/${request.taskRunId.value}/$nextSequence").also { providerRunId ->
                sessionsByProvider.getOrPut(id.value) { mutableMapOf() }[providerRunId] = Session(
                request = request,
                    phase = MutableStateFlow(if (request.requirePlanApproval) Phase.AwaitingApproval else Phase.Ready),
                )
            }
        }
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = session(runId)
        try {
            if (session.phase.value == Phase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName GitLab workspace session cancelled"))
                return@flow
            }

            if (isSpecificationTask(session.request)) {
                if (session.request.requirePlanApproval) {
                    if (session.phase.value == Phase.AwaitingApproval) {
                        emit(
                            AgentEvent.PlanGenerated(
                                runId,
                                "Design a pre-code verification contract for: ${session.request.objective.trim()}".take(MAX_PLAN_PREVIEW_CHARS),
                            ),
                        )
                        val phase = session.phase.filter { it != Phase.AwaitingApproval }.first()
                        if (phase == Phase.Cancelled) {
                            emit(AgentEvent.Failed(runId, "$displayName GitLab workspace session cancelled"))
                            return@flow
                        }
                    }
                    emit(AgentEvent.PlanApproved(runId))
                }
                if (session.phase.value == Phase.Cancelled) {
                    emit(AgentEvent.Failed(runId, "$displayName GitLab workspace session cancelled"))
                    return@flow
                }
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
                                    "source" to RepositorySource.GitLab.name,
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

            if (!session.request.requirePlanApproval) {
                emit(AgentEvent.Progress(runId, "Reading linked GitLab repository"))
            }
            val token = tokenProvider.requireToken()
            val context = loadRepositoryContext(session.request, token)

            var plan = session.plan
            if (plan == null) {
                val generated = generatePlan(session.request, context)
                plan = generated.first
                session.plan = plan
                session.planUsage = generated.second
            }
            val approvedPlan = requireNotNull(plan)

            if (session.request.requirePlanApproval && session.phase.value == Phase.AwaitingApproval) {
                emit(AgentEvent.PlanGenerated(runId, approvedPlan.text))
                val phase = session.phase.filter { it != Phase.AwaitingApproval }.first()
                if (phase == Phase.Cancelled) {
                    emit(AgentEvent.Failed(runId, "$displayName GitLab workspace session cancelled"))
                    return@flow
                }
                emit(AgentEvent.PlanApproved(runId))
                emit(AgentEvent.Progress(runId, "Reading linked GitLab repository"))
            } else if (session.request.requirePlanApproval) {
                emit(AgentEvent.PlanApproved(runId))
                emit(AgentEvent.Progress(runId, "Reading linked GitLab repository"))
            }

            if (session.phase.value == Phase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName GitLab workspace session cancelled"))
                return@flow
            }

            val selectedFiles = selectFiles(approvedPlan, context.trackedFiles, session.request)
            val snapshot = loadSnapshot(context, token, selectedFiles)
            require(snapshot.text.isNotBlank()) { "No readable GitLab source files were selected for the workspace task" }

            val mutationRequested =
                session.request.requiredCapabilities.isEmpty() ||
                    AgentCapability.RepositoryWrite in session.request.requiredCapabilities
            if (!mutationRequested) {
                emit(AgentEvent.Progress(runId, "Reviewing selected GitLab files"))
                val review = api.generate(
                    reviewPrompt(session.request, approvedPlan, context, snapshot.text),
                )
                emit(
                    AgentEvent.ArtifactProduced(
                        runId,
                        ProviderArtifact(
                            kind = ArtifactKind.Review,
                            label = "GitLab repository review",
                            uri = context.webUrl,
                            textContent = review.text.trim(),
                            mediaType = "text/markdown",
                            metadata = mapOf(
                                "provider" to id.value,
                                "source" to RepositorySource.GitLab.name,
                                "baseBranch" to context.defaultBranch,
                                "repositoryUrl" to context.webUrl,
                            ),
                        ),
                    ),
                )
                val inputTokens = listOfNotNull(session.planUsage?.inputTokens, review.inputTokens).sumOrNull()
                val outputTokens = listOfNotNull(session.planUsage?.outputTokens, review.outputTokens).sumOrNull()
                if (inputTokens != null || outputTokens != null) {
                    emit(AgentEvent.UsageReported(runId, inputTokens = inputTokens, outputTokens = outputTokens))
                }
                emit(AgentEvent.Completed(runId))
                return@flow
            }

            emit(AgentEvent.Progress(runId, "Generating validated GitLab file changes"))
            val generation = api.generate(changePrompt(session.request, approvedPlan, context, snapshot.text))
            val changeSet = parseChangeSet(generation.text)
            validateChangeSet(
                changeSet = changeSet,
                trackedFiles = context.trackedFiles.toSet(),
                editablePaths = snapshot.completePaths,
            )

            val branch = workspaceBranch(session.request, runId)
            emit(AgentEvent.Progress(runId, "Creating GitLab branch $branch"))
            createBranch(context, token, branch)

            emit(AgentEvent.Progress(runId, "Committing ${changeSet.actions.size} GitLab file action(s)"))
            val commit = commitActions(context, token, branch, changeSet)
            val diff = try {
                readCommitDiff(context, token, commit.id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                ""
            }
            val compareUrl = "${context.webUrl}/-/compare/${context.defaultBranch}...$branch"

            emit(
                AgentEvent.ArtifactProduced(
                    runId,
                    ProviderArtifact(
                        kind = ArtifactKind.CodeChange,
                        label = changeSet.commitMessage,
                        uri = commit.webUrl ?: compareUrl,
                        textContent = diff.ifBlank {
                            changeSet.actions.joinToString("\n") { action ->
                                "${action.action.uppercase()}: ${action.filePath}"
                            }
                        },
                        mediaType = if (diff.isBlank()) "text/plain" else "text/x-diff",
                        metadata = mapOf(
                            "provider" to id.value,
                            "source" to RepositorySource.GitLab.name,
                            "branch" to branch,
                            "baseBranch" to context.defaultBranch,
                            "headCommit" to commit.id,
                            "repositoryUrl" to context.webUrl,
                            "compareUrl" to compareUrl,
                            "actionCount" to changeSet.actions.size.toString(),
                        ),
                    ),
                ),
            )

            val inputTokens = listOfNotNull(session.planUsage?.inputTokens, generation.inputTokens).sumOrNull()
            val outputTokens = listOfNotNull(session.planUsage?.outputTokens, generation.outputTokens).sumOrNull()
            if (inputTokens != null || outputTokens != null) {
                emit(AgentEvent.UsageReported(runId, inputTokens = inputTokens, outputTokens = outputTokens))
            }
            emit(AgentEvent.Completed(runId))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            emit(
                AgentEvent.Failed(
                    runId,
                    failure.message?.takeIf(String::isNotBlank)
                        ?: failure::class.simpleName.orEmpty().ifBlank { "GitLab workspace execution failed" },
                ),
            )
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Rejected(
            "$displayName GitLab workspace sessions are one-shot governed tasks; start a new task for follow-up work.",
        )

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val session = sessionOrNull(runId)
            ?: return ProviderActionResult.Rejected("GitLab workspace session ${runId.value} is not available")
        if (session.phase.value == Phase.Cancelled) {
            return ProviderActionResult.Rejected("GitLab workspace session ${runId.value} is cancelled")
        }
        session.phase.value = Phase.Ready
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        sessionOrNull(runId)?.phase?.value = Phase.Cancelled
        return ProviderActionResult.Accepted
    }

    private fun isSpecificationTask(request: AgentTaskRequest): Boolean {
        val specificationArtifacts = setOf(
            ArtifactKind.AcceptanceTestPlan,
            ArtifactKind.BehavioralTest,
            ArtifactKind.ContractTest,
            ArtifactKind.FailureScenario,
        )
        return request.requiredArtifacts.isNotEmpty() &&
            request.requiredArtifacts.all(specificationArtifacts::contains)
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

    private suspend fun loadRepositoryContext(request: AgentTaskRequest, token: String): RepositoryContext {
        val repository = requireNotNull(request.repository)
        val apiBase = repository.gitLabApiBase()
        val projectPath = repository.displayName().encodeURLPathPart()
        val projectBody = httpClient.get("$apiBase/projects/$projectPath") {
            gitLabHeaders(token)
        }.requireSuccessBody("read GitLab project ${repository.displayName()}")
        val project = json.decodeFromString<GitLabWorkspaceProjectResponse>(projectBody)
        val defaultBranch = repository.defaultBranch?.takeIf(String::isNotBlank) ?: project.defaultBranch
        val trackedFiles = loadTree(apiBase, projectPath, token, defaultBranch)
        require(trackedFiles.isNotEmpty()) { "GitLab repository has no readable tracked files" }
        return RepositoryContext(
            repository = repository,
            apiBase = apiBase,
            projectPath = projectPath,
            defaultBranch = defaultBranch,
            webUrl = project.webUrl,
            trackedFiles = trackedFiles,
        )
    }

    private suspend fun loadTree(
        apiBase: String,
        projectPath: String,
        token: String,
        branch: String,
    ): List<String> {
        val files = mutableListOf<String>()
        for (page in 1..MAX_TREE_PAGES) {
            val body = httpClient.get("$apiBase/projects/$projectPath/repository/tree") {
                gitLabHeaders(token)
                parameter("ref", branch)
                parameter("recursive", true)
                parameter("per_page", TREE_PAGE_SIZE)
                parameter("page", page)
            }.requireSuccessBody("read GitLab repository tree")
            val entries = json.decodeFromString<List<GitLabWorkspaceTreeEntry>>(body)
            files += entries.asSequence()
                .filter { it.type == "blob" }
                .map { it.path }
                .filter(::isSafePath)
                .toList()
            if (entries.size < TREE_PAGE_SIZE || files.size >= MAX_TREE_PATHS) break
        }
        return files.distinct().take(MAX_TREE_PATHS)
    }

    private suspend fun generatePlan(
        request: AgentTaskRequest,
        context: RepositoryContext,
    ): Pair<WorkspacePlan, TextGenerationResult> {
        val result = api.generate(planPrompt(request, context))
        val fullText = result.text.trim()
        val approvedText = fullText.take(MAX_PLAN_PREVIEW_CHARS)
        return WorkspacePlan(
            text = approvedText,
            requestedFiles = parseRequestedFiles(fullText, context.trackedFiles),
        ) to result
    }

    private fun planPrompt(request: AgentTaskRequest, context: RepositoryContext): String = buildString {
        appendLine("You are planning a real code change in a GitLab repository. Do not claim changes yet.")
        appendLine("Repository: ${context.repository.displayName()}")
        appendLine("Base branch: ${context.defaultBranch}")
        appendLine("Task: ${request.objective.trim()}")
        appendLine("Role instructions: ${request.roleInstructions.trim()}")
        if (request.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            request.acceptanceCriteria.forEach { appendLine("- ${it.description.trim()}") }
        }
        appendLine()
        appendLine("Tracked files:")
        context.trackedFiles.forEach { appendLine(it) }
        appendLine()
        appendLine("Return a concise implementation plan, then a line containing exactly FILES:, followed by up to $MAX_SELECTED_FILES tracked file paths to inspect, one per line. Do not use Markdown code fences.")
    }

    private suspend fun loadSnapshot(
        context: RepositoryContext,
        token: String,
        paths: List<String>,
    ): WorkspaceSnapshot {
        var remaining = MAX_SNAPSHOT_CHARS
        val completePaths = linkedSetOf<String>()
        val rendered = buildString {
            for (path in paths) {
                if (remaining <= 0) break
                val body = httpClient.get(
                    "${context.apiBase}/projects/${context.projectPath}/repository/files/${path.encodeURLPathPart()}/raw",
                ) {
                    gitLabHeaders(token)
                    parameter("ref", context.defaultBranch)
                }.requireSuccessBody("read GitLab file $path")
                val limit = minOf(MAX_FILE_CHARS, remaining)
                val complete = body.length <= limit
                val content = body.take(limit)
                appendLine("===== FILE: $path =====")
                appendLine(content)
                if (!complete) {
                    appendLine("[TRUNCATED BY HAIVE — THIS FILE IS READ-ONLY IN THIS TASK]")
                } else {
                    completePaths += path
                }
                appendLine("===== END FILE =====")
                remaining -= content.length
            }
        }
        return WorkspaceSnapshot(rendered, completePaths)
    }

    private fun changePrompt(
        request: AgentTaskRequest,
        plan: WorkspacePlan,
        context: RepositoryContext,
        snapshot: String,
    ): String = buildString {
        appendLine("You are editing a real GitLab repository through Haive's validated commit API.")
        appendLine("Return ONLY one JSON object. Do not use Markdown fences or commentary.")
        appendLine("Schema:")
        appendLine("{\"commitMessage\":\"concise message\",\"actions\":[{\"action\":\"create|update|delete\",\"filePath\":\"relative/path\",\"content\":\"full file content for create/update; omit or empty for delete\"}]}")
        appendLine("Use full replacement file contents, not patches. Keep the change focused. Maximum $MAX_ACTIONS actions.")
        appendLine()
        appendLine("REPOSITORY: ${context.repository.displayName()}")
        appendLine("BASE BRANCH: ${context.defaultBranch}")
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
        appendLine("TRACKED FILES")
        context.trackedFiles.forEach { appendLine(it) }
        appendLine()
        appendLine("SELECTED FILE CONTENT")
        append(snapshot)
    }

    private fun reviewPrompt(
        request: AgentTaskRequest,
        plan: WorkspacePlan,
        context: RepositoryContext,
        snapshot: String,
    ): String = buildString {
        appendLine("Review the selected GitLab repository content without modifying the repository.")
        appendLine("Repository: ${context.repository.displayName()}")
        appendLine("Base branch: ${context.defaultBranch}")
        appendLine("Task: ${request.objective.trim()}")
        appendLine("Role instructions: ${request.roleInstructions.trim()}")
        appendLine()
        appendLine("APPROVED PLAN")
        appendLine(plan.text)
        appendLine()
        appendLine("SELECTED FILE CONTENT")
        append(snapshot)
        appendLine()
        appendLine("Return concise review evidence only. Do not propose or claim repository mutations.")
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

    private fun parseChangeSet(raw: String): GitLabWorkspaceChangeSet {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n').substringBeforeLast("```").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "$displayName did not return a JSON GitLab change set" }
        return json.decodeFromString(text.substring(start, end + 1))
    }

    private fun validateChangeSet(
        changeSet: GitLabWorkspaceChangeSet,
        trackedFiles: Set<String>,
        editablePaths: Set<String>,
    ) {
        require(changeSet.commitMessage.isNotBlank()) { "GitLab change set requires a commit message" }
        require(changeSet.actions.isNotEmpty()) { "GitLab change set did not contain any file actions" }
        require(changeSet.actions.size <= MAX_ACTIONS) { "GitLab change set exceeds the $MAX_ACTIONS-action limit" }
        require(changeSet.actions.map { it.filePath }.distinct().size == changeSet.actions.size) {
            "GitLab change set contains duplicate file paths"
        }
        var totalContent = 0
        changeSet.actions.forEach { action ->
            require(isSafePath(action.filePath)) { "GitLab change set contains an unsafe path: ${action.filePath}" }
            when (action.action.lowercase()) {
                "create" -> {
                    require(action.filePath !in trackedFiles) { "GitLab create action targets an existing file: ${action.filePath}" }
                    require(action.content != null) { "GitLab create action requires content: ${action.filePath}" }
                }
                "update" -> {
                    require(action.filePath in trackedFiles) { "GitLab update action targets a missing file: ${action.filePath}" }
                    require(action.filePath in editablePaths) {
                        "GitLab update action targets a file whose complete contents were not loaded: ${action.filePath}"
                    }
                    require(action.content != null) { "GitLab update action requires content: ${action.filePath}" }
                }
                "delete" -> {
                    require(action.filePath in trackedFiles) {
                        "GitLab delete action targets a missing file: ${action.filePath}"
                    }
                    require(action.filePath in editablePaths) {
                        "GitLab delete action targets a file whose complete contents were not loaded: ${action.filePath}"
                    }
                }
                else -> error("Unsupported GitLab file action '${action.action}'")
            }
            val size = action.content?.length ?: 0
            require(size <= MAX_ACTION_CONTENT_CHARS) { "GitLab file action is too large: ${action.filePath}" }
            totalContent += size
        }
        require(totalContent <= MAX_TOTAL_ACTION_CONTENT_CHARS) {
            "GitLab change set exceeds the total content limit"
        }
    }

    private suspend fun createBranch(context: RepositoryContext, token: String, branch: String) {
        httpClient.post("${context.apiBase}/projects/${context.projectPath}/repository/branches") {
            gitLabHeaders(token)
            parameter("branch", branch)
            parameter("ref", context.defaultBranch)
        }.requireSuccessBody("create GitLab workspace branch $branch")
    }

    private suspend fun commitActions(
        context: RepositoryContext,
        token: String,
        branch: String,
        changeSet: GitLabWorkspaceChangeSet,
    ): GitLabWorkspaceCommitResponse {
        val body = httpClient.post("${context.apiBase}/projects/${context.projectPath}/repository/commits") {
            gitLabHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    GitLabWorkspaceCommitBody(
                        branch = branch,
                        commitMessage = changeSet.commitMessage.trim().take(200),
                        actions = changeSet.actions.map { action ->
                            GitLabWorkspaceCommitAction(
                                action = action.action.lowercase(),
                                filePath = action.filePath,
                                content = action.content?.takeIf { action.action.lowercase() != "delete" },
                            )
                        },
                    ),
                ),
            )
        }.requireSuccessBody("commit GitLab workspace changes")
        return json.decodeFromString(body)
    }

    private suspend fun readCommitDiff(context: RepositoryContext, token: String, commitId: String): String {
        val body = httpClient.get(
            "${context.apiBase}/projects/${context.projectPath}/repository/commits/${commitId.encodeURLPathPart()}/diff",
        ) {
            gitLabHeaders(token)
            parameter("per_page", MAX_ACTIONS)
        }.requireSuccessBody("read GitLab workspace commit diff")
        return json.decodeFromString<List<GitLabWorkspaceDiffResponse>>(body)
            .joinToString("\n") { item ->
                buildString {
                    append("diff --git a/${item.oldPath} b/${item.newPath}\n")
                    append(item.diff.trimEnd())
                    append('\n')
                }
            }
            .trim()
    }

    private fun workspaceBranch(request: AgentTaskRequest, runId: ProviderRunId): String {
        val task = safeSegment(request.taskRunId.value).take(40)
        val suffix = safeSegment(runId.value.substringAfterLast('/')).takeLast(12)
        return "haive/$task-$suffix"
    }

    private fun safeSegment(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "task" }

    private fun isSafePath(path: String): Boolean {
        val clean = path.trim()
        return clean.isNotBlank() &&
            !clean.startsWith('/') &&
            !clean.startsWith(".git/") &&
            clean.none { it == '\u0000' || it == '\n' || it == '\r' } &&
            ".." !in clean.split('/')
    }

    private suspend fun session(runId: ProviderRunId): Session =
        sessionOrNull(runId) ?: error("$displayName GitLab workspace session ${runId.value} is not available")

    private suspend fun sessionOrNull(runId: ProviderRunId): Session? = sessionsMutex.withLock {
        sessionsByProvider[id.value]?.get(runId)
    }

    private fun HttpRequestBuilder.gitLabHeaders(token: String) {
        header("PRIVATE-TOKEN", token)
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }

    private fun List<Long>.sumOrNull(): Long? = if (isEmpty()) null else sum()
}

private suspend fun GitLabWorkspaceTokenProvider.requireToken(): String =
    getToken().trim().also { require(it.isNotEmpty()) { "GitLab repository credential is not configured" } }

private fun RepositoryRef.gitLabApiBase(): String {
    val remote = remoteUrl.orEmpty().trim()
    val host = when {
        remote.startsWith("https://", ignoreCase = true) ->
            remote.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
        remote.startsWith("git@", ignoreCase = true) ->
            remote.substringAfter('@').substringBefore(':')
        remote.startsWith("ssh://", ignoreCase = true) ->
            remote.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
        remote.isBlank() -> "gitlab.com"
        else -> error("GitLab workspace credentials require an HTTPS or SSH gitlab.com repository locator")
    }
    require(host.equals("gitlab.com", ignoreCase = true)) {
        "GitLab workspace credentials are configured only for gitlab.com"
    }
    return "https://gitlab.com/api/v4"
}

private suspend fun HttpResponse.requireSuccessBody(operation: String): String {
    val body = bodyAsText()
    if (status.value !in 200..299) {
        error(
            "Unable to $operation: HTTP ${status.value}" +
                body.take(500).takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
        )
    }
    return body
}

private fun defaultGitLabWorkspaceHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000L
        connectTimeoutMillis = 10_000L
    }
}

private val gitLabWorkspaceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class GitLabWorkspaceProjectResponse(
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("web_url") val webUrl: String,
)

@Serializable
private data class GitLabWorkspaceTreeEntry(
    val path: String,
    val type: String,
)

@Serializable
private data class GitLabWorkspaceChangeSet(
    val commitMessage: String,
    val actions: List<GitLabWorkspaceRequestedAction>,
)

@Serializable
private data class GitLabWorkspaceRequestedAction(
    val action: String,
    val filePath: String,
    val content: String? = null,
)

@Serializable
private data class GitLabWorkspaceCommitBody(
    val branch: String,
    @SerialName("commit_message") val commitMessage: String,
    val actions: List<GitLabWorkspaceCommitAction>,
)

@Serializable
private data class GitLabWorkspaceCommitAction(
    val action: String,
    @SerialName("file_path") val filePath: String,
    val content: String? = null,
)

@Serializable
private data class GitLabWorkspaceCommitResponse(
    val id: String,
    @SerialName("web_url") val webUrl: String? = null,
)

@Serializable
private data class GitLabWorkspaceDiffResponse(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    val diff: String,
)
