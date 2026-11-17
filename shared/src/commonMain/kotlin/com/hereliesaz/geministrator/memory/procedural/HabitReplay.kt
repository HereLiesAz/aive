package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Habit replay (docs/architecture/PROCEDURAL_MEMORY.md, section 11). A formed habit executes only after the
 * keeper approved that habit at its current version. Text habits replay as a deterministic provider output
 * (no model call). Habits with effects become ordinary workflow steps, each behind its own human-approval
 * task; nothing here approves, widens or bypasses a gate.
 */

@Serializable
enum class ReplayStatus { Active, Quarantined, Revoked }

@Serializable
data class ReplayHabit(
    val template: ReplayTemplate,
    val version: Int = 1,
    val status: ReplayStatus = ReplayStatus.Active,
    /** The template digest the keeper approved; null when none is. A new version has a new digest, so needs a new approval. */
    val approvedDigest: String? = null,
    val reason: String? = null,
    val replays: Int = 0,
    val tokensAvoided: Long = 0,
    /** Times an agent invoked this habit when the matcher had not fired it. */
    val matcherMisses: Int = 0,
) {
    val id: String get() = template.id
    val approved: Boolean get() = approvedDigest != null && approvedDigest == template.digest
    val ref: String get() = "proc:$id@$version"
}

@Serializable
data class ReplayLedgerState(
    val sources: List<ReplaySourceRun> = emptyList(),
    val habits: List<ReplayHabit> = emptyList(),
    /** `operation@version` of executor habits the keeper approved. */
    val approvedOperations: List<String> = emptyList(),
    /** Operations (any version) quarantined after a rejection or a failed verification. */
    val quarantinedOperations: List<String> = emptyList(),
    val operationMatcherMisses: Int = 0,
    /** Task runs already read, so a restart does not count one twice (bounded). */
    val seen: List<String> = emptyList(),
) {
    val tokensAvoided: Long get() = habits.sumOf { it.tokensAvoided }
    val replays: Int get() = habits.sumOf { it.replays }
}

/** Keeps the replay state. [load] and [save] are the durable store; a failed save never fails the caller. */
class HabitReplayLedger(
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    private val mutable = MutableStateFlow(decode(load()))
    val state: StateFlow<ReplayLedgerState> = mutable.asStateFlow()

    private fun change(transform: (ReplayLedgerState) -> ReplayLedgerState) {
        val next = mutable.updateAndGetCompat(transform)
        runCatching { save(json.encodeToString(ReplayLedgerState.serializer(), next)) }
    }

    private fun MutableStateFlow<ReplayLedgerState>.updateAndGetCompat(transform: (ReplayLedgerState) -> ReplayLedgerState): ReplayLedgerState {
        update(transform)
        return value
    }

    fun hasSeen(key: String): Boolean = key in mutable.value.seen

    fun markSeen(key: String) = change { it.copy(seen = (it.seen + key).takeLast(MAX_SEEN)) }

    /** Adds an accepted run and regenerates its class. Never revives a quarantined or revoked habit. */
    fun recordSource(run: ReplaySourceRun) = change { state ->
        if (state.sources.any { it.taskRunId == run.taskRunId && it.attempt == run.attempt }) return@change state
        val classId = run.classId
        val sameClass = (state.sources.filter { it.classId == classId } + run).takeLast(MAX_PER_CLASS)
        val others = state.sources.filter { it.classId != classId }
        val sources = (others + sameClass).takeLast(MAX_SOURCES)
        val made = ReplayGeneralizer.generalize(sameClass)
        val habits = if (made is ReplayGeneralization.Made) {
            val existing = state.habits.firstOrNull { it.id == classId }
            when {
                existing == null -> state.habits + ReplayHabit(made.template)
                existing.status != ReplayStatus.Active || existing.template.digest == made.template.digest -> state.habits
                else -> state.habits.map { if (it.id == classId) it.copy(template = made.template, version = it.version + 1) else it }
            }
        } else {
            state.habits
        }
        state.copy(sources = sources, habits = habits)
    }

    private fun edit(id: String, transform: (ReplayHabit) -> ReplayHabit) =
        change { s -> s.copy(habits = s.habits.map { if (it.id == id) transform(it) else it }) }

    /** The keeper approves this habit at its current digest; a quarantined or revoked one is reinstated by that act. */
    fun approve(id: String) = edit(id) {
        it.copy(status = ReplayStatus.Active, reason = null, approvedDigest = it.template.digest)
    }

    fun revoke(id: String) = edit(id) { it.copy(approvedDigest = null) }

    /** Never auto-edited: a quarantined habit stays as it was until the keeper acts. */
    fun quarantine(id: String, reason: String) = edit(id) {
        if (it.status == ReplayStatus.Quarantined) it else it.copy(status = ReplayStatus.Quarantined, reason = reason.take(200))
    }

    fun recordReplay(id: String, tokensAvoided: Long) = edit(id) {
        it.copy(replays = it.replays + 1, tokensAvoided = it.tokensAvoided + tokensAvoided.coerceAtLeast(0))
    }

    fun recordMiss(ref: String) = change { s ->
        s.copy(
            habits = s.habits.map { if (it.ref == ref) it.copy(matcherMisses = it.matcherMisses + 1) else it },
            operationMatcherMisses = s.operationMatcherMisses + if (s.habits.none { it.ref == ref }) 1 else 0,
        )
    }

    fun approveOperation(operation: String, version: Int) = change {
        it.copy(
            approvedOperations = (it.approvedOperations + "$operation@$version").distinct(),
            quarantinedOperations = it.quarantinedOperations - operation,
        )
    }

    fun revokeOperation(operation: String, version: Int) =
        change { it.copy(approvedOperations = it.approvedOperations - "$operation@$version") }

    fun quarantineOperation(operation: String) =
        change { it.copy(quarantinedOperations = (it.quarantinedOperations + operation).distinct().takeLast(MAX_SEEN)) }

    fun isOperationApproved(operation: String, version: Int): Boolean =
        "$operation@$version" in mutable.value.approvedOperations && operation !in mutable.value.quarantinedOperations

    /** Forgetting, clearing memory or switching learning off: every source, template, approval and counter goes. */
    fun invalidateAll() = change { ReplayLedgerState() }

    companion object {
        const val MAX_PER_CLASS = 8
        const val MAX_SOURCES = 200
        const val MAX_SEEN = 2000
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun decode(encoded: String?): ReplayLedgerState = encoded
            ?.let { runCatching { json.decodeFromString(ReplayLedgerState.serializer(), it) }.getOrNull() }
            ?: ReplayLedgerState()
    }
}

