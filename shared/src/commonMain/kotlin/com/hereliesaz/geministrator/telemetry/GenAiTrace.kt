package com.hereliesaz.geministrator.telemetry

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.TaskRerunRequested
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
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
 * OpenTelemetry GenAI spans derived from a run's workflow event journal
 * (docs/architecture/OBSERVABILITY.md). The journal is the only input, so the same journal always
 * yields the same trace: ids are hashes of run, task, attempt and position, never random.
 *
 * Tree: one `invoke_workflow` span per run; under it one `invoke_agent` span per task attempt;
 * under each attempt one `chat` span per provider usage report, carrying its tokens and cost.
 * Prompt and output content (the objective, artifact text, failure and approval reasons) is left
 * out unless [TraceContentPolicy.includeContent] is on.
 */
enum class OtelSpanKind(val otlpValue: Int) {
    Internal(1),
    Client(3),
}

enum class OtelStatusCode(val otlpValue: Int) {
    Unset(0),
    Ok(1),
    Error(2),
}

sealed interface OtelAttributeValue {
    data class Text(val value: String) : OtelAttributeValue
    data class Integer(val value: Long) : OtelAttributeValue
    data class Decimal(val value: Double) : OtelAttributeValue
    data class Flag(val value: Boolean) : OtelAttributeValue
}

data class OtelSpanEvent(
    val name: String,
    val timeEpochMillis: Long,
    val attributes: Map<String, OtelAttributeValue> = emptyMap(),
)

data class OtelSpan(
    /** 32 lowercase hex characters. */
    val traceId: String,
    /** 16 lowercase hex characters. */
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val kind: OtelSpanKind,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val attributes: Map<String, OtelAttributeValue>,
    val events: List<OtelSpanEvent> = emptyList(),
    val status: OtelStatusCode = OtelStatusCode.Unset,
    /** Set only when content is included: a status message can quote what failed. */
    val statusMessage: String? = null,
)

/** What may leave the device besides structure, timing, tokens and cost. Content is off by default. */
data class TraceContentPolicy(
    val includeContent: Boolean = false,
    val maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_CONTENT_CHARS: Int = 4_096
        val Default: TraceContentPolicy = TraceContentPolicy()
    }
}

/** Attribute and operation names from the OpenTelemetry GenAI semantic conventions, plus The Aive's own. */
object GenAiAttributes {
    const val OPERATION_NAME = "gen_ai.operation.name"
    const val PROVIDER_NAME = "gen_ai.provider.name"
    const val REQUEST_MODEL = "gen_ai.request.model"
    const val AGENT_NAME = "gen_ai.agent.name"
    const val WORKFLOW_NAME = "gen_ai.workflow.name"
    const val CONVERSATION_ID = "gen_ai.conversation.id"
    const val USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"
    const val USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
    const val ERROR_TYPE = "error.type"

    const val OPERATION_INVOKE_WORKFLOW = "invoke_workflow"
    const val OPERATION_INVOKE_AGENT = "invoke_agent"
    const val OPERATION_CHAT = "chat"

    // The Aive's own attributes. Cost has no GenAI convention yet.
    const val USAGE_COST_USD = "aive.usage.cost_usd"
    const val USAGE_CACHE_HIT_FRACTION = "aive.usage.cache_hit_fraction"
    const val PROVIDER_ID = "aive.provider.id"
    const val PROVIDER_RUN_ID = "aive.provider.run_id"
    const val WORKFLOW_RUN_ID = "aive.workflow.run_id"
    const val PROJECT_ID = "aive.project.id"
    const val TASK_ID = "aive.task.id"
    const val TASK_ATTEMPT = "aive.task.attempt"
    const val EXECUTOR_KIND = "aive.executor.kind"
    const val ARTIFACT_KIND = "aive.artifact.kind"
    const val APPROVED = "aive.approval.approved"

    // Content attributes, emitted only when content is included.
    const val WORKFLOW_OBJECTIVE = "aive.workflow.objective"
    const val ARTIFACT_LABEL = "aive.artifact.label"
    const val ARTIFACT_TEXT = "aive.artifact.text"
    const val REASON = "aive.reason"

    val CONTENT_ATTRIBUTES: Set<String> = setOf(WORKFLOW_OBJECTIVE, ARTIFACT_LABEL, ARTIFACT_TEXT, REASON)
}

