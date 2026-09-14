package com.hereliesaz.geministrator.providers.jules

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
import com.hereliesaz.geministrator.providers.IsolationHint
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


data class JulesProviderConfig(
    val pollIntervalMillis: Long = 2_000L,
    val autoCreatePullRequests: Boolean = true,
    val destructiveCancelDeletesSession: Boolean = false,
) {
    init {
        require(pollIntervalMillis >= 250L) { "pollIntervalMillis must be at least 250ms" }
    }
}

class JulesProvider(
    private val api: JulesApi,
    private val config: JulesProviderConfig = JulesProviderConfig(),
) : AgentProvider {

    override val id: AgentProviderId = AgentProviderId("jules")

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
            AgentCapability.Messaging,
            AgentCapability.ShellExecution,
            AgentCapability.Testing,
            AgentCapability.TestAuthoring,
            AgentCapability.PullRequestCreation,
        ),
        promptCaching = PromptCacheCapabilities(
            modes = setOf(PromptCacheMode.SessionScoped),
            reportsCacheUsage = false,
        ),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        val sourceContext = request.repository?.let { repository ->
            require(repository.source == RepositorySource.GitHub) {
                "Jules repository sessions currently require a GitHub-linked project; " +
                    "this project is linked to ${repository.source.displayName()}."
            }
            val source = findSource(repository)
            val branch = repository.defaultBranch
                ?: source.githubRepo?.defaultBranch?.displayName
                ?: error("Repository ${repository.owner}/${repository.name} has no default branch configured in project or Jules source")
            JulesSourceContext(
                source = source.name,
                githubRepoContext = JulesGithubRepoContext(startingBranch = branch),
            )
        }

        val repoless = request.isolationHint == IsolationHint.Repoless || request.repository == null
        val session = api.createSession(
            JulesCreateSessionRequest(
                prompt = renderPrompt(request),
                title = request.objective.take(120),
                sourceContext = if (repoless) null else sourceContext,
                requirePlanApproval = request.requirePlanApproval,
                automationMode = if (!repoless && config.autoCreatePullRequests) "AUTO_CREATE_PR" else null,
            ),
        )
        return AgentRunHandle(providerRunId = ProviderRunId(session.name))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val sessionName = runId.value
        val seenActivityIds = mutableSetOf<String>()
        val seenPullRequests = mutableSetOf<String>()
        var terminal = false

        while (!terminal) {
            val activities = api.listActivities(sessionName)
                .sortedWith(compareBy<JulesActivity> { it.createTime == null }.thenBy { it.createTime ?: "" }.thenBy { it.id })

            for (activity in activities) {
                if (!seenActivityIds.add(activity.id)) continue

                activity.planGenerated?.let { generated ->
                    emit(
                        AgentEvent.PlanGenerated(
                            runId = runId,
                            summary = generated.plan.steps.sortedBy { it.index }.joinToString("\n") { step ->
                                buildString {
                                    append(step.index + 1)
                                    append(". ")
                                    append(step.title)
                                    step.description?.takeIf { it.isNotBlank() }?.let {
                                        append(" — ")
                                        append(it)
                                    }
                                }
                            },
                        ),
                    )
                }
                if (activity.planApproved != null) emit(AgentEvent.PlanApproved(runId))
                activity.agentMessaged?.let { emit(AgentEvent.Message(runId, it.agentMessage)) }
                activity.progressUpdated?.let { progress ->
                    emit(
                        AgentEvent.Progress(
                            runId = runId,
                            message = listOfNotNull(progress.title, progress.description)
                                .filter { it.isNotBlank() }
                                .joinToString(": "),
                        ),
                    )
                }
                activity.artifacts.forEach { artifact ->
                    mapArtifact(artifact)?.let { emit(AgentEvent.ArtifactProduced(runId, it)) }
                }
                activity.sessionFailed?.let { failed ->
                    emit(AgentEvent.Failed(runId, failed.reason))
                    terminal = true
                }
                if (activity.sessionCompleted != null) {
                    emitPullRequests(runId, api.getSession(sessionName), seenPullRequests)
                    emit(AgentEvent.Completed(runId))
                    terminal = true
                }
            }

            if (!terminal) {
                val session = api.getSession(sessionName)
                when (session.state) {
                    "FAILED" -> {
                        emit(AgentEvent.Failed(runId, "Jules session failed without a failure activity."))
                        terminal = true
                    }
                    "COMPLETED" -> {
                        emitPullRequests(runId, session, seenPullRequests)
                        emit(AgentEvent.Completed(runId))
                        terminal = true
                    }
                    "AWAITING_USER_FEEDBACK" -> emit(AgentEvent.Progress(runId, "Jules is awaiting user feedback."))
                }
            }
            if (!terminal) delay(config.pollIntervalMillis)
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult = action {
        api.sendMessage(runId.value, message)
    }

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult = action {
        api.approvePlan(runId.value)
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        if (!config.destructiveCancelDeletesSession) {
            return ProviderActionResult.Rejected(
                "Jules currently documents session deletion, not non-destructive cancellation. " +
                    "Deletion is disabled so workflow evidence is preserved.",
            )
        }
        return action { api.deleteSession(runId.value) }
    }

    private suspend fun findSource(repository: RepositoryRef): JulesSource =
        api.listSources().firstOrNull { source ->
            source.githubRepo?.owner == repository.owner && source.githubRepo.repo == repository.name
        } ?: error(
            "Repository ${repository.owner}/${repository.name} is not connected as a Jules source. " +
                "Connect it in the Jules web app first.",
        )

    private fun renderPrompt(request: AgentTaskRequest): String = buildString {
        append("ROLE INSTRUCTIONS\n")
        append(request.roleInstructions.trim())
        append("\n\n")
        request.promptContext.stablePrefix.forEach { block ->
            append(block.label.uppercase())
            append("\n")
            append(block.content.trim())
            append("\n\n")
        }
        append("TASK\n")
        append(request.objective.trim())
        append("\n\n")
        if (request.acceptanceCriteria.isNotEmpty()) {
            append("ACCEPTANCE CRITERIA\n")
            request.acceptanceCriteria.forEachIndexed { index, criterion ->
                append(index + 1)
                append(". ")
                append(criterion.description.trim())
                append("\n")
            }
            append("\n")
        }
        request.promptContext.dynamicContext.forEach { block ->
            append(block.label.uppercase())
            append("\n")
            append(block.content.trim())
            append("\n\n")
        }
    }.trim()

    private fun mapArtifact(artifact: JulesArtifact): ProviderArtifact? {
        artifact.changeSet?.let { changeSet ->
            return ProviderArtifact(
                kind = ArtifactKind.CodeChange,
                label = changeSet.gitPatch.suggestedCommitMessage ?: "Jules code changes",
                textContent = changeSet.gitPatch.unidiffPatch,
                mediaType = "text/x-diff",
                metadata = buildMap {
                    put("source", changeSet.source)
                    put("baseCommitId", changeSet.gitPatch.baseCommitId)
                    changeSet.gitPatch.suggestedCommitMessage?.let { put("suggestedCommitMessage", it) }
                },
            )
        }
        artifact.bashOutput?.let { output ->
            return ProviderArtifact(
                kind = ArtifactKind.CommandOutput,
                label = output.command,
                textContent = output.output,
                mediaType = "text/plain",
                metadata = mapOf("command" to output.command, "exitCode" to output.exitCode.toString()),
            )
        }
        artifact.media?.let { media ->
            return ProviderArtifact(
                kind = ArtifactKind.Media,
                label = "Jules media artifact",
                textContent = media.data,
                mediaType = media.mimeType,
                metadata = mapOf("encoding" to "base64"),
            )
        }
        return null
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.emitPullRequests(
        runId: ProviderRunId,
        session: JulesSession,
        seenPullRequests: MutableSet<String>,
    ) {
        session.outputs.mapNotNull { it.pullRequest }.forEach { pullRequest ->
            if (seenPullRequests.add(pullRequest.url)) {
                emit(
                    AgentEvent.ArtifactProduced(
                        runId = runId,
                        artifact = ProviderArtifact(
                            kind = ArtifactKind.PullRequest,
                            label = pullRequest.title,
                            uri = pullRequest.url,
                            textContent = pullRequest.description,
                            mediaType = "text/markdown",
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun action(block: suspend () -> Unit): ProviderActionResult {
        block()
        return ProviderActionResult.Accepted
    }
}
