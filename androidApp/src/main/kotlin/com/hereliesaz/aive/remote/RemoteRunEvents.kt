package com.hereliesaz.aive.remote

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed

/**
 * The typed event stream a remote client can watch (protocol version 3): what happened in the
 * run The Aive is showing, in order, shaped after AG-UI's event categories (run lifecycle, task
 * steps, gates, artifacts, usage) while keeping the remote API's flat field format. Each event
 * comes from one [WorkflowEvent] of the run's timeline, and [eventSeq] is that event's 1-based
 * position in the timeline, so it only grows within a run and a client can resume after it.
 * Timeline events with nothing to tell a client ([ExecutorAssigned]) leave a gap in the numbers.
 *
 * Read-only: nothing here lets a client decide a gate. A pairing code is a bearer secret that
 * cannot be told apart from its owner (docs/THREAT_MODEL.md), so approvals stay on the device.
 * Free text is capped in UTF-8 bytes (see [RemoteRunEventLimits]) so a single event always fits a
 * Binder transaction and a sealed relay frame. Protocol: docs/Remote-API.md, "Run events".
 */
internal sealed class RemoteRunEvent {
    abstract val runId: String
    abstract val eventSeq: Long
    abstract val atMillis: Long

    /** The wire name in the [RemoteRunEventFields.TYPE] field. */
    abstract val type: String

    /**
     * Every field of this event, in a stable order, as String, Int, Long, Boolean or Double
     * values; absent optional fields are left out. The relay sends these as one JSON object and
     * the bound service as one Bundle.
     */
    fun fields(): LinkedHashMap<String, Any> {
        val fields = LinkedHashMap<String, Any>()
        fields[RemoteRunEventFields.TYPE] = type
        fields[RemoteRunEventFields.RUN_ID] = runId
        fields[RemoteRunEventFields.EVENT_SEQ] = eventSeq
        fields[RemoteRunEventFields.AT] = atMillis
        putFields(fields)
        return fields
    }

    protected abstract fun putFields(fields: MutableMap<String, Any>)

    /** A run began (AG-UI RUN_STARTED). */
    data class RunStarted(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val objective: String?,
    ) : RemoteRunEvent() {
        override val type = "run_started"
        override fun putFields(fields: MutableMap<String, Any>) {
            objective?.let { fields[RemoteRunEventFields.OBJECTIVE] = it }
        }
    }

