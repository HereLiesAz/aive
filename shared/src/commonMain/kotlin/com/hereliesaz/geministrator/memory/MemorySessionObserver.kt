package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.ProviderActionResult
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

    /**
     * [onSessionEvent] with a way to answer the running session: [reply] sends a message into it
     * (memory cues and recalls) and reports whether the provider accepted it.
     */
    suspend fun onSessionEvent(
        handle: ManagedSessionHandle,
        event: AgentEvent,
        reply: suspend (String) -> ProviderActionResult,
    ) = onSessionEvent(handle, event)

    suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus)

    /**
     * The agent wrote `/remindme`: send the session so far to memory now. Only the parts not yet
     * sent go; the capture stays, and the end of the session sends what comes after. Nothing is
     * answered back.
     */
    suspend fun onRemindMe(handle: ManagedSessionHandle) = Unit

    /**
     * A user message on its way into a running session. Returns the text to send: memory appends
     * its cue cloud for the user's words; observers without one return it unchanged.
     */
    suspend fun annotateUserMessage(handle: ManagedSessionHandle, text: String): String = text

    /**
     * Memory is about to send [message] into a session. An echo of exactly that text is memory's own
     * and is not banked again; any other message, whatever it starts with, is the agent's.
     */
    suspend fun noteIssued(message: String) = Unit

    /** The keeper sent [text] into the running session; the whole session is experience, so it is banked. */
    suspend fun onKeeperMessage(handle: ManagedSessionHandle, text: String) = Unit
}

/** First line of every message memory sends into a session, so it is never banked or re-read as the agent's own. */
const val MEMORY_MESSAGE_MARKER: String = "⟦memory⟧"

data object NoOpMemorySessionObserver : MemorySessionObserver {
    override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) = Unit
    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) = Unit
    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) = Unit
}

/**
 * Collects the useful episodic trace while a spawn is alive, then hands one immutable envelope to
 * the serialized consolidation queue. Progress heartbeats, token telemetry, approval acks, and
 * recalled Memory context are intentionally excluded before memory processing.
 */
