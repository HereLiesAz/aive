package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Self-updating procedural memory as diff-gated edits (docs/architecture/PROCEDURAL_MEMORY.md §10).
 * Recorded run history (failure and completion events, executor receipts, the keeper's messages to
 * an agent) is reduced to observations; deterministic heuristics turn repeated patterns into a
 * proposed edit of the responsible role's instructions, shown as old -> new text with the evidence.
 * Nothing here ever changes a role: approval returns the edited role collection for the caller to
 * save through the ordinary role save path, and only when the keeper approves.
 */

@Serializable
enum class RoleObservationKind {
    /** A task the role was responsible for failed a check or failed outright. */
    Failure,
    /** A task completed after at least one failure in the same run. */
    Fix,
    /** The keeper messaged the role's agent while it worked. */
    KeeperMessage,
}

/** One bounded, redacted fact from run history. [detail] is untrusted text: quoted, never obeyed. */
@Serializable
data class RoleObservation(
    val kind: RoleObservationKind,
    val roleId: RoleDefinitionId,
    val workflowRunId: String,
    val taskDefinitionId: String? = null,
    val attempt: Int = 1,
    val detail: String = "",
    val atEpochMillis: Long,
) {
    /** Identity for deduplication: the same event observed twice is one observation. */
    val key: String
        get() = when (kind) {
            RoleObservationKind.Failure ->
                "f|${roleId.value}|$workflowRunId|$taskDefinitionId|$attempt|${normalizeFailure(detail)}"
            RoleObservationKind.Fix -> "x|${roleId.value}|$workflowRunId|$taskDefinitionId"
            RoleObservationKind.KeeperMessage ->
                "k|${roleId.value}|$workflowRunId|$taskDefinitionId|$atEpochMillis|$detail"
        }
}

@Serializable
enum class RoleInstructionEvidenceKind { RepeatedFailure, FailuresThenFix, KeeperCorrection }

/** One added instruction line and the observations that motivated it. */
@Serializable
data class RoleInstructionChange(
    val line: String,
    val kind: RoleInstructionEvidenceKind,
    val evidence: List<RoleObservation>,
)

@Serializable
enum class RoleInstructionProposalStatus { Pending, Approved, Dismissed, Superseded }

/** A reviewable edit of one role's instructions: [baseInstructions] -> [proposedInstructions]. */
@Serializable
data class RoleInstructionProposal(
    val id: String,
    val roleId: RoleDefinitionId,
    val baseInstructions: String,
    val proposedInstructions: String,
    val changes: List<RoleInstructionChange>,
    val createdAtEpochMillis: Long,
    val status: RoleInstructionProposalStatus = RoleInstructionProposalStatus.Pending,
) {
    /** A unified-style diff of the instructions, for review and copying. */
    fun diff(): String = buildString {
        append("--- ${roleId.value} instructions (current)\n")
        append("+++ ${roleId.value} instructions (proposed)\n")
        // Proposals only append, so the (trimmed) old text is a prefix of the new one.
        val kept = baseInstructions.trimEnd()
        if (kept.isNotEmpty()) kept.lines().forEach { append(' ').append(it).append('\n') }
        val added = proposedInstructions.removePrefix(kept).removePrefix("\n")
        added.lines().forEach { append('+').append(it).append('\n') }
    }.trimEnd('\n')
}

