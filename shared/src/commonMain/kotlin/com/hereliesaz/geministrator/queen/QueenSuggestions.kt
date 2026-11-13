package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.isUntrusted
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.canMutate
import com.hereliesaz.geministrator.workflow.formatUsd
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.workflow.knownCostUsd
import com.hereliesaz.geministrator.workflow.taintInputs

/**
 * What the queen can suggest while a swarm runs: a closed menu. Some she applies herself when the keeper
 * taps (they change only the running swarm, with calls the keeper already has); the rest lead to the place where
 * the keeper changes the thing, because a running swarm cannot take it (roles and Sources are fixed per run).
 * Never on the menu: removing or loosening an approval, moving work to a Source that can write to the repository,
 * skipping a step, touching the Nanny.
 */
enum class QueenAction {
    /** Give a step that has not started a new job. Applied by her on the keeper's tap. */
    RewordStep,

    /** Run a finished step and what follows it again. Applied by her on the keeper's tap, after asking. */
    RedoFromStep,

    /** A step moved to another Source: the keeper changes it on the creature's Mind, from the next run. */
    SwapSource,

    /** A role is close to its token budget: the keeper raises it on the creature's Mind. */
    RaiseBudget,

    /** The run is close to its allowance: the keeper changes it in the Keeper's room. */
    RaiseAllowance,

    /** Rest the swarm (the keeper's own pause): what is running finishes, nothing new starts. */
    Rest,
    ;

    /** Whether the queen applies it herself on the keeper's tap; the others only lead somewhere. */
    val appliesHere: Boolean get() = this == RewordStep || this == RedoFromStep || this == Rest

    companion object {
        fun parse(raw: String?): QueenAction? = entries.firstOrNull { it.name == raw }
    }
}

/** The ledger row a suggestion note stands for, and whether accepting it asks first. */
data class QueenSuggestionRef(val proposalId: String, val action: QueenAction, val confirm: Boolean)

/** How often and how much the queen suggests. The keeper's Quiet and Helpful levels set only how many may wait at once. */
object QueenPacing {
    /** Between one new suggestion and the next, in a run. */
    const val COOLDOWN_MILLIS: Long = 15L * 60_000L

    /** At most this many for one run, whatever their fate. */
    const val MAX_PER_RUN: Int = 5

    /** A suggestion the keeper dismissed is not offered again, for the same step and action, for this long (any run). */
    const val DISMISSED_MEMORY_MILLIS: Long = 14L * 24 * 3_600_000L

    /** How many may be open (waiting on the keeper) at once. */
    fun openLimit(level: QueenLevel): Int = if (level == QueenLevel.Helpful) 2 else 1
}

/** The subject a run-wide suggestion (the allowance, resting) is filed under. */
internal const val QUEEN_RUN_SUBJECT: String = "swarm"

private const val COST_PACE_PERCENT = 70
private const val STEPS_LEFT_PERCENT = 40
private const val SUMMARY_CHARS = 200
private const val WORDING_CHARS = 280

private val OPEN_STATUSES = setOf(TaskRunStatus.Created, TaskRunStatus.Blocked, TaskRunStatus.Ready, TaskRunStatus.Retrying)

/** One thing she could suggest right now, before the pacing rules decide whether she says it. */
internal data class QueenCandidate(
    val action: QueenAction,
    /** The step it is about, or [QUEEN_RUN_SUBJECT]. */
    val subject: String,
    val voice: QueenVoice,
    val args: List<String>,
    val outside: Boolean,
    val newText: String = "",
)

internal fun stepNumber(definition: WorkflowDefinition, taskId: String) = definition.tasks.indexOfFirst { it.id.value == taskId } + 1

internal fun outsideFor(definition: WorkflowDefinition, run: WorkflowRun, taskId: String): Boolean {
    val task = definition.tasks.firstOrNull { it.id.value == taskId } ?: return run.objectiveProvenance.isUntrusted()
    return definition.taintInputs(run, task).any { it.isUntrustedContext() }
}