    /**
     * A task step moved (AG-UI STEP_STARTED / STEP_FINISHED). [change] names what happened;
     * [status] is the [TaskRunStatus] name it leaves the task in, when the change decides one.
     */
    data class TaskChanged(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val taskId: String,
        val taskName: String?,
        val change: String,
        val status: String?,
        val attempt: Int? = null,
        val roleId: String? = null,
        val reason: String? = null,
    ) : RemoteRunEvent() {
        override val type = "task"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.TASK_ID] = taskId
            taskName?.let { fields[RemoteRunEventFields.TASK_NAME] = it }
            fields[RemoteRunEventFields.CHANGE] = change
            status?.let { fields[RemoteRunEventFields.STATUS] = it }
            attempt?.let { fields[RemoteRunEventFields.ATTEMPT] = it }
            roleId?.let { fields[RemoteRunEventFields.ROLE_ID] = it }
            reason?.let { fields[RemoteRunEventFields.REASON] = it }
        }
    }

    /**
     * The run waits for a person on the device (AG-UI interrupt). [need] is `approval` (a gate,
     * with [gateId]) or `decision` (an escalation); [reason] says what is needed and [planPreview]
     * shows the plan up for approval, when there is one.
     */
    data class GateWaiting(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val need: String,
        val gateId: String?,
        val taskId: String?,
        val taskName: String?,
        val reason: String,
        val planPreview: String? = null,
        val planTruncated: Boolean = false,
    ) : RemoteRunEvent() {
        override val type = "gate_waiting"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.NEED] = need
            gateId?.let { fields[RemoteRunEventFields.GATE_ID] = it }
            taskId?.let { fields[RemoteRunEventFields.TASK_ID] = it }
            taskName?.let { fields[RemoteRunEventFields.TASK_NAME] = it }
            fields[RemoteRunEventFields.REASON] = reason
            planPreview?.let {
                fields[RemoteRunEventFields.PLAN_PREVIEW] = it
                fields[RemoteRunEventFields.PLAN_TRUNCATED] = planTruncated
            }
        }
    }

    /** A gate was decided on the device. */
    data class GateDecided(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val gateId: String,
        val taskId: String?,
        val approved: Boolean,
    ) : RemoteRunEvent() {
        override val type = "gate_decided"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.GATE_ID] = gateId
            taskId?.let { fields[RemoteRunEventFields.TASK_ID] = it }
            fields[RemoteRunEventFields.APPROVED] = approved
        }
    }

    /**
     * A task produced an artifact. [preview] is the start of its text, at most
     * [RemoteRunEventLimits.PREVIEW_BYTES]; [sizeBytes] is the whole text's UTF-8 size. A task
     * that holds its output ([TaskDefinition.holdOutput]) sends no preview ([withheld]).
     */
    data class ArtifactProduced(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val taskId: String,
        val taskName: String?,
        val artifactId: String,
        val kind: String,
        val label: String,
        val mediaType: String?,
        val sizeBytes: Long?,
        val preview: String?,
        val truncated: Boolean,
        val withheld: Boolean,
    ) : RemoteRunEvent() {
        override val type = "artifact"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.TASK_ID] = taskId
            taskName?.let { fields[RemoteRunEventFields.TASK_NAME] = it }
            fields[RemoteRunEventFields.ARTIFACT_ID] = artifactId
            fields[RemoteRunEventFields.KIND] = kind
            fields[RemoteRunEventFields.LABEL] = label
            mediaType?.let { fields[RemoteRunEventFields.MEDIA_TYPE] = it }
            sizeBytes?.let { fields[RemoteRunEventFields.SIZE_BYTES] = it }
            preview?.let { fields[RemoteRunEventFields.PREVIEW] = it }
            fields[RemoteRunEventFields.TRUNCATED] = truncated
            if (withheld) fields[RemoteRunEventFields.WITHHELD] = true
        }
    }

    /** A provider reported usage for one task's session (one report, not a running total). */
    data class Usage(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val taskId: String,
        val providerId: String,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val costUsd: Double?,
    ) : RemoteRunEvent() {
        override val type = "usage"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.TASK_ID] = taskId
            fields[RemoteRunEventFields.PROVIDER_ID] = providerId
            inputTokens?.let { fields[RemoteRunEventFields.INPUT_TOKENS] = it }
            outputTokens?.let { fields[RemoteRunEventFields.OUTPUT_TOKENS] = it }
            costUsd?.let { fields[RemoteRunEventFields.COST_USD] = it }
        }
    }

    /** The run ended (AG-UI RUN_FINISHED / RUN_ERROR): [status] is a [WorkflowRunStatus] name. */
    data class RunFinished(
        override val runId: String,
        override val eventSeq: Long,
        override val atMillis: Long,
        val status: String,
        val reason: String?,
    ) : RemoteRunEvent() {
        override val type = "run_finished"
        override fun putFields(fields: MutableMap<String, Any>) {
            fields[RemoteRunEventFields.STATUS] = status
            reason?.let { fields[RemoteRunEventFields.REASON] = it }
        }
    }
}

/** Field names of a run event, the same on the relay and the bound service. */
internal object RemoteRunEventFields {
    const val TYPE = "type"
    const val RUN_ID = "runId"
    /** Long: the event's position in the run's timeline. Not the relay envelope's `seq`. */
    const val EVENT_SEQ = "eventSeq"
    /** Long: when the event happened, epoch milliseconds. Not the relay envelope's `ts`. */
    const val AT = "at"
    const val OBJECTIVE = "objective"
    const val TASK_ID = "taskId"
    const val TASK_NAME = "taskName"
    const val CHANGE = "change"
    const val STATUS = "status"
    const val ATTEMPT = "attempt"
    const val ROLE_ID = "roleId"
    const val REASON = "reason"
    const val NEED = "need"
    const val GATE_ID = "gateId"
    const val PLAN_PREVIEW = "planPreview"
    const val PLAN_TRUNCATED = "planTruncated"
    const val APPROVED = "approved"
    const val ARTIFACT_ID = "artifactId"
    const val KIND = "kind"
    const val LABEL = "label"
    const val MEDIA_TYPE = "mediaType"
    const val SIZE_BYTES = "sizeBytes"
    const val PREVIEW = "preview"
    const val TRUNCATED = "truncated"
    const val WITHHELD = "withheld"
    const val PROVIDER_ID = "providerId"
    const val INPUT_TOKENS = "inputTokens"
    const val OUTPUT_TOKENS = "outputTokens"
    const val COST_USD = "costUsd"
}