/** What the planner needs to know about one task about to start. */
data class ReplayQuery(
    val roleId: String,
    val inputs: ReplayInputs,
    val requiredCapabilities: Set<AgentCapability>,
    val requiredArtifactKinds: Set<String>,
    /** Untrusted content reached the task (docs/THREAT_MODEL.md, context provenance). */
    val untrusted: Boolean,
    /** The task names a provider by constraint: a replay never overrides that. */
    val constrained: Boolean,
)

sealed interface ReplayDecision {
    data class Replay(val habit: ReplayHabit, val text: String) : ReplayDecision {
        val tokensAvoided: Long get() = habit.template.tokensAvoided
        val provenance: String get() = "Replayed from ${habit.template.sourceRuns} earlier runs (habit ${habit.id}@${habit.version})"
    }

    data class NotApplicable(val reason: String) : ReplayDecision
}

/** The one place that decides whether a task may be answered by replay. Pure given the ledger. */
class HabitReplayPlanner(
    val ledger: HabitReplayLedger,
    /** False while memory or habit learning is off: nothing replays then. */
    private val allowed: () -> Boolean = { true },
) {
    fun decide(query: ReplayQuery): ReplayDecision {
        fun no(reason: String) = ReplayDecision.NotApplicable(reason)
        if (!allowed()) return no("habit learning is off")
        if (query.constrained) return no("the task names its provider")
        if (query.untrusted) return no("untrusted content reached the task")
        if (!query.requiredCapabilities.all { it in TEXT_SAFE }) return no("the task needs a capability with outside effects")
        val habit = ledger.state.value.habits.firstOrNull { it.id == ReplayShape.classId(query.roleId, query.inputs) }
            ?: return no("no habit matches")
        if (habit.status != ReplayStatus.Active) return no("the habit is ${habit.status.name.lowercase()}")
        if (!habit.approved) return no("the keeper has not approved this version")
        if (habit.template.digestNow() != habit.template.digest) return no("the version digest does not match")
        if (query.requiredArtifactKinds.any { it != habit.template.artifactKind }) return no("the task wants another kind of output")
        val text = habit.template.render(query.inputs) ?: return no("a slot did not bind")
        val named = buildString {
            append(query.inputs.objective).append('\n')
            query.inputs.criteria.forEach { append(it).append('\n') }
            query.inputs.context.forEach { append(it.text).append('\n') }
        }
        ReplayGuards.violation(text, named)?.let { return no("the text has ${it.second}") }
        ReplayCriteria.check(query.inputs.criteria, text).let { if (!it.passed) return no("it fails: " + it.failures.joinToString("; ")) }
        return ReplayDecision.Replay(habit, text)
    }

    companion object {
        /** Capabilities with no effect outside the app. Any other capability disqualifies a habit for good. */
        val TEXT_SAFE: Set<AgentCapability> = setOf(AgentCapability.Research, AgentCapability.PlanGeneration)
    }
}

