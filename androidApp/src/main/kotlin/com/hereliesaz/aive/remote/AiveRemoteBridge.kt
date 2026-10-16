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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide hand-off between [AiveRemoteService] and the UI. Workflows run on the runtime the
 * UI owns, so the service only queues launch requests here; MainActivity feeds them to App, which
 * hands each back through [complete] once its launch started or failed, and publishes the runtime
 * state back, which the service streams to watching clients.
 *
 * The queue lives in memory only: it is lost with the process, holds at most [MAX_PENDING]
 * requests, and a request still queued [PENDING_TTL_MILLIS] after it arrived is dropped as expired
 * rather than launched long after the client asked. Every request ends in one [RemoteLaunchOutcome].
 */
internal object AiveRemoteBridge {
    const val MAX_PENDING = 10
    const val PENDING_TTL_MILLIS = 60 * 60 * 1000L

    private val nextRequestId = AtomicLong(1)
    private val lock = Any()
    private val enqueuedAt = mutableMapOf<Long, Long>()
    private val pending = MutableStateFlow<List<ExternalWorkflowLaunchRequest>>(emptyList())
    private val status = MutableStateFlow<RemoteRunStatus?>(null)
    private val outcomeEvents = MutableSharedFlow<RemoteLaunchOutcome>(
        replay = MAX_PENDING,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Launch requests not yet handed to the runtime, oldest first. */
    val pendingLaunches: StateFlow<List<ExternalWorkflowLaunchRequest>> = pending.asStateFlow()

    /** The run the UI's runtime currently shows; null before any run is live. */
    val runStatus: StateFlow<RemoteRunStatus?> = status.asStateFlow()

    /** How queued launches ended; replays the most recent [MAX_PENDING] to new collectors. */
    val outcomes: SharedFlow<RemoteLaunchOutcome> = outcomeEvents.asSharedFlow()

    /** Queues a launch and returns its id, or null when [MAX_PENDING] requests are already waiting. */
    fun enqueue(
        projectName: String,
        objective: String,
        repository: RepositoryRef?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? = synchronized(lock) {
        pruneExpiredLocked(nowMillis)
        if (pending.value.size >= MAX_PENDING) return@synchronized null
        val request = ExternalWorkflowLaunchRequest(nextRequestId.getAndIncrement(), projectName, objective, repository)
        enqueuedAt[request.requestId] = nowMillis
        pending.update { it + request }
        request.requestId
    }

    /** Ends [requestId]: started when [failure] is null, else failed with that reason. */
    fun complete(requestId: Long, failure: String?) {
        synchronized(lock) {
            if (enqueuedAt.remove(requestId) == null) return
            pending.update { queue -> queue.filterNot { it.requestId == requestId } }
            outcomeEvents.tryEmit(RemoteLaunchOutcome(requestId, started = failure == null, error = failure))
        }
    }

    /** Drops requests queued longer than [PENDING_TTL_MILLIS], reporting each as expired. */
    fun pruneExpired(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) { pruneExpiredLocked(nowMillis) }
    }

    /** The recorded outcome of [requestId], while it is among the most recent [MAX_PENDING]. */
    fun outcomeOf(requestId: Long): RemoteLaunchOutcome? = outcomes.replayCache.lastOrNull { it.requestId == requestId }

    private fun pruneExpiredLocked(nowMillis: Long) {
        val expired = enqueuedAt.filterValues { nowMillis - it >= PENDING_TTL_MILLIS }.keys
        if (expired.isEmpty()) return
        expired.forEach(enqueuedAt::remove)
        pending.update { queue -> queue.filterNot { it.requestId in expired } }
        expired.sorted().forEach {
            outcomeEvents.tryEmit(RemoteLaunchOutcome(it, started = false, error = EXPIRED_MESSAGE))
        }
    }

    const val EXPIRED_MESSAGE = "The Aive was not opened within an hour, so the launch expired"

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

/** How a queued launch ended: [started], or not with [error] saying why. */
internal data class RemoteLaunchOutcome(
    val requestId: Long,
    val started: Boolean,
    val error: String?,
)

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