/** Size caps, in UTF-8 bytes, on the free text a run event carries. */
internal object RemoteRunEventLimits {
    const val PREVIEW_BYTES = 4_096
    const val PLAN_PREVIEW_BYTES = 4_096
    const val REASON_BYTES = 1_024
    const val OBJECTIVE_BYTES = 1_024
    const val NAME_BYTES = 256

    /** How many of the newest events the host keeps for clients that start watching late. */
    const val RETAINED_EVENTS = 200
}

/**
 * The newest [RemoteRunEventLimits.RETAINED_EVENTS] events of the run The Aive shows, in order.
 * [generation] changes whenever the numbering starts over: another run is shown, or the run's
 * timeline got shorter (it was restored or re-imported), so a watcher knows to start again.
 */
internal data class RemoteRunEventLog(
    val generation: Long,
    val runId: String?,
    val timelineSize: Int,
    val events: List<RemoteRunEvent>,
) {
    /**
     * This log brought up to [timeline], the shown [run]'s whole event timeline in order. Only
     * events past [timelineSize] are mapped; earlier ones are already here.
     */
    fun advance(run: WorkflowRun, definition: WorkflowDefinition, timeline: List<WorkflowEvent>): RemoteRunEventLog {
        val runTimeline = timeline.filter { it.workflowRunId == run.id }
        val runId = run.id.value
        val restart = runId != this.runId || runTimeline.size < timelineSize
        if (!restart && runTimeline.size == timelineSize) return this
        val from = if (restart) 0 else timelineSize
        val tasks = definition.tasks.associateBy(TaskDefinition::id)
        val added = (from until runTimeline.size).mapNotNull { index ->
            runTimeline[index].toRemoteRunEvent(eventSeq = index + 1L, run = run, tasks = tasks)
        }
        val kept = (if (restart) added else events + added).takeLast(RemoteRunEventLimits.RETAINED_EVENTS)
        return RemoteRunEventLog(
            generation = if (restart) generation + 1 else generation,
            runId = runId,
            timelineSize = runTimeline.size,
            events = kept,
        )
    }

    companion object {
        val EMPTY = RemoteRunEventLog(generation = 0, runId = null, timelineSize = 0, events = emptyList())
    }
}

/**
 * One watcher's place in the [RemoteRunEventLog]: [take] returns the events it has not been sent
 * yet, oldest first, each exactly once. A client resuming a run it saw before passes that run's
 * id and the last [RemoteRunEvent.eventSeq] it received; anything else starts from the oldest
 * retained event.
 */
internal class RemoteRunEventCursor(
    private val resumeRunId: String? = null,
    private val resumeAfterSeq: Long = 0,
) {
    private var generation: Long? = null
    private var afterSeq = 0L

    fun take(log: RemoteRunEventLog): List<RemoteRunEvent> {
        val runId = log.runId ?: return emptyList()
        if (generation != log.generation) {
            afterSeq = if (generation == null && runId == resumeRunId) resumeAfterSeq.coerceAtLeast(0) else 0
            generation = log.generation
        }
        val fresh = log.events.filter { it.eventSeq > afterSeq }
        fresh.lastOrNull()?.let { afterSeq = it.eventSeq }
        return fresh
    }
}

