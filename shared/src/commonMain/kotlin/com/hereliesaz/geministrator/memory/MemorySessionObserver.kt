package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface MemorySessionObserver {
    suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest)
    suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent)
    suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus)
}

data object NoOpMemorySessionObserver : MemorySessionObserver {
    override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) = Unit
    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) = Unit
    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) = Unit
}

/**
 * Collects the useful episodic trace while a spawn is alive, then hands one immutable envelope to
 * the serialized consolidation queue. Progress heartbeats, token telemetry, and approval acks are
 * intentionally excluded before memory processing.
 */
class QueuedMemorySessionObserver(
    private val queue: MemoryConsolidationQueue,
    private val maxCapturedEventChars: Int = 24_000,
    private val nowEpochMillis: () -> Long = ::systemNowEpochMillis,
) : MemorySessionObserver {
    private data class Capture(
        val request: AgentTaskRequest,
        val userPrompt: String,
        val parts: MutableList<MemorySessionPart>,
    )

    private val mutex = Mutex()
    private val captures = mutableMapOf<ManagedSessionHandle, Capture>()

    init {
        require(maxCapturedEventChars > 0)
    }

    override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) {
        val workflowObjective = request.promptContext.stablePrefix
            .firstOrNull { it.label.equals("Workflow objective", ignoreCase = true) }
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: request.objective.trim()

        val initialParts = mutableListOf<MemorySessionPart>()
        initialParts += MemorySessionPart(MemorySourceKind.Objective, "Task objective", request.objective)
        initialParts += MemorySessionPart(MemorySourceKind.Role, "Role instructions", request.roleInstructions)
        request.acceptanceCriteria.forEachIndexed { index, criterion ->
            initialParts += MemorySessionPart(
                MemorySourceKind.PromptContext,
                "Acceptance criterion ${index + 1}",
                criterion.description,
            )
        }
        request.promptContext.stablePrefix.forEach { block ->
            initialParts += MemorySessionPart(MemorySourceKind.PromptContext, block.label, block.content)
        }
        request.promptContext.dynamicContext.forEach { block ->
            initialParts += MemorySessionPart(MemorySourceKind.PromptContext, block.label, block.content)
        }
        request.contextArtifacts.forEach { artifact ->
            val evidence = buildString {
                append(artifact.label)
                artifact.uri?.takeIf(String::isNotBlank)?.let { append("\nURI: ").append(it) }
                artifact.textContent?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                if (artifact.metadata.isNotEmpty()) {
                    append("\nMetadata: ")
                    append(artifact.metadata.entries.joinToString { "${it.key}=${it.value}" })
                }
            }.take(maxCapturedEventChars)
            initialParts += MemorySessionPart(MemorySourceKind.Artifact, "Input artifact", evidence)
        }

        mutex.withLock {
            captures[handle] = Capture(request, workflowObjective, initialParts)
        }
    }

    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
        val part = when (event) {
            is AgentEvent.PlanGenerated -> MemorySessionPart(
                MemorySourceKind.Plan,
                "Agent plan",
                event.summary.take(maxCapturedEventChars),
            )
            is AgentEvent.Message -> MemorySessionPart(
                MemorySourceKind.Message,
                "Agent message",
                event.content.take(maxCapturedEventChars),
            )
            is AgentEvent.ArtifactProduced -> {
                val artifact = event.artifact
                val content = buildString {
                    append(artifact.label)
                    artifact.uri?.takeIf(String::isNotBlank)?.let { append("\nURI: ").append(it) }
                    artifact.textContent?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                    if (artifact.metadata.isNotEmpty()) {
                        append("\nMetadata: ")
                        append(artifact.metadata.entries.joinToString { "${it.key}=${it.value}" })
                    }
                }.take(maxCapturedEventChars)
                MemorySessionPart(MemorySourceKind.Artifact, "Produced artifact", content)
            }
            is AgentEvent.Failed -> MemorySessionPart(
                MemorySourceKind.Failure,
                "Agent failure",
                event.reason.take(maxCapturedEventChars),
            )
            is AgentEvent.PlanApproved,
            is AgentEvent.Progress,
            is AgentEvent.Completed,
            is AgentEvent.UsageReported,
            -> null
        } ?: return

        if (part.text.isBlank()) return
        mutex.withLock { captures[handle]?.parts?.add(part) }
    }

    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
        if (status != ManagedSessionStatus.Completed && status != ManagedSessionStatus.Failed) return
        val capture = mutex.withLock { captures.remove(handle) } ?: return
        val request = capture.request
        val context = request.orchestrationContext
        val cacheNamespace = request.promptContext.cacheNamespace
        val fallbackWorkflowRunId = cacheNamespace?.substringBefore(':')?.takeIf(String::isNotBlank)
        val fallbackRoleId = cacheNamespace
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)

        queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = handle.providerRunId.value,
                projectId = context.projectId?.value,
                workflowRunId = context.workflowRunId?.value ?: fallbackWorkflowRunId,
                workflowDefinitionId = context.workflowDefinitionId?.value,
                taskRunId = request.taskRunId.value,
                taskDefinitionId = context.taskDefinitionId?.value,
                roleId = context.roleId?.value ?: fallbackRoleId,
                userPrompt = capture.userPrompt,
                parts = capture.parts.toList(),
                closedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun systemNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
