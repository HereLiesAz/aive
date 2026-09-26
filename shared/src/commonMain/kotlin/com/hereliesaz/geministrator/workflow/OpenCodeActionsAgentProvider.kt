package com.hereliesaz.geministrator.workflow

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
import com.hereliesaz.geministrator.resources.Res
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.synth.kmpzip.zip.ZipInputStream
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.random.Random

/** The workflow The Aive installs in a GitHub repository to run OpenCode. */
object OpenCodeAgentWorkflow {
    const val FILE_NAME = "aive-opencode-agent.yml"
    const val PATH = ".github/workflows/$FILE_NAME"
    const val TASK_INPUT = "aive_task"

    /** Free OpenCode Zen model; runs inside OpenCode with no key. */
    const val DEFAULT_MODEL = "opencode/big-pickle"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun template(): String = Res.readBytes("files/$FILE_NAME").decodeToString()
}

/**
 * Runs the open-source OpenCode agent on GitHub Actions against a linked GitHub repository.
 *
 * Unlike Jules, every agent step (file read, command, edit, message) streams back: the runner
 * writes them to a check run that this provider polls. The workflow file is installed, and kept in
 * step with this app version, on the repository's default branch before the first dispatch.
 * Changes come back as a pushed branch plus a patch artifact.
 */