/** Turns run history into observations. Pure; reads only reasons, ids, attempts and the keeper's text. */
object RoleInstructionObservations {
    fun fromRun(
        run: WorkflowRun,
        events: List<WorkflowEvent>,
        traces: List<ProcedureTrace> = emptyList(),
    ): List<RoleObservation> {
        val runId = run.id.value
        val roleOfTask = mutableMapOf<String, RoleDefinitionId>()
        run.taskRuns.forEach { (taskId, taskRun) -> taskRun.assignedRoleId?.let { roleOfTask[taskId.value] = it } }
        val attemptOfTask = mutableMapOf<String, Int>()
        val failedTasks = mutableSetOf<String>()
        val failedAttempts = mutableSetOf<Pair<String, Int>>()
        val observations = mutableListOf<RoleObservation>()
        fun failure(task: TaskDefinitionId, reason: String, at: Long) {
            val role = roleOfTask[task.value] ?: return
            val attempt = attemptOfTask[task.value] ?: 1
            failedTasks += task.value
            failedAttempts += task.value to attempt
            observations += RoleObservation(
                RoleObservationKind.Failure, role, runId, task.value, attempt, boundedDetail(reason), at,
            )
        }
        fun fix(task: String, at: Long) {
            if (task !in failedTasks) return
            val role = roleOfTask[task] ?: return
            observations += RoleObservation(
                RoleObservationKind.Fix, role, runId, task, attemptOfTask[task] ?: 1, "", at,
            )
        }
        events.sortedBy { it.occurredAtEpochMillis }.forEach { event ->
            when (event) {
                is AgentAssigned -> roleOfTask[event.taskDefinitionId.value] = event.roleId
                is ExecutorAssigned -> event.responsibilityRoleId?.let { roleOfTask[event.taskDefinitionId.value] = it }
                is TaskStarted -> attemptOfTask[event.taskDefinitionId.value] = event.attempt
                is VerificationFailed -> failure(event.taskDefinitionId, event.reason, event.occurredAtEpochMillis)
                is TaskFailed -> failure(event.taskDefinitionId, event.reason, event.occurredAtEpochMillis)
                is TaskCompleted -> fix(event.taskDefinitionId.value, event.occurredAtEpochMillis)
                else -> Unit
            }
        }
        // Executor receipts add failures the event log gave no reason for, and their recoveries.
        traces.filter { it.workflowId == runId }.sortedBy { it.recordedAtEpochMillis }.forEach { trace ->
            val task = trace.taskDefinitionId
            when (trace.outcome) {
                ProcedureTraceOutcome.Failed, ProcedureTraceOutcome.Threw -> {
                    val role = roleOfTask[task] ?: return@forEach
                    if ((task to trace.attempt) in failedAttempts) return@forEach
                    failedTasks += task
                    failedAttempts += task to trace.attempt
                    observations += RoleObservation(
                        RoleObservationKind.Failure, role, runId, task, trace.attempt,
                        "the ${trace.executorKind} executor reported ${trace.outcome.name.lowercase()}",
                        trace.recordedAtEpochMillis,
                    )
                }
                ProcedureTraceOutcome.Completed -> fix(task, trace.recordedAtEpochMillis)
                ProcedureTraceOutcome.Running, ProcedureTraceOutcome.Verifying -> Unit
            }
        }
        return observations.distinctBy { it.key }
    }

    fun keeperMessage(
        roleId: RoleDefinitionId,
        workflowRunId: String,
        taskDefinitionId: String?,
        text: String,
        atEpochMillis: Long,
    ): RoleObservation = RoleObservation(
        RoleObservationKind.KeeperMessage, roleId, workflowRunId, taskDefinitionId, 1, boundedDetail(text), atEpochMillis,
    )
}

/** Deterministic heuristics from observations to proposed instruction lines. */
object RoleInstructionProposer {
    const val LESSONS_HEADER: String = "Learned from past work:"
    /** The same failure this many times, across tasks or runs, is a recurring failure. */
    const val REPEATED_FAILURE_THRESHOLD: Int = 2
    /** This many failures of one task before it passed is a hard-won fix. */
    const val FAILURES_BEFORE_FIX_THRESHOLD: Int = 2
    /** This many corrective keeper messages to one role make a standing correction. */
    const val KEEPER_CORRECTION_THRESHOLD: Int = 2
    private const val MAX_LINES_PER_KIND: Int = 5

    private val correctionCue = Regex(
        "\\b(don't|don’t|dont|do not|never|always|instead|stop|wrong|should|must|remember)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a keeper's message reads as a correction rather than a question or a nudge. */
    fun isCorrection(text: String): Boolean = correctionCue.containsMatchIn(text)

    /** The instruction lines the observations support for [roleId], in a stable order. */
    fun changesFor(roleId: RoleDefinitionId, observations: List<RoleObservation>): List<RoleInstructionChange> {
        val mine = observations.filter { it.roleId == roleId }
        val failures = mine.filter { it.kind == RoleObservationKind.Failure }
        val repeated = failures
            .groupBy { normalizeFailure(it.detail) }
            .filter { (normalized, group) -> normalized.isNotBlank() && group.size >= REPEATED_FAILURE_THRESHOLD }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<RoleObservation>>> { it.value.size }.thenBy { it.key })
            .take(MAX_LINES_PER_KIND)
        val repeatedReasons = repeated.mapTo(hashSetOf()) { it.key }
        val changes = mutableListOf<RoleInstructionChange>()
        repeated.forEach { (_, group) ->
            changes += RoleInstructionChange(
                line = "Before finishing, check for this recurring failure: \"${quoted(group.first().detail)}\".",
                kind = RoleInstructionEvidenceKind.RepeatedFailure,
                evidence = group.sortedBy { it.atEpochMillis },
            )
        }
        mine.filter { it.kind == RoleObservationKind.Fix }
            .sortedWith(compareBy<RoleObservation> { it.workflowRunId }.thenBy { it.taskDefinitionId.orEmpty() })
            .mapNotNull { fix ->
                val before = failures.filter {
                    it.workflowRunId == fix.workflowRunId &&
                        it.taskDefinitionId == fix.taskDefinitionId &&
                        it.atEpochMillis <= fix.atEpochMillis
                }.sortedBy { it.atEpochMillis }
                val last = before.lastOrNull() ?: return@mapNotNull null
                if (before.size < FAILURES_BEFORE_FIX_THRESHOLD) return@mapNotNull null
                // A recurring failure already has its own line.
                if (normalizeFailure(last.detail) in repeatedReasons) return@mapNotNull null
                RoleInstructionChange(
                    line = "A task failed ${before.size} times before it passed (last: \"${quoted(last.detail)}\"); " +
                        "find and fix that cause before finishing similar work.",
                    kind = RoleInstructionEvidenceKind.FailuresThenFix,
                    evidence = before + fix,
                )
            }
            .take(MAX_LINES_PER_KIND)
            .forEach { changes += it }
        val corrections = mine
            .filter { it.kind == RoleObservationKind.KeeperMessage && isCorrection(it.detail) }
            .sortedBy { it.atEpochMillis }
        if (corrections.size >= KEEPER_CORRECTION_THRESHOLD) {
            corrections.distinctBy { it.detail.trim().lowercase() }.takeLast(MAX_LINES_PER_KIND).forEach { correction ->
                changes += RoleInstructionChange(
                    line = "The keeper has corrected this before: \"${quoted(correction.detail)}\".",
                    kind = RoleInstructionEvidenceKind.KeeperCorrection,
                    evidence = listOf(correction),
                )
            }
        }
        return changes.distinctBy { it.line }
    }