/** `gen_ai.provider.name` for a provider id: the convention's well-known value where one exists. */
fun genAiProviderName(providerId: String): String = when (providerId) {
    "openai" -> "openai"
    "anthropic" -> "anthropic"
    "gemini" -> "gcp.gemini"
    "xai" -> "x_ai"
    "deepseek" -> "deepseek"
    "groq" -> "groq"
    "mistral" -> "mistral"
    "perplexity" -> "perplexity"
    "cohere" -> "cohere"
    else -> providerId
}

/** The executor's kind, never its configuration (a script's source or an approval's label). */
internal fun TaskExecutor.traceKind(): String = when (this) {
    is TaskExecutor.RoleAgent -> "role_agent"
    is TaskExecutor.GitHubAction -> "github_action"
    is TaskExecutor.Script -> "script"
    is TaskExecutor.TestRunner -> "test_runner"
    is TaskExecutor.Deployment -> "deployment"
    is TaskExecutor.RepositoryOperation -> "repository_operation"
    is TaskExecutor.HumanApproval -> "human_approval"
    is TaskExecutor.ExternalService -> "external_service"
    is TaskExecutor.NestedWorkflow -> "nested_workflow"
    is TaskExecutor.Distributed -> "distributed"
}

object WorkflowTraceBuilder {
    /**
     * Spans for one run's journal, in [events] order. Events of other runs are ignored; an empty
     * journal yields no spans. A run that has not ended closes at its last event.
     */
    fun build(
        events: List<WorkflowEvent>,
        content: TraceContentPolicy = TraceContentPolicy.Default,
    ): List<OtelSpan> {
        val runId = events.firstOrNull()?.workflowRunId ?: return emptyList()
        val journal = events.filter { it.workflowRunId == runId }
        return Walk(runId.value, content).apply { journal.forEach { accept(it) } }.spans()
    }

    private class Chat(
        val usage: ProviderUsageRecorded,
        val index: Int,
    )

    private class Attempt(
        val taskId: TaskDefinitionId,
        val attempt: Int,
        val ordinal: Int,
        val start: Long,
        val roleId: String?,
        val executorKind: String?,
    ) {
        var end: Long? = null
        var status: OtelStatusCode = OtelStatusCode.Unset
        var errorType: String? = null
        var statusMessage: String? = null
        val chats = mutableListOf<Chat>()
        val events = mutableListOf<OtelSpanEvent>()
    }