/** Under way (a creature waiting on the keeper does not stop it), and neither frozen by the Nanny nor stopped by an allowance. */
private fun WorkflowRun.live(): Boolean =
    (status == WorkflowRunStatus.Running || status == WorkflowRunStatus.AwaitingHuman) && globalPause == null && spendPause == null

private fun WorkflowRun.underWay(): Boolean = status == WorkflowRunStatus.Running || status == WorkflowRunStatus.AwaitingHuman

/**
 * What the queen could suggest from [signals] and the run's cost, most serious first. Pure: the same state gives the
 * same candidates. The queen makes her own judgement of whether the swarm is working (a step that keeps failing, a
 * Source that gave out, a budget running low), with or without a Nanny. The Nanny's job is different: making sure it
 * works as well as possible. Her findings are relayed alongside (see [nannyRelay]), and the queen offers a rest while
 * the Nanny's report is still being checked.
 */
internal fun queenCandidates(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    signals: List<QueenSignal>,
    caps: SpendCaps,
): List<QueenCandidate> {
    if (!run.live()) return emptyList()
    val nanny = hasNanny(definition, run)
    val found = mutableListOf<QueenCandidate>()
    // A failing step that builds on a finished one: the finished one is the place to go back to.
    for (signal in signals.filterIsInstance<QueenSignal.RepeatedFailure>()) {
        val task = definition.tasks.firstOrNull { it.id.value == signal.taskId } ?: continue
        val upstream = definition.tasks.firstOrNull { dep ->
            dep.id in task.dependsOn && run.taskRuns[dep.id]?.status == TaskRunStatus.Completed
        } ?: continue
        found += QueenCandidate(
            QueenAction.RedoFromStep,
            upstream.id.value,
            QueenVoice.SuggestRedoStep,
            listOf(
                stepNumber(definition, upstream.id.value).toString(), queenSlot(upstream.name),
                stepNumber(definition, task.id.value).toString(), signal.failedAttempts.toString(),
            ),
            outsideFor(definition, run, task.id.value),
        )
    }
    for (signal in signals.filterIsInstance<QueenSignal.FailedOver>()) {
        val name = definition.tasks.firstOrNull { it.id.value == signal.taskId }?.name.orEmpty()
        found += QueenCandidate(
            QueenAction.SwapSource, signal.taskId, QueenVoice.SuggestSwapSource,
            listOf(stepNumber(definition, signal.taskId).toString(), queenSlot(name)),
            outsideFor(definition, run, signal.taskId),
        )
    }
    for (signal in signals.filterIsInstance<QueenSignal.BudgetBurn>()) {
        found += QueenCandidate(
            QueenAction.RaiseBudget, signal.taskId, QueenVoice.SuggestRaiseBudget,
            listOf(queenSlot(signal.roleName), signal.percent.toString()),
            outsideFor(definition, run, signal.taskId),
        )
    }
    costPace(definition, run, caps)?.let { found += it }
    // The Nanny's findings are one more input to her own judgement; she keeps her own candidate when both name the same step and action.
    nannyCandidates(definition, run).forEach { n -> if (found.none { it.action == n.action && it.subject == n.subject }) found += n }
    if (nanny && nannyPhase(definition, run) == NannyPhase.AwaitingReview && run.keeperPause == null) {
        found += QueenCandidate(QueenAction.Rest, QUEEN_RUN_SUBJECT, QueenVoice.SuggestRestNanny, emptyList(), outside = false)
    }
    return found
}

/** The run's known cost is close to its allowance while many steps are still to do. Null with no allowance set. */
private fun costPace(definition: WorkflowDefinition, run: WorkflowRun, caps: SpendCaps): QueenCandidate? {
    val cap = caps.runCostCapUsd ?: return null
    val total = definition.tasks.size
    if (total == 0) return null
    val cost = run.knownCostUsd()
    if (cost >= cap) return null
    val percent = (cost * 100.0 / cap).toInt()
    val left = definition.tasks.count { run.taskRuns[it.id]?.status.let { s -> s != TaskRunStatus.Completed && s != TaskRunStatus.Cancelled } }
    if (percent < COST_PACE_PERCENT || left * 100 < total * STEPS_LEFT_PERCENT) return null
    return QueenCandidate(
        QueenAction.RaiseAllowance, QUEEN_RUN_SUBJECT, QueenVoice.SuggestRaiseAllowance,
        listOf(percent.toString(), formatUsd(cap)),
        outside = false,
    )
}