    /** [base] with [lines] appended under the lessons header, which is added once. */
    fun applyLines(base: String, lines: List<String>): String {
        if (lines.isEmpty()) return base
        val bullets = lines.joinToString("\n") { "- $it" }
        val trimmed = base.trimEnd()
        return when {
            trimmed.isEmpty() -> "$LESSONS_HEADER\n$bullets"
            LESSONS_HEADER in trimmed -> "$trimmed\n$bullets"
            else -> "$trimmed\n\n$LESSONS_HEADER\n$bullets"
        }
    }
}

/** What approving a proposal produced. */
sealed interface RoleInstructionApproval {
    /** The role collection with only the proposal's role changed; the caller saves it. */
    data class Applied(val roles: List<RoleDefinition>, val role: RoleDefinition) : RoleInstructionApproval
    /** The role changed or vanished since the proposal was drafted; nothing was applied. */
    data object Stale : RoleInstructionApproval
    /** No pending proposal has that id. */
    data object NotFound : RoleInstructionApproval
}

@Serializable
data class DismissedInstructionLine(val roleId: RoleDefinitionId, val line: String)

@Serializable
data class RoleInstructionProposalState(
    val observations: List<RoleObservation> = emptyList(),
    val proposals: List<RoleInstructionProposal> = emptyList(),
    val dismissed: List<DismissedInstructionLine> = emptyList(),
    val nextId: Long = 1L,
) {
    fun pendingFor(roleId: RoleDefinitionId): List<RoleInstructionProposal> =
        proposals.filter { it.roleId == roleId && it.status == RoleInstructionProposalStatus.Pending }
}

/**
 * The queue of proposed role-instruction edits. It never writes a role: [approve] only returns the
 * edited collection after an explicit keeper decision, and a proposal whose role changed since it
 * was drafted is superseded rather than applied. [load]/[save] persist the bounded state as JSON.
 * Not thread-safe; call it from one thread (the UI's).
 */