    private class Walk(
        private val runId: String,
        private val content: TraceContentPolicy,
    ) {
        private val traceId = traceIdFor(runId)
        private val rootSpanId = spanIdFor("run|$runId")
        private var start: Long? = null
        private var last: Long = 0L
        private var end: Long? = null
        private var status = OtelStatusCode.Unset
        private var errorType: String? = null
        private var statusMessage: String? = null
        private var workflowName: String? = null
        private var projectId: String? = null
        private var objective: String? = null
        private val rootEvents = mutableListOf<OtelSpanEvent>()
        private val attempts = mutableListOf<Attempt>()
        private val orphanChats = mutableListOf<Chat>()
        private val pendingRole = mutableMapOf<TaskDefinitionId, String>()
        private val pendingExecutor = mutableMapOf<TaskDefinitionId, String>()
        private val chatCounts = mutableMapOf<TaskDefinitionId, Int>()

        fun accept(event: WorkflowEvent) {
            val at = event.occurredAtEpochMillis
            if (start == null) start = at
            last = maxOf(last, at)
            when (event) {
                is WorkflowCreated -> {
                    start = minOf(start ?: at, at)
                    workflowName = event.workflowDefinitionId?.value
                    projectId = event.projectId?.value
                    objective = event.objective
                }
                is TaskBecameReady -> Unit
                is ExternalRunCancelFailed, is PolicyDecisionRecorded, is TaskRerunRequested -> Unit
                is ExecutorAssigned -> pendingExecutor[event.taskDefinitionId] = event.executor.traceKind()
                is AgentAssigned -> pendingRole[event.taskDefinitionId] = event.roleId.value
                is TaskStarted -> {
                    open(event.taskDefinitionId)?.close(at, OtelStatusCode.Unset, null, null)
                    val ordinal = attempts.count { it.taskId == event.taskDefinitionId && it.attempt == event.attempt }
                    attempts += Attempt(
                        taskId = event.taskDefinitionId,
                        attempt = event.attempt,
                        ordinal = ordinal,
                        start = at,
                        roleId = pendingRole[event.taskDefinitionId],
                        executorKind = pendingExecutor[event.taskDefinitionId],
                    )
                }
                is ApprovalRequired -> note(
                    event.taskDefinitionId,
                    "aive.approval_required",
                    at,
                    reasonAttributes(event.reason),
                )
                is ApprovalDecisionReceived -> note(
                    event.taskDefinitionId,
                    "aive.approval_decision",
                    at,
                    mapOf(GenAiAttributes.APPROVED to OtelAttributeValue.Flag(event.approved)),
                )
                is ArtifactCreated -> note(
                    event.taskDefinitionId,
                    "aive.artifact",
                    at,
                    buildMap<String, OtelAttributeValue> {
                        put(GenAiAttributes.ARTIFACT_KIND, OtelAttributeValue.Text(event.artifact.kind.name))
                        if (content.includeContent) {
                            put(GenAiAttributes.ARTIFACT_LABEL, OtelAttributeValue.Text(clip(event.artifact.label)))
                            event.artifact.textContent?.let {
                                put(GenAiAttributes.ARTIFACT_TEXT, OtelAttributeValue.Text(clip(it)))
                            }
                        }
                    },
                )
                is VerificationFailed -> note(
                    event.taskDefinitionId,
                    "aive.verification_failed",
                    at,
                    reasonAttributes(event.reason),
                )
                is HumanDecisionRequired -> note(
                    event.taskDefinitionId,
                    "aive.human_decision_required",
                    at,
                    reasonAttributes(event.reason),
                )
                is RetryScheduled -> open(event.taskDefinitionId)
                    ?.close(at, OtelStatusCode.Error, "retry_scheduled", event.reason)
                is TaskEscalated -> open(event.taskDefinitionId)
                    ?.close(at, OtelStatusCode.Error, "escalated", event.reason)
                is TaskCompleted -> open(event.taskDefinitionId)
                    ?.close(at, OtelStatusCode.Ok, null, null)
                is TaskFailed -> open(event.taskDefinitionId)
                    ?.close(at, OtelStatusCode.Error, "task_failed", event.reason)
                is TaskCancelled -> open(event.taskDefinitionId)
                    ?.close(at, OtelStatusCode.Error, "cancelled", null)
                is WorkflowCompleted -> finish(at, OtelStatusCode.Ok, null, null)
                is WorkflowFailed -> finish(at, OtelStatusCode.Error, "workflow_failed", event.reason)
                is WorkflowCancelled -> finish(at, OtelStatusCode.Error, "cancelled", null)
                is ProviderUsageRecorded -> {
                    val index = chatCounts[event.taskDefinitionId] ?: 0
                    chatCounts[event.taskDefinitionId] = index + 1
                    val chat = Chat(event, index)
                    val owner = open(event.taskDefinitionId)
                        ?: attempts.lastOrNull { it.taskId == event.taskDefinitionId }
                    if (owner != null) owner.chats += chat else orphanChats += chat
                }
            }
        }

        fun spans(): List<OtelSpan> {
            val runStart = start ?: return emptyList()
            val runEnd = maxOf(end ?: last, runStart)
            attempts.filter { it.end == null }.forEach { it.end = runEnd }

            val result = mutableListOf<OtelSpan>()
            val allChats = attempts.flatMap { it.chats } + orphanChats
            result += OtelSpan(
                traceId = traceId,
                spanId = rootSpanId,
                parentSpanId = null,
                name = listOfNotNull(GenAiAttributes.OPERATION_INVOKE_WORKFLOW, workflowName).joinToString(" "),
                kind = OtelSpanKind.Internal,
                startEpochMillis = runStart,
                endEpochMillis = runEnd,
                attributes = buildMap<String, OtelAttributeValue> {
                    put(GenAiAttributes.OPERATION_NAME, OtelAttributeValue.Text(GenAiAttributes.OPERATION_INVOKE_WORKFLOW))
                    workflowName?.let { put(GenAiAttributes.WORKFLOW_NAME, OtelAttributeValue.Text(it)) }
                    put(GenAiAttributes.CONVERSATION_ID, OtelAttributeValue.Text(runId))
                    put(GenAiAttributes.WORKFLOW_RUN_ID, OtelAttributeValue.Text(runId))
                    projectId?.let { put(GenAiAttributes.PROJECT_ID, OtelAttributeValue.Text(it)) }
                    putUsage(allChats)
                    errorType?.let { put(GenAiAttributes.ERROR_TYPE, OtelAttributeValue.Text(it)) }
                    if (content.includeContent) {
                        objective?.let { put(GenAiAttributes.WORKFLOW_OBJECTIVE, OtelAttributeValue.Text(clip(it))) }
                    }
                },
                events = rootEvents.toList(),
                status = status,
                statusMessage = statusMessage,
            )
            attempts.forEach { attempt ->
                val attemptSpanId = spanIdFor("task|$runId|${attempt.taskId.value}|${attempt.attempt}|${attempt.ordinal}")
                val providers = attempt.chats.map { it.usage.providerId.value }.distinct()
                result += OtelSpan(
                    traceId = traceId,
                    spanId = attemptSpanId,
                    parentSpanId = rootSpanId,
                    name = listOfNotNull(GenAiAttributes.OPERATION_INVOKE_AGENT, attempt.roleId).joinToString(" "),
                    kind = OtelSpanKind.Internal,
                    startEpochMillis = attempt.start,
                    endEpochMillis = maxOf(requireNotNull(attempt.end), attempt.start),
                    attributes = buildMap<String, OtelAttributeValue> {
                        put(GenAiAttributes.OPERATION_NAME, OtelAttributeValue.Text(GenAiAttributes.OPERATION_INVOKE_AGENT))
                        attempt.roleId?.let { put(GenAiAttributes.AGENT_NAME, OtelAttributeValue.Text(it)) }
                        providers.singleOrNull()?.let {
                            put(GenAiAttributes.PROVIDER_NAME, OtelAttributeValue.Text(genAiProviderName(it)))
                        }
                        put(GenAiAttributes.CONVERSATION_ID, OtelAttributeValue.Text(runId))
                        put(GenAiAttributes.WORKFLOW_RUN_ID, OtelAttributeValue.Text(runId))
                        put(GenAiAttributes.TASK_ID, OtelAttributeValue.Text(attempt.taskId.value))
                        put(GenAiAttributes.TASK_ATTEMPT, OtelAttributeValue.Integer(attempt.attempt.toLong()))
                        attempt.executorKind?.let { put(GenAiAttributes.EXECUTOR_KIND, OtelAttributeValue.Text(it)) }
                        putUsage(attempt.chats)
                        attempt.errorType?.let { put(GenAiAttributes.ERROR_TYPE, OtelAttributeValue.Text(it)) }
                    },
                    events = attempt.events.toList(),
                    status = attempt.status,
                    statusMessage = attempt.statusMessage,
                )
                attempt.chats.forEach { chat ->
                    result += chatSpan(chat, attemptSpanId, "${attempt.attempt}|${attempt.ordinal}")
                }
            }
            orphanChats.forEach { chat -> result += chatSpan(chat, rootSpanId, "none") }
            return result
        }

        private fun chatSpan(chat: Chat, parentSpanId: String, attemptKey: String): OtelSpan {
            val usage = chat.usage
            val endAt = usage.occurredAtEpochMillis
            val startAt = usage.latencyMillis?.takeIf { it > 0 }?.let { endAt - it } ?: endAt
            return OtelSpan(
                traceId = traceId,
                spanId = spanIdFor("chat|$runId|${usage.taskDefinitionId.value}|$attemptKey|${chat.index}"),
                parentSpanId = parentSpanId,
                name = GenAiAttributes.OPERATION_CHAT,
                kind = OtelSpanKind.Client,
                startEpochMillis = startAt,
                endEpochMillis = endAt,
                attributes = buildMap<String, OtelAttributeValue> {
                    put(GenAiAttributes.OPERATION_NAME, OtelAttributeValue.Text(GenAiAttributes.OPERATION_CHAT))
                    put(GenAiAttributes.PROVIDER_NAME, OtelAttributeValue.Text(genAiProviderName(usage.providerId.value)))
                    put(GenAiAttributes.CONVERSATION_ID, OtelAttributeValue.Text(runId))
                    put(GenAiAttributes.PROVIDER_ID, OtelAttributeValue.Text(usage.providerId.value))
                    put(GenAiAttributes.PROVIDER_RUN_ID, OtelAttributeValue.Text(usage.providerRunId.value))
                    put(GenAiAttributes.TASK_ID, OtelAttributeValue.Text(usage.taskDefinitionId.value))
                    usage.inputTokens?.let { put(GenAiAttributes.USAGE_INPUT_TOKENS, OtelAttributeValue.Integer(it)) }
                    usage.outputTokens?.let { put(GenAiAttributes.USAGE_OUTPUT_TOKENS, OtelAttributeValue.Integer(it)) }
                    usage.costUsd?.let { put(GenAiAttributes.USAGE_COST_USD, OtelAttributeValue.Decimal(it)) }
                    usage.cacheHitFraction?.let {
                        put(GenAiAttributes.USAGE_CACHE_HIT_FRACTION, OtelAttributeValue.Decimal(it.toDouble()))
                    }
                },
            )
        }

        private fun MutableMap<String, OtelAttributeValue>.putUsage(chats: List<Chat>) {
            val input = chats.mapNotNull { it.usage.inputTokens }
            val output = chats.mapNotNull { it.usage.outputTokens }
            val cost = chats.mapNotNull { it.usage.costUsd }
            if (input.isNotEmpty()) put(GenAiAttributes.USAGE_INPUT_TOKENS, OtelAttributeValue.Integer(input.sum()))
            if (output.isNotEmpty()) put(GenAiAttributes.USAGE_OUTPUT_TOKENS, OtelAttributeValue.Integer(output.sum()))
            if (cost.isNotEmpty()) put(GenAiAttributes.USAGE_COST_USD, OtelAttributeValue.Decimal(cost.sum()))
        }

        private fun open(taskId: TaskDefinitionId): Attempt? =
            attempts.lastOrNull { it.taskId == taskId && it.end == null }

        private fun note(
            taskId: TaskDefinitionId?,
            name: String,
            at: Long,
            attributes: Map<String, OtelAttributeValue>,
        ) {
            val withTask = if (taskId == null) attributes else attributes + (GenAiAttributes.TASK_ID to OtelAttributeValue.Text(taskId.value))
            val target = taskId?.let { open(it) }?.events ?: rootEvents
            target += OtelSpanEvent(name, at, withTask)
        }

        private fun reasonAttributes(reason: String): Map<String, OtelAttributeValue> =
            if (content.includeContent) mapOf(GenAiAttributes.REASON to OtelAttributeValue.Text(clip(reason))) else emptyMap()

        private fun Attempt.close(at: Long, code: OtelStatusCode, error: String?, reason: String?) {
            this.end = at
            this.status = code
            this.errorType = error
            this.statusMessage = reason?.takeIf { content.includeContent }?.let { clip(it) }
        }

        private fun finish(at: Long, code: OtelStatusCode, error: String?, reason: String?) {
            end = at
            status = code
            errorType = error
            statusMessage = reason?.takeIf { content.includeContent }?.let { clip(it) }
        }

        // Exported content passes the same scrub as the log before it leaves the device.
        private fun clip(text: String): String =
            com.hereliesaz.geministrator.control.ControlRedaction.scrub(text).let {
                if (it.length <= content.maxContentChars) it else it.take(content.maxContentChars)
            }
    }
}

// Deterministic ids: FNV-1a 64-bit over the UTF-8 key. Two differently-seeded hashes make the
// 128-bit trace id. OTLP forbids all-zero ids, so a zero hash is replaced by one.

internal fun traceIdFor(runId: String): String =
    hex64(fnv1a64("aive-trace|$runId", FNV_OFFSET_BASIS)) + hex64(fnv1a64("aive-trace|$runId", FNV_SECOND_BASIS))

internal fun spanIdFor(key: String): String = hex64(fnv1a64("aive-span|$key", FNV_OFFSET_BASIS))

private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
private const val FNV_SECOND_BASIS: Long = 0x6c62272e07bb0142L
private const val FNV_PRIME: Long = 0x100000001b3L

private fun fnv1a64(key: String, basis: Long): Long {
    var hash = basis
    for (byte in key.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= FNV_PRIME
    }
    return if (hash == 0L) 1L else hash
}

private fun hex64(value: Long): String = value.toULong().toString(16).padStart(16, '0')
