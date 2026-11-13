package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab

/** How much the queen tells the keeper in Messages. Neither level sends anything to a model. */
enum class QueenLevel {
    /** What needs the keeper, why a swarm stopped, what the Nanny and the queen noticed, and one note at the end. */
    Quiet,

    /** Quiet, plus one living line on how far the swarm has got and a digest of what waits. */
    Helpful,
    ;

    companion object {
        /** The stored level, Quiet when nothing valid is stored. */
        fun parse(raw: String?): QueenLevel = entries.firstOrNull { it.name == raw } ?: Quiet
    }
}

/** Where a relayed note comes from. */
enum class QueenRelay { Nanny, Allowance, Waits, Watch, Missing, Dispute }

/**
 * Every sentence the queen can say: a closed set, each a string resource with bounded slots
 * ([QueenNote.args]). A voice never carries text from the run.
 */
enum class QueenVoice(val relay: QueenRelay?) {
    StatusWorking(null),
    StatusWorkingClear(null),
    StatusIdle(null),
    StatusIdleClear(null),
    NannyChecking(QueenRelay.Nanny),
    FinishedAll(null),
    FinishedAllCost(null),
    FinishedFailed(null),
    FinishedFailedNoStep(null),
    FinishedStopped(null),
    WaitsOne(QueenRelay.Waits),
    WaitsMany(QueenRelay.Waits),
    WaitEscalation(QueenRelay.Waits),
    WaitBudget(QueenRelay.Waits),
    WatchRepeatedFailure(QueenRelay.Watch),
    WatchBudget(QueenRelay.Watch),
    WatchFailedOver(QueenRelay.Watch),
    WatchSlow(QueenRelay.Watch),
    WatchTimedOut(QueenRelay.Watch),
    AllowanceStopRun(QueenRelay.Allowance),
    AllowanceStopHour(QueenRelay.Allowance),
    AllowanceStopUnpriced(QueenRelay.Allowance),
    NannyReport(QueenRelay.Nanny),
    NannyRevise(QueenRelay.Nanny),
    NannyRejected(QueenRelay.Nanny),
    NannyFroze(QueenRelay.Nanny),
    NannyMoreFindings(QueenRelay.Nanny),
    QueenReading(QueenRelay.Nanny),
    QueenApproves(QueenRelay.Nanny),
    QueenRejects(QueenRelay.Nanny),
    QueenCouldNotRead(QueenRelay.Nanny),
    NannyYouDecide(QueenRelay.Nanny),
    PracticeRunning(QueenRelay.Nanny),
    PracticeDone(QueenRelay.Nanny),
    PracticeEnded(QueenRelay.Nanny),
    ResearchConsulted(null),
    ResearchSkipped(null),
    MissingNoSource(QueenRelay.Missing),
    MissingNoPlanner(QueenRelay.Missing),
    MissingNoReviewer(QueenRelay.Missing),
    MissingPairBroken(QueenRelay.Missing),
    MissingSameSource(QueenRelay.Missing),
    DisputeNeedsYou(QueenRelay.Dispute),
    DisputeNeedsYouQueenAway(QueenRelay.Dispute),
    DisputeResearching(QueenRelay.Dispute),
    DisputeTesting(QueenRelay.Dispute),
    DisputeStillUnsure(QueenRelay.Dispute),
    DisputeCannotInvestigate(QueenRelay.Dispute),
    DisputeMovedSource(QueenRelay.Dispute),

    // --- Mid-run suggestions (queen/QueenSuggestions.kt). Kept together, after every other voice. ---
    SuggestRedoStep(QueenRelay.Watch),
    SuggestSwapSource(QueenRelay.Watch),
    SuggestRaiseBudget(QueenRelay.Watch),
    SuggestRaiseAllowance(QueenRelay.Allowance),
    SuggestRestNanny(QueenRelay.Nanny),
    SuggestRewordStep(QueenRelay.Watch),

    // --- The Nanny's findings handed to the queen (queen/NannyFindings.kt). Kept apart, after every other voice. ---
    SuggestNannyFinding(QueenRelay.Nanny),