/** [this] as a client-facing event, or null when it has nothing to tell a client. */
internal fun WorkflowEvent.toRemoteRunEvent(
    eventSeq: Long,
    run: WorkflowRun,
    tasks: Map<TaskDefinitionId, TaskDefinition>,
): RemoteRunEvent? {
    val runId = workflowRunId.value
    val at = occurredAtEpochMillis
    fun name(id: TaskDefinitionId?): String? = id?.let { tasks[it] }?.name?.let { capUtf8(it, RemoteRunEventLimits.NAME_BYTES) }
    fun capReason(text: String): String = capUtf8(text, RemoteRunEventLimits.REASON_BYTES)
    fun task(id: TaskDefinitionId, change: String, status: TaskRunStatus?, attempt: Int? = null, roleId: String? = null, reason: String? = null) =
        RemoteRunEvent.TaskChanged(
            runId = runId,
            eventSeq = eventSeq,
            atMillis = at,
            taskId = id.value,
            taskName = name(id),
            change = change,
            status = status?.name,
            attempt = attempt,
            roleId = roleId,
            reason = reason?.let { capReason(it) },
        )
    return when (this) {
        is WorkflowCreated -> RemoteRunEvent.RunStarted(
            runId = runId,
            eventSeq = eventSeq,
            atMillis = at,
            objective = (objective ?: run.objective.takeIf { workflowRunId == run.id })
                ?.let { capUtf8(it, RemoteRunEventLimits.OBJECTIVE_BYTES) },
        )
        is TaskBecameReady -> task(taskDefinitionId, "ready", TaskRunStatus.Ready)
        is ExecutorAssigned -> null
        is AgentAssigned -> task(taskDefinitionId, "assigned", status = null, roleId = roleId.value)
        is TaskStarted -> task(taskDefinitionId, "started", TaskRunStatus.Running, attempt = attempt)
        is ApprovalRequired -> {
            val plan = run.taskRuns[taskDefinitionId]?.providerPlan?.takeIf(String::isNotBlank)
            val planPreview = plan?.let { capUtf8(it, RemoteRunEventLimits.PLAN_PREVIEW_BYTES) }
            RemoteRunEvent.GateWaiting(
                runId = runId,
                eventSeq = eventSeq,
                atMillis = at,
                need = "approval",
                gateId = if (hasGateId) gateId.value else null,
                taskId = taskDefinitionId.value,
                taskName = name(taskDefinitionId),
                reason = capReason(reason),
                planPreview = planPreview,
                planTruncated = plan != null && planPreview != plan,
            )
        }
        is HumanDecisionRequired -> RemoteRunEvent.GateWaiting(
            runId = runId,
            eventSeq = eventSeq,
            atMillis = at,
            need = "decision",
            gateId = null,
            taskId = taskDefinitionId?.value,
            taskName = name(taskDefinitionId),
            reason = capReason(reason),
        )
        is ApprovalDecisionReceived -> RemoteRunEvent.GateDecided(
            runId = runId,
            eventSeq = eventSeq,
            atMillis = at,
            gateId = gateId.value,
            taskId = taskDefinitionId?.value,
            approved = approved,
        )
        is ArtifactCreated -> {
            val withheld = tasks[taskDefinitionId]?.holdOutput == true
            val text = artifact.textContent
            val preview = if (withheld) null else text?.let { capUtf8(it, RemoteRunEventLimits.PREVIEW_BYTES) }
            RemoteRunEvent.ArtifactProduced(
                runId = runId,
                eventSeq = eventSeq,
                atMillis = at,
                taskId = taskDefinitionId.value,
                taskName = name(taskDefinitionId),
                artifactId = artifact.id.value,
                kind = artifact.kind.name,
                label = capUtf8(artifact.label, RemoteRunEventLimits.NAME_BYTES),
                mediaType = artifact.mediaType?.let { capUtf8(it, RemoteRunEventLimits.NAME_BYTES) },
                sizeBytes = text?.encodeToByteArray()?.size?.toLong(),
                preview = preview,
                truncated = preview != null && preview != text,
                withheld = withheld,
            )
        }
        is VerificationFailed -> task(taskDefinitionId, "verification_failed", status = null, reason = reason)
        is RetryScheduled -> task(taskDefinitionId, "retry_scheduled", TaskRunStatus.Retrying, attempt = nextAttempt, reason = reason)
        is TaskEscalated -> task(taskDefinitionId, "escalated", TaskRunStatus.Escalated, roleId = reassignedRoleId?.value, reason = reason)
        is TaskCompleted -> task(taskDefinitionId, "completed", TaskRunStatus.Completed)
        is TaskFailed -> task(taskDefinitionId, "failed", TaskRunStatus.Failed, reason = reason)
        is TaskCancelled -> task(taskDefinitionId, "cancelled", TaskRunStatus.Cancelled)
        is WorkflowCompleted -> RemoteRunEvent.RunFinished(runId, eventSeq, at, WorkflowRunStatus.Completed.name, reason = null)
        is WorkflowFailed -> RemoteRunEvent.RunFinished(runId, eventSeq, at, WorkflowRunStatus.Failed.name, capReason(reason))
        is WorkflowCancelled -> RemoteRunEvent.RunFinished(runId, eventSeq, at, WorkflowRunStatus.Cancelled.name, reason = null)
        is ProviderUsageRecorded -> RemoteRunEvent.Usage(
            runId = runId,
            eventSeq = eventSeq,
            atMillis = at,
            taskId = taskDefinitionId.value,
            providerId = providerId.value,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd?.takeIf { it.isFinite() },
        )
    }
}

/** The longest prefix of [text] whose UTF-8 encoding fits [maxBytes], never splitting a character. */
internal fun capUtf8(text: String, maxBytes: Int): String {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) return text.substring(0, index)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return text
}