/** The proposal id of a suggestion: the same state gives the same id, so the same signal never repeats. */
internal fun queenSuggestionId(run: WorkflowRun, action: QueenAction, subject: String): String =
    queenNoteId("suggest", run.id.value, action.name, subject)

/** The queen's rows for [runId] still waiting on the keeper. */
fun openQueenProposals(proposals: List<ImprovementProposal>, runId: String): List<ImprovementProposal> =
    proposals.filter { it.source == ImprovementSource.Queen && it.cycleRunId == runId && it.status == ProposalStatus.Proposed }

/**
 * The suggestion she makes now, if the pacing rules allow one: at most one open at Quiet (two at Helpful), fifteen
 * minutes between new ones, five a run, none already made for the same step and action, and none the keeper
 * dismissed in the last fourteen days for the same step and action. Pure: [now] is passed in.
 */
internal fun queenNewProposals(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    candidates: List<QueenCandidate>,
    existing: List<ImprovementProposal>,
    level: QueenLevel,
    now: Long,
): List<ImprovementProposal> {
    val mine = existing.filter { it.source == ImprovementSource.Queen && it.cycleRunId == run.id.value }
    if (mine.count { it.status == ProposalStatus.Proposed } >= QueenPacing.openLimit(level)) return emptyList()
    if (mine.size >= QueenPacing.MAX_PER_RUN) return emptyList()
    val last = mine.maxOfOrNull { it.createdAtEpochMillis }
    if (last != null && now - last < QueenPacing.COOLDOWN_MILLIS) return emptyList()
    val dismissed = existing.filter { it.source == ImprovementSource.Queen && it.status == ProposalStatus.Dismissed }
    val pick = candidates.firstOrNull { candidate ->
        val id = queenSuggestionId(run, candidate.action, candidate.subject)
        mine.none { it.id == id } && dismissed.none {
            it.action == candidate.action.name && it.target == candidate.subject && it.sourceRef == definition.id.value &&
                now - (it.decidedAtEpochMillis ?: 0L) < QueenPacing.DISMISSED_MEMORY_MILLIS
        }
    } ?: return emptyList()
    return listOf(proposalOf(definition, run, pick, now))
}

private fun proposalOf(definition: WorkflowDefinition, run: WorkflowRun, candidate: QueenCandidate, now: Long): ImprovementProposal =
    ImprovementProposal(
        id = queenSuggestionId(run, candidate.action, candidate.subject),
        cycleRunId = run.id.value,
        kind = if (candidate.action == QueenAction.RewordStep) ImprovementKind.HiveStep else ImprovementKind.Other,
        target = candidate.subject,
        summary = queenText("Queen: " + candidate.action.name + " " + candidate.args.joinToString(" "), SUMMARY_CHARS),
        newText = candidate.newText,
        expectedEffect = "",
        undo = "",
        meta = false,
        createdAtEpochMillis = now,
        source = ImprovementSource.Queen,
        sourceRef = definition.id.value,
        action = candidate.action.name,
        voice = candidate.voice.name,
        args = candidate.args,
        outside = candidate.outside,
    )

/**
 * Whether an open suggestion no longer stands: the run is over, or its precondition is gone (the step started, the
 * signal passed, the swarm already rests). Such a row becomes Superseded; it is not asked of the keeper.
 */
internal fun queenIsMoot(
    proposal: ImprovementProposal,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    candidates: List<QueenCandidate>,
): Boolean {
    if (!run.underWay()) return true
    val action = QueenAction.parse(proposal.action) ?: return true
    return when (action) {
        QueenAction.RewordStep -> run.taskRuns[com.hereliesaz.geministrator.domain.TaskDefinitionId(proposal.target)]?.status !in OPEN_STATUSES
        // While frozen or stopped nothing applies, and the row waits; it is not moot.
        else -> run.live() && candidates.none { it.action == action && it.subject == proposal.target }
    }
}