class OpenCodeActionsAgentProvider(
    private val client: OpenCodeRunnerClient,
    private val model: String = OpenCodeAgentWorkflow.DEFAULT_MODEL,
    private val workflowTemplate: suspend () -> String = OpenCodeAgentWorkflow::template,
    private val pollIntervalMillis: Long = 3_000L,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AgentProvider {
    override val id: AgentProviderId = AgentProviderId(ID)

    private class Session(
        val request: AgentTaskRequest,
        val approved: MutableStateFlow<Boolean>,
        var cancelled: Boolean = false,
        var githubRunId: String? = null,
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, Session>()

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
    )

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository?.source == RepositorySource.GitHub

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        require(supportsRepository(request.repository)) { "OpenCode runs against a linked GitHub repository" }
        val suffix = Random.nextLong().toULong().toString(36)
        val runId = ProviderRunId("$ID:${request.taskRunId.value}:$suffix")
        mutex.withLock {
            sessions[runId] = Session(request, MutableStateFlow(!request.requirePlanApproval))
        }
        return AgentRunHandle(runId)
    }

    override suspend fun reconnect(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        planGenerated: Boolean,
        planApproved: Boolean,
        planPreview: String?,
    ): ProviderActionResult {
        if (!supportsRepository(request.repository)) {
            return ProviderActionResult.Rejected("OpenCode cannot resume without its linked GitHub repository")
        }
        mutex.withLock {
            sessions.getOrPut(runId) {
                Session(request, MutableStateFlow(!request.requirePlanApproval || planApproved))
            }
        }
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = mutex.withLock { sessions[runId] } ?: error("Unknown OpenCode run ${runId.value}")
        val repository = requireNotNull(session.request.repository)
        val runName = runName(runId)

        var githubRunId = session.githubRunId ?: client.findRun(repository, runName)?.id
        if (githubRunId == null) {
            if (!session.approved.value) {
                emit(AgentEvent.PlanGenerated(runId, planSummary(session.request)))
                session.approved.first { it }
                emit(AgentEvent.PlanApproved(runId))
            }
            if (session.cancelled) {
                emit(AgentEvent.Failed(runId, "OpenCode run cancelled before dispatch"))
                return@flow
            }
            githubRunId = dispatch(runId, session.request, repository, runName)
        }
        session.githubRunId = githubRunId
        followRun(runId, repository, githubRunId, runName)
    }

    private suspend fun FlowCollector<AgentEvent>.dispatch(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        repository: RepositoryRef,
        runName: String,
    ): String {
        val branch = repository.defaultBranch ?: client.defaultBranch(repository)
        val template = workflowTemplate()
        val installed = client.workflowFile(repository, branch)
        if (installed != template) {
            emit(
                AgentEvent.Progress(
                    runId,
                    if (installed == null) "Installing ${OpenCodeAgentWorkflow.PATH}" else "Updating ${OpenCodeAgentWorkflow.PATH}",
                ),
            )
            client.writeWorkflowFile(
                repository = repository,
                branch = branch,
                content = template,
                message = "Add The Aive's OpenCode agent workflow",
            )
        }
        val task = json.encodeToString(
            OpenCodeTask.serializer(),
            OpenCodeTask(
                checkName = runName,
                model = model,
                prompt = renderPrompt(request),
                branch = "aive/opencode-${request.taskRunId.value}-${runId.value.substringAfterLast(':')}"
                    .replace(Regex("[^A-Za-z0-9._/-]"), "-"),
                commitMessage = request.objective.lineSequence().first().take(72).ifBlank { "OpenCode changes" },
            ),
        )
        emit(AgentEvent.Progress(runId, "Starting OpenCode on GitHub Actions ($model)"))
        // A freshly committed workflow can take a few seconds to become dispatchable.
        var lastError: Throwable? = null
        repeat(DISPATCH_ATTEMPTS) { attempt ->
            try {
                return client.dispatch(repository, branch, task)
            } catch (error: IllegalStateException) {
                lastError = error
                if (attempt < DISPATCH_ATTEMPTS - 1) delay(DISPATCH_RETRY_MILLIS)
            }
        }
        throw lastError ?: IllegalStateException("OpenCode dispatch failed")
    }

    private suspend fun FlowCollector<AgentEvent>.followRun(
        runId: ProviderRunId,
        repository: RepositoryRef,
        githubRunId: String,
        runName: String,
    ) {
        var lastSeq = 0
        var queuedReported = false
        while (true) {
            val run = client.getRun(repository, githubRunId)
            run.headSha?.let { sha ->
                client.checkRunText(repository, sha, runName)?.let { text ->
                    parseSteps(text).filter { it.first > lastSeq }.forEach { (seq, message) ->
                        emit(AgentEvent.Progress(runId, message))
                        lastSeq = seq
                    }
                }
            }
            when (run.status) {
                GitHubWorkflowRunStatus.Queued -> if (!queuedReported) {
                    emit(AgentEvent.Progress(runId, "Waiting for a GitHub Actions runner"))
                    queuedReported = true
                }
                GitHubWorkflowRunStatus.Running -> Unit
                GitHubWorkflowRunStatus.Completed, GitHubWorkflowRunStatus.Failed -> {
                    finish(runId, repository, run)
                    return
                }
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun FlowCollector<AgentEvent>.finish(
        runId: ProviderRunId,
        repository: RepositoryRef,
        run: GitHubWorkflowRun,
    ) {
        val result = run.artifacts.firstOrNull { it.name == RESULT_ARTIFACT }
            ?.let { artifact -> readResult(client.downloadArtifact(repository, artifact.id)) }
        if (result == null) {
            emit(AgentEvent.Failed(runId, "OpenCode run ended without a result: ${run.progressMessage ?: run.status}"))
            return
        }
        if (result.inputTokens != null || result.outputTokens != null) {
            emit(AgentEvent.UsageReported(runId, inputTokens = result.inputTokens, outputTokens = result.outputTokens))
        }
        result.summary?.takeIf(String::isNotBlank)?.let { emit(AgentEvent.Message(runId, it)) }
        result.branch?.let { branch ->
            emit(
                AgentEvent.ArtifactProduced(
                    runId,
                    ProviderArtifact(
                        kind = ArtifactKind.CodeChange,
                        label = "OpenCode changes on $branch",
                        uri = "https://github.com/${repository.owner}/${repository.name}/tree/$branch",
                        textContent = result.patch,
                        mediaType = "text/x-diff",
                        metadata = mapOf("branch" to branch, "githubRunId" to run.id),
                    ),
                ),
            )
        }
        if (result.status == "completed" && run.status == GitHubWorkflowRunStatus.Completed) {
            emit(AgentEvent.Completed(runId))
        } else {
            emit(AgentEvent.Failed(runId, result.message ?: "OpenCode run failed"))
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Rejected("OpenCode runs headless; follow-up instructions need a new task")

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val session = mutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Rejected("Unknown OpenCode run ${runId.value}")
        session.approved.value = true
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        val session = mutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Rejected("Unknown OpenCode run ${runId.value}")
        session.cancelled = true
        session.approved.value = true
        val repository = session.request.repository ?: return ProviderActionResult.Accepted
        val githubRunId = session.githubRunId ?: client.findRun(repository, runName(runId))?.id
        githubRunId?.let { client.cancelRun(repository, it) }
        return ProviderActionResult.Accepted
    }

    private fun readResult(archive: ByteArray): OpenCodeResult? {
        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == RESULT_FILE) {
                    return json.decodeFromString(OpenCodeResult.serializer(), zip.readBytes().decodeToString())
                }
                zip.closeEntry()
            }
        }
        return null
    }

    private fun planSummary(request: AgentTaskRequest): String = buildString {
        append("OpenCode ($model) will work on GitHub Actions:\n")
        append(request.objective.trim())
        if (request.acceptanceCriteria.isNotEmpty()) {
            append("\n\nAcceptance criteria:\n")
            request.acceptanceCriteria.forEachIndexed { index, criterion ->
                append("${index + 1}. ${criterion.description.trim()}\n")
            }
        }
    }.trim().take(8_000)

    private fun renderPrompt(request: AgentTaskRequest): String = buildString {
        append("ROLE INSTRUCTIONS\n")
        append(request.roleInstructions.trim())
        append("\n\n")
        request.promptContext.stablePrefix.forEach { block ->
            append(block.label.uppercase()).append('\n').append(block.content.trim()).append("\n\n")
        }
        append("TASK\n")
        append(request.objective.trim())
        append("\n\n")
        if (request.acceptanceCriteria.isNotEmpty()) {
            append("ACCEPTANCE CRITERIA\n")
            request.acceptanceCriteria.forEachIndexed { index, criterion ->
                append("${index + 1}. ${criterion.description.trim()}\n")
            }
            append('\n')
        }
        request.promptContext.dynamicContext.forEach { block ->
            append(block.label.uppercase()).append('\n').append(block.content.trim()).append("\n\n")
        }
        append("Work directly in this repository checkout. Do not commit or push; the runner does.")
    }.trim().take(MAX_PROMPT_CHARS)

    @Serializable
    private data class OpenCodeTask(
        val checkName: String,
        val model: String,
        val prompt: String,
        val branch: String,
        val commitMessage: String,
    )

    @Serializable
    private data class OpenCodeResult(
        val status: String,
        val message: String? = null,
        val branch: String? = null,
        val patch: String? = null,
        val summary: String? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
    )

    companion object {
        const val ID = "opencode"
        private const val RESULT_ARTIFACT = "aive-result"
        private const val RESULT_FILE = "aive-result.json"
        private const val DISPATCH_ATTEMPTS = 4
        private const val DISPATCH_RETRY_MILLIS = 5_000L

        // workflow_dispatch inputs are capped at 65,535 characters in total.
        private const val MAX_PROMPT_CHARS = 50_000

        /** Workflow run-name and check-run name; unique per provider run. */
        internal fun runName(runId: ProviderRunId): String = "aive-${runId.value}".take(100)

        /** Parses the runner's "seq<TAB>message" step log. */
        internal fun parseSteps(text: String): List<Pair<Int, String>> = text.lineSequence().mapNotNull { line ->
            val seq = line.substringBefore('\t', "").toIntOrNull() ?: return@mapNotNull null
            seq to line.substringAfter('\t').trim()
        }.filter { it.second.isNotEmpty() }.toList()
    }
}