class QueuedMemorySessionObserver(
    private val queue: MemoryConsolidationQueue,
    private val maxCapturedEventChars: Int = 24_000,
    private val nowEpochMillis: () -> Long = ::systemNowEpochMillis,
    /** At most one crash-journal write per capture in this many milliseconds (0 journals every part). */
    private val journalIntervalMillis: Long = DEFAULT_JOURNAL_INTERVAL_MILLIS,
) : MemorySessionObserver {
    /**
     * Where what has not yet reached memory is kept, so a crash loses nothing and nothing is sent twice
     * after a restart. Set by the controller; absent, captures live in memory only.
     */
    var journal: MemoryCaptureJournal? = null

    private data class Capture(
        val request: AgentTaskRequest,
        val userPrompt: String,
        val parts: MutableList<MemorySessionPart>,
        /** How many of [parts] have already been sent to the queue (the cursor). */
        var sentParts: Int = 0,
        /** How many envelopes this capture has sent; keeps episode ids unique per envelope. */
        var sends: Int = 0,
        /** When the journal was last written for this capture; null until the first write. */
        var journaledAt: Long? = null,
    )

    private val mutex = Mutex()
    private val captures = mutableMapOf<ManagedSessionHandle, Capture>()
    private val issued = ArrayDeque<String>()

    override suspend fun noteIssued(message: String) {
        mutex.withLock {
            issued.addLast(message.trim())
            while (issued.size > MAX_ISSUED_MESSAGES) issued.removeFirst()
        }
    }

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
        request.promptContext.dynamicContext
            .filterNot { block -> block.label.equals(MEMORY_RECALL_PROMPT_LABEL, ignoreCase = true) }
            .forEach { block ->
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
            if (handle !in captures) {
                captures[handle] = Capture(request, workflowObjective, initialParts)
            }
        }
        mutex.withLock { captures[handle] }?.let { journalUnsent(handle, it, force = true) }
    }

    override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
        val part = when (event) {
            is AgentEvent.PlanGenerated -> MemorySessionPart(
                MemorySourceKind.Plan,
                "Agent plan",
                event.summary.take(maxCapturedEventChars),
            )
            // Memory's own injected cues and recalls (the exact text it sent, noted by noteIssued) are not
            // experience to bank again. A marker at the start of an agent's own message proves nothing:
            // the agent writes its text, so that cannot be a way to keep a message out of memory.
            is AgentEvent.Message -> if (mutex.withLock { event.content.trim() in issued }) null else MemorySessionPart(
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
            // The whole session is context: reasoning is banked as well as watched for recall triggers.
            is AgentEvent.Thinking -> MemorySessionPart(
                MemorySourceKind.AgentNote,
                "Agent thinking",
                event.content.take(maxCapturedEventChars),
            )
            is AgentEvent.PlanApproved,
            is AgentEvent.Progress,
            is AgentEvent.Completed,
            is AgentEvent.UsageReported,
            -> null
        } ?: return

        if (part.text.isBlank()) return
        val capture = mutex.withLock { captures[handle]?.also { it.parts.add(part) } } ?: return
        journalUnsent(handle, capture)
    }

    override suspend fun onKeeperMessage(handle: ManagedSessionHandle, text: String) {
        if (text.isBlank()) return
        val part = MemorySessionPart(MemorySourceKind.Message, "Keeper message", text.take(maxCapturedEventChars))
        val capture = mutex.withLock { captures[handle]?.also { it.parts.add(part) } } ?: return
        journalUnsent(handle, capture)
    }

    override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
        if (status != ManagedSessionStatus.Completed && status != ManagedSessionStatus.Failed) return
        val capture = mutex.withLock { captures[handle] } ?: return
        send(
            handle,
            capture,
            MemorySessionPart(
                kind = if (status == ManagedSessionStatus.Failed) MemorySourceKind.Failure else MemorySourceKind.Other,
                label = "Terminal status",
                text = status.name,
            ),
        )
        mutex.withLock {
            if (captures[handle] === capture) captures.remove(handle)
        }
    }

    override suspend fun onRemindMe(handle: ManagedSessionHandle) {
        val capture = mutex.withLock { captures[handle] } ?: return
        send(handle, capture, terminal = null)
    }

    /**
     * Sends the parts after the capture's cursor (plus [terminal], if any) as one envelope and moves
     * the cursor past them, so each part reaches memory exactly once. Nothing new and no terminal
     * part: nothing is sent. The capture itself is kept.
     */
    private suspend fun send(handle: ManagedSessionHandle, capture: Capture, terminal: MemorySessionPart?) {
        val (fresh, sequence) = mutex.withLock {
            val fresh = capture.parts.subList(capture.sentParts, capture.parts.size).toList()
            if (fresh.isEmpty() && terminal == null) return
            capture.sentParts = capture.parts.size
            fresh to capture.sends++
        }
        queue.enqueueSession(envelopeOf(handle, capture, fresh + listOfNotNull(terminal), sequence))
        // Only once it is in the queue does the journal let go of it: a failure above keeps it for the restart.
        capture.journaledAt = null
        runCatching { journal?.record(handle.providerRunId.value, null) }
    }

    private fun envelopeOf(
        handle: ManagedSessionHandle,
        capture: Capture,
        parts: List<MemorySessionPart>,
        sequence: Int,
    ): MemorySessionEnvelope {
        val request = capture.request
        val context = request.orchestrationContext
        val cacheNamespace = request.promptContext.cacheNamespace
        val fallbackRoleId = cacheNamespace
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
        return MemorySessionEnvelope(
            sourceSessionId = handle.providerRunId.value,
            projectId = context.projectId?.value,
            // The workflow whose bank this session's memory goes to (a session outside any workflow is its own).
            workflowRunId = memoryWorkflowOf(request, handle.providerRunId.value),
            workflowDefinitionId = context.workflowDefinitionId?.value,
            taskRunId = request.taskRunId.value,
            taskDefinitionId = context.taskDefinitionId?.value,
            roleId = context.roleId?.value ?: fallbackRoleId,
            // Nothing is banked unscrubbed: role instructions, prompt blocks, artifacts and messages
            // can all carry a key a run saw, and a bank outlives the run (and is recalled into others).
            userPrompt = ControlRedaction.scrub(capture.userPrompt),
            parts = parts.map { it.copy(text = ControlRedaction.scrub(it.text)) },
            closedAtEpochMillis = nowEpochMillis(),
            sequence = sequence,
        )
    }

    /**
     * Keeps what [capture] holds past its cursor in the crash journal (at most once per
     * [journalIntervalMillis] unless [force]d), so the parts survive a crash and, once sent, are not sent again.
     */
    private suspend fun journalUnsent(handle: ManagedSessionHandle, capture: Capture, force: Boolean = false) {
        val target = journal ?: return
        val now = nowEpochMillis()
        val (fresh, sequence) = mutex.withLock {
            val last = capture.journaledAt
            if (!force && last != null && now - last < journalIntervalMillis) return
            capture.journaledAt = now
            capture.parts.subList(capture.sentParts, capture.parts.size).toList() to capture.sends
        }
        if (fresh.isEmpty()) return
        runCatching { target.record(handle.providerRunId.value, envelopeOf(handle, capture, fresh, sequence)) }
    }
}

/**
 * The workflow a session's memory belongs to: its workflow run (from the orchestration context, else
 * the prompt cache namespace), or, outside any workflow, the session itself as a root workflow.
 */
fun memoryWorkflowOf(request: AgentTaskRequest, sessionId: String? = null): String = memoryWorkflowOf(
    request.orchestrationContext.workflowRunId?.value
        ?: request.promptContext.cacheNamespace?.substringBefore(':')?.takeIf(String::isNotBlank),
    request.taskRunId.value,
    sessionId,
)

private const val MAX_ISSUED_MESSAGES = 128

private const val DEFAULT_JOURNAL_INTERVAL_MILLIS = 2_000L

internal const val MEMORY_RECALL_PROMPT_LABEL: String = "Relevant memory"

@OptIn(ExperimentalTime::class)
private fun systemNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
