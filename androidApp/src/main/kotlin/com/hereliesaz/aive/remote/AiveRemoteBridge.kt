package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.ApplicationRuntimeState
import com.hereliesaz.geministrator.ExternalWorkflowLaunchRequest
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.providers.llm.TextGenerationApi
import com.hereliesaz.geministrator.providers.llm.memoryTextApi
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide hand-off between [AiveRemoteService] and the UI. Workflows run on the runtime the
 * UI owns, so the service only queues launch requests here; MainActivity feeds them to App and
 * publishes the runtime state back, which the service streams to watching clients.
 */
internal object AiveRemoteBridge {
    private val nextRequestId = AtomicLong(1)
    private val pending = MutableStateFlow<List<ExternalWorkflowLaunchRequest>>(emptyList())
    private val status = MutableStateFlow<RemoteRunStatus?>(null)

    /** Launch requests not yet handed to the runtime, oldest first. */
    val pendingLaunches: StateFlow<List<ExternalWorkflowLaunchRequest>> = pending.asStateFlow()

    /** The run the UI's runtime currently shows; null before any run is live. */
    val runStatus: StateFlow<RemoteRunStatus?> = status.asStateFlow()

    fun enqueue(projectName: String, objective: String, repository: RepositoryRef?): Long {
        val request = ExternalWorkflowLaunchRequest(nextRequestId.getAndIncrement(), projectName, objective, repository)
        pending.update { it + request }
        return request.requestId
    }

    fun consume(requestId: Long) = pending.update { queue -> queue.filterNot { it.requestId == requestId } }

    fun publish(state: ApplicationRuntimeState) {
        val live = state as? ApplicationRuntimeState.Live ?: return
        val run = live.presentation.run
        val tasks = run.taskRuns.values
        status.value = RemoteRunStatus(
            runId = run.id.value,
            objective = run.objective,
            status = run.status.name,
            tasksTotal = tasks.size,
            tasksDone = tasks.count { it.status.isTerminal() },
            progressMessage = tasks.lastOrNull { it.status == TaskRunStatus.Running }?.progressMessage,
        )
    }
}

internal data class RemoteRunStatus(
    val runId: String,
    val objective: String,
    val status: String,
    val tasksTotal: Int,
    val tasksDone: Int,
    val progressMessage: String?,
)

/**
 * The text API a remote prompt runs on: [providerId] when the client names one (keyless providers
 * need no stored credential), else the provider planning uses. Never an unlinked keyless gateway
 * by default: those may log prompts, so the user has to pick them.
 */
internal fun remoteTextApi(credentials: Map<String, String>, providerId: String?, model: String?): TextGenerationApi? =
    memoryTextApi(credentials, providerId, model)

/** `https://github.com/owner/name[.git]` as a [RepositoryRef]; null for anything else. */
internal fun gitHubRepositoryRef(url: String?): RepositoryRef? {
    val match = Regex("""^https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""")
        .find(url?.trim().orEmpty()) ?: return null
    val (owner, name) = match.destructured
    return RepositoryRef(owner = owner, name = name, source = RepositorySource.GitHub, remoteUrl = "https://github.com/$owner/$name")
}