/** `/habit proc:<ref>` on a line of its own: how a small model that has no tool channel chooses an offered habit. */
object HabitInvokeCommand {
    private val line = Regex("(?m)^[ \\t]*/habit[ \\t]+(proc:[A-Za-z0-9._:/-]+@\\d+)[ \\t]*\\r?$")

    /** The distinct references written as the command, at most [MAX]. Anything that is not exactly the grammar is ignored. */
    fun refsIn(text: String): List<String> = line.findAll(text).map { it.groupValues[1] }.distinct().take(MAX).toList()

    const val MAX = 3
}

/** The compact catalogue of approved habits an agent is shown, with how to choose one. */
object HabitCatalogue {
    const val MAX_ENTRIES = 5

    fun render(state: ReplayLedgerState, executorHabits: List<FormedHabit>): String? {
        val text = state.habits.filter { it.status == ReplayStatus.Active && it.approved }
            .map { "- ${it.ref}: text for tasks like \"${it.template.shape.take(80)}\", from ${it.template.sourceRuns} accepted runs, ${it.template.slots.size} inputs." }
        val steps = executorHabits.filter { HabitStepReplay.eligible(it, state) }
            .map { "- proc:${it.operation}@${it.version}: ${it.operation}, ${it.verifiedUses} reviewed and approved uses, none turned back." }
        val all = (text + steps).take(MAX_ENTRIES)
        if (all.isEmpty()) return null
        return "Habits the keeper approved. To use one, write a line of its own: /habit <ref>. It runs only if it is still approved and its checks pass; every step keeps its normal approvals.\n" +
            all.joinToString("\n")
    }
}

/** Turns an approved executor habit or sequence into ordinary tasks. Adds no permission of its own. */
object HabitStepReplay {
    const val MIN_VERIFIED_USES = 3

    fun eligible(habit: FormedHabit, state: ReplayLedgerState): Boolean =
        habit.offered &&
            habit.verifiedUses >= MIN_VERIFIED_USES &&
            habit.rejectedUses == 0 &&
            habit.recentFailedUses == 0 &&
            habit.openContrasts.isEmpty() &&
            habit.activeOverride == null &&
            habit.executorKind != "Distributed" &&
            "${habit.operation}@${habit.version}" in state.approvedOperations &&
            habit.operation !in state.quarantinedOperations

    /**
     * The one task for the habit [ref] an agent chose (`proc:<operation>@<version>`), or why not. It applies the
     * same eligibility as [plan]: approved at that version, not quarantined, verified and unturned. The task is
     * the ordinary [HabitInvocation] proposal; adding it behind its own human-approval task is the caller's act.
     */
    fun planFor(
        habits: List<FormedHabit>,
        ref: String,
        state: ReplayLedgerState,
        idPrefix: String,
    ): HabitInvocation.Result {
        val body = ref.removePrefix("proc:")
        val version = body.substringAfterLast('@').toIntOrNull()
            ?: return HabitInvocation.Result.Refused("That is not a habit reference.")
        val operation = body.substringBeforeLast('@')
        val habit = habits.firstOrNull { it.operation == operation && it.version == version }
            ?: return HabitInvocation.Result.Refused("No habit by that name and version has formed.")
        if (operation in state.quarantinedOperations) {
            return HabitInvocation.Result.Refused("That habit is quarantined until the keeper approves it again.")
        }
        if (!eligible(habit, state)) {
            return HabitInvocation.Result.Refused("That habit is not approved and verified at this version.")
        }
        return HabitInvocation.propose(habit, TaskDefinitionId("$idPrefix-1"))
    }

    /**
     * The tasks for the task text [objective], or empty. They are the same [HabitInvocation] proposals the
     * keeper can add by hand: each carries a human-approval policy and, once added through
     * [com.hereliesaz.geministrator.workflow.WorkflowCompositionService.addProposedTasks], its own human-approval
     * task. Delegation never approves those tasks.
     */
    fun plan(
        habits: List<FormedHabit>,
        sequences: List<FormedSequence>,
        objective: String,
        state: ReplayLedgerState,
        idPrefix: String = "habit",
    ): List<TaskDefinition> {
        val usable = habits.filter { eligible(it, state) }
        if (usable.isEmpty()) return emptyList()
        HabitOffers.selectSequences(sequences, objective)
            .firstOrNull { sequence -> sequence.operations.all { op -> usable.any { it.operation == op } } }
            ?.let { sequence ->
                val result = HabitInvocation.proposeSequence(sequence, usable, idPrefix)
                if (result is HabitInvocation.SequenceResult.Proposed) return result.tasks
            }
        val top = HabitOffers.select(usable, objective).firstOrNull() ?: return emptyList()
        return when (val result = HabitInvocation.propose(top.habit, TaskDefinitionId("$idPrefix-1"))) {
            is HabitInvocation.Result.Proposed -> listOf(result.task)
            is HabitInvocation.Result.Refused -> emptyList()
        }
    }
}