    // --- The training swarm (queen/TrainingLoop.kt). Kept apart, after every other voice. ---
    TrainingOffer(QueenRelay.Watch),
    TrainingStopped(QueenRelay.Watch),
    TrainingNeedsYou(QueenRelay.Watch),
    // --- Signals added later (queen/QueenSignals.kt) and her place on the creature sheet. Kept apart, after every other voice. ---
    WatchStalled(QueenRelay.Watch),
    WatchRepeatedBlock(QueenRelay.Watch),
    SuggestAddCheck(QueenRelay.Watch),
    SuggestSplitStep(QueenRelay.Watch),
    SuggestReorderSteps(QueenRelay.Watch),
    SuggestAddApproval(QueenRelay.Watch),
    TrainingAdopted(QueenRelay.Watch),

    // --- The reviewer not asked, and her finish guidance (queen/PlanningRecord.kt, queen/Guidance.kt). Kept apart, after every other voice. ---
    MissingReviewerNoPrice(QueenRelay.Missing),
    MissingReviewerOverCap(QueenRelay.Missing),
    MissingReviewerNoRoom(QueenRelay.Missing),
    MissingReviewerNoSecondSource(QueenRelay.Missing),
    FinishedNotReached(null),

    // --- Blockers: what a hive still needs created or obtained (queen/Blockers.kt). Kept apart, after every other voice. ---
    MissingBlocker(QueenRelay.Missing),
    FinishedBlockersOpen(null),

    // --- A specialist worth training (queen/TrainingSuggestion.kt). Kept apart, after every other voice. ---
    SuggestTrainSpecialist(QueenRelay.Watch),
    // --- Blockers found or made in the middle of a run (queen/Blockers.kt). Kept apart, after every other voice. ---
    BlockerAddedQueen(QueenRelay.Missing),
    BlockerAddedNanny(QueenRelay.Nanny),
    BlockerMade(QueenRelay.Missing),
}

/** Where a note leads: an existing place, never a new one. */
sealed interface QueenLink {
    /** A creature's panel on a tab (the existing sheet). */
    data class Creature(val taskId: String, val tab: CreatureTab) : QueenLink

    /** The spend caps in the Keeper's room. */
    data object Limits : QueenLink

    /** The Story (the run's timeline). */
    data object Story : QueenLink

    /** Another Messages row, by key. */
    data class Message(val key: String) : QueenLink

    /** Another run, such as a practice run. */
    data class Run(val runId: String) : QueenLink

    /** Connections in the Keeper's room: where a Source is connected. */
    data object Connections : QueenLink

    /** The Roster: where a role is switched on or edited. */
    data object Roster : QueenLink
}

/** What a quoted line is, so the keeper can see whose words they are. */
enum class QueenQuoteLabel { NannyFinding, QueenReason, PracticeSolution, QueenApproach, Objection, StillMissing, QueenWording, NowMade }

/** Text taken from the run, shown on its own labeled line: escaped, capped, never part of a sentence. */
data class QueenQuote(val label: QueenQuoteLabel, val text: String)

/**
 * One thing the queen says in Messages. Derived from the run on every publish: nothing is stored.
 * A note with [attachedTo] is a sentence on that existing row (the wait, the freeze, the stop);
 * without it, the note is a row of its own. Notes never count toward the badge.
 */
data class QueenNote(
    /** Stable: the same state gives the same id. */
    val id: String,
    val voice: QueenVoice,
    /** Bounded, control-stripped slots: counts, step numbers, names. */
    val args: List<String> = emptyList(),
    val link: QueenLink? = null,
    val quotes: List<QueenQuote> = emptyList(),
    val attachedTo: String? = null,
    /** A second sentence on the same note. */
    val extra: QueenVoice? = null,
    /** Set for a suggestion: the ledger row the keeper decides. Only a suggestion counts toward the badge. */
    val suggestion: QueenSuggestionRef? = null,
    /** A suggestion resting on content from outside the keeper's own: labelled, and it asks before it applies. */
    val outside: Boolean = false,
) {
    val relay: QueenRelay? get() = voice.relay
    val countsToBadge: Boolean get() = suggestion != null
}