/**
 * What accepting a row needs, checked again at the moment of the tap. True when she must not apply it. With
 * [candidates] (recomputed from the run now), a redo or a rest is also refused once the signal that prompted it is
 * gone (the step recovered, the Nanny's review finished), so a stale tap never rewinds or pauses on old grounds.
 */
internal fun queenAcceptRefused(
    proposal: ImprovementProposal,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    candidates: List<QueenCandidate>? = null,
): Boolean {
    if (!run.live()) return true
    val action = QueenAction.parse(proposal.action) ?: return true
    if (!action.appliesHere) return true
    val task = definition.tasks.firstOrNull { it.id.value == proposal.target }
    val stale = candidates != null && action != QueenAction.RewordStep &&
        candidates.none { it.action == action && it.subject == proposal.target }
    if (stale) return true
    return when (action) {
        QueenAction.RewordStep ->
            task == null || proposal.newText.isBlank() || proposal.outside ||
                run.taskRuns[task.id]?.status !in OPEN_STATUSES || mutatesWithoutApproval(definition, task, roles)
        QueenAction.RedoFromStep -> task == null || run.taskRuns[task.id] == null
        QueenAction.Rest -> run.keeperPause != null
        else -> true
    }
}

/**
 * A step that can change something outside its own output (a repository, a script, a service) and does not sit
 * behind a human approval: its job steers a creature directly, so an agent-written wording is never applied to it live.
 */
internal fun mutatesWithoutApproval(definition: WorkflowDefinition, task: TaskDefinition, roles: Collection<RoleDefinition>): Boolean {
    val rolesById = roles.associateBy { it.id }
    fun behindApproval(t: TaskDefinition): Boolean =
        t.approvalPolicy == ApprovalPolicy.HumanApproval || t.effectiveExecutor(t.roleId?.let { rolesById[it] }) is TaskExecutor.HumanApproval
    val role = task.roleId?.let { rolesById[it] }
    if (!task.effectiveExecutor(role).canMutate(role)) return false
    return !(behindApproval(task) || definition.tasks.any { it.id in task.dependsOn && behindApproval(it) })
}

/** Whether accepting [proposal] asks the keeper first: always when it redoes work; a rewording only when it rests on outside content. */
internal fun queenSuggestionConfirms(action: QueenAction, outside: Boolean): Boolean =
    action == QueenAction.RedoFromStep || (action == QueenAction.RewordStep && outside)

/** The notes for the rows of the queen's still waiting on the keeper, in the order they were made. */
fun queenSuggestionNotes(proposals: List<ImprovementProposal>, run: WorkflowRun): List<QueenNote> =
    openQueenProposals(proposals, run.id.value).sortedBy { it.createdAtEpochMillis }.mapNotNull { proposal ->
        val action = QueenAction.parse(proposal.action) ?: return@mapNotNull null
        val voice = QueenVoice.entries.firstOrNull { it.name == proposal.voice } ?: return@mapNotNull null
        val subject = proposal.target
        val link = when (action) {
            QueenAction.RewordStep, QueenAction.RedoFromStep -> QueenLink.Creature(subject, CreatureTab.Work)
            QueenAction.SwapSource, QueenAction.RaiseBudget -> QueenLink.Creature(subject, CreatureTab.Mind)
            QueenAction.RaiseAllowance -> QueenLink.Limits
            QueenAction.Rest -> null
        }
        QueenNote(
            id = proposal.id,
            voice = voice,
            args = proposal.args.take(4).map { queenSlot(it) },
            link = link,
            quotes = if (action == QueenAction.RewordStep && proposal.newText.isNotBlank()) {
                listOf(QueenQuote(QueenQuoteLabel.QueenWording, queenQuoteText(proposal.newText, WORDING_CHARS)))
            } else {
                emptyList()
            },
            suggestion = QueenSuggestionRef(proposal.id, action, queenSuggestionConfirms(action, proposal.outside)),
            outside = proposal.outside,
        )
    }