class RoleInstructionProposalLedger(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val maxObservations: Int = 500,
    private val maxHistory: Int = 200,
) {
    private val mutableState = MutableStateFlow(decode(load()))
    val state: StateFlow<RoleInstructionProposalState> = mutableState.asStateFlow()

    fun pendingFor(roleId: RoleDefinitionId): List<RoleInstructionProposal> = mutableState.value.pendingFor(roleId)

    /** Records [observations] and redrafts pending proposals for [roles]. Never changes a role. */
    fun observe(
        observations: List<RoleObservation>,
        roles: Collection<RoleDefinition>,
        nowEpochMillis: Long,
    ) {
        val current = mutableState.value
        val known = current.observations.mapTo(hashSetOf()) { it.key }
        val added = observations.filter { known.add(it.key) }
        val merged = (current.observations + added).takeLast(maxObservations)
        commit(redraft(current.copy(observations = merged), roles, nowEpochMillis))
    }

    /**
     * Applies a pending proposal to [roles] if its role's instructions are still the ones it was
     * drafted against. Marks it approved; the caller saves the returned collection.
     */
    fun approve(proposalId: String, roles: List<RoleDefinition>): RoleInstructionApproval {
        val current = mutableState.value
        val proposal = current.proposals.firstOrNull {
            it.id == proposalId && it.status == RoleInstructionProposalStatus.Pending
        } ?: return RoleInstructionApproval.NotFound
        val role = roles.firstOrNull { it.id == proposal.roleId }
        if (role == null || role.instructions != proposal.baseInstructions) {
            commit(current.withStatus(proposalId, RoleInstructionProposalStatus.Superseded))
            return RoleInstructionApproval.Stale
        }
        val updated = role.copy(instructions = proposal.proposedInstructions)
        commit(current.withStatus(proposalId, RoleInstructionProposalStatus.Approved))
        return RoleInstructionApproval.Applied(roles.map { if (it.id == role.id) updated else it }, updated)
    }

    /** Dismisses a pending proposal; nothing is applied and its lines are never proposed for that role again. */
    fun dismiss(proposalId: String): Boolean {
        val current = mutableState.value
        val proposal = current.proposals.firstOrNull {
            it.id == proposalId && it.status == RoleInstructionProposalStatus.Pending
        } ?: return false
        val dismissed = (current.dismissed + proposal.changes.map { DismissedInstructionLine(proposal.roleId, it.line) })
            .distinct()
        commit(current.withStatus(proposalId, RoleInstructionProposalStatus.Dismissed).copy(dismissed = dismissed))
        return true
    }

    private fun redraft(
        state: RoleInstructionProposalState,
        roles: Collection<RoleDefinition>,
        now: Long,
    ): RoleInstructionProposalState {
        var nextId = state.nextId
        var proposals = state.proposals
        roles.filter { it.executionSource == RoleExecutionSource.Agent }.forEach { role ->
            // Lines the keeper already decided on (dismissed, or approved even if the save has not
            // reached the roles yet) are never proposed again.
            val decided = state.dismissed.filter { it.roleId == role.id }.mapTo(hashSetOf()) { it.line }
            state.proposals
                .filter { it.roleId == role.id && it.status == RoleInstructionProposalStatus.Approved }
                .forEach { approved -> approved.changes.mapTo(decided) { it.line } }
            val changes = RoleInstructionProposer.changesFor(role.id, state.observations)
                .filter { it.line !in decided && it.line !in role.instructions }
            val pending = proposals.firstOrNull { it.roleId == role.id && it.status == RoleInstructionProposalStatus.Pending }
            val unchanged = pending != null &&
                pending.baseInstructions == role.instructions &&
                pending.changes.map { it.line } == changes.map { it.line }
            if (unchanged) return@forEach
            if (pending != null) {
                proposals = proposals.map {
                    if (it.id == pending.id) it.copy(status = RoleInstructionProposalStatus.Superseded) else it
                }
            }
            if (changes.isNotEmpty()) {
                proposals = proposals + RoleInstructionProposal(
                    id = "rip-${nextId++}",
                    roleId = role.id,
                    baseInstructions = role.instructions,
                    proposedInstructions = RoleInstructionProposer.applyLines(role.instructions, changes.map { it.line }),
                    changes = changes,
                    createdAtEpochMillis = now,
                )
            }
        }
        return state.copy(proposals = proposals, nextId = nextId)
    }

    private fun RoleInstructionProposalState.withStatus(
        id: String,
        status: RoleInstructionProposalStatus,
    ): RoleInstructionProposalState = copy(proposals = proposals.map { if (it.id == id) it.copy(status = status) else it })

    private fun commit(next: RoleInstructionProposalState) {
        val pending = next.proposals.filter { it.status == RoleInstructionProposalStatus.Pending }
        val history = next.proposals.filter { it.status != RoleInstructionProposalStatus.Pending }.takeLast(maxHistory)
        val bounded = next.copy(
            proposals = next.proposals.filter { it in pending || it in history },
            dismissed = next.dismissed.takeLast(maxHistory * 4),
        )
        mutableState.value = bounded
        runCatching { save(json.encodeToString(RoleInstructionProposalState.serializer(), bounded)) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(encoded: String?): RoleInstructionProposalState = encoded
            ?.let { runCatching { json.decodeFromString(RoleInstructionProposalState.serializer(), it) }.getOrNull() }
            ?: RoleInstructionProposalState()
    }
}

private const val MAX_DETAIL_CHARS = 200

/** Collapses whitespace and bounds untrusted text before it is stored or quoted. */
private fun boundedDetail(text: String): String {
    val collapsed = text.trim().replace(Regex("\\s+"), " ")
    return if (collapsed.length <= MAX_DETAIL_CHARS) collapsed else collapsed.take(MAX_DETAIL_CHARS - 1) + "…"
}

/** Groups failures that differ only in case, spacing or numbers. */
internal fun normalizeFailure(detail: String): String =
    detail.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()

/** Text quoted inside an instruction line keeps its own double quotes from closing the quote. */
private fun quoted(detail: String): String = detail.replace('"', '\'')
