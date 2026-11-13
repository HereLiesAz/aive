package com.hereliesaz.geministrator.queen

/** Why a decision is the keeper's. A closed list: nothing outside it makes her stop and ask, and nothing on it is hers to decide. */
enum class KeeperReason {
    /** It would raise a cap, an allowance or a role budget. */
    RaisesALimit,

    /** It would spend where the price is unknown and a dollar cap is set. */
    SpendsUnpriced,

    /** It would pass an approval gate, the outside-content gate, a freeze or a spend stop. */
    PassesAGate,

    /** It needs the keeper's recorded yes and there is none. */
    NeedsApproval,

    /** It would change something only the keeper may change. */
    BeyondHerAuthority,

    /** Two creatures disagree and neither the rules nor a valid ruling of hers settled it. */
    Disputed,

    /** There is nothing readable to rule on, or the ruling cannot be read. */
    UnreadableRuling,

    /** It would act on what the keeper pinned. */
    KeeperPinned,
}

/** What only the keeper may change (her authority limits): a role, a pin, a Source, a limit, an approval policy, the loop, the Nanny, an installed package. */
enum class KeeperOwned { Role, Pin, Source, Limit, ApprovalPolicy, Loop, Nanny, Package }

/** What she is about to do, or has run into, as one closed set (see [needsKeeper]). */
sealed interface Situation {
    /** She would raise a cap, an allowance or a role budget. */
    data object RaisesLimit : Situation

    /** She would spend: [price] is what the model's price is known to be, and [dollarCap] whether the keeper set a dollar cap. */
    data class Spends(val price: PriceStatus, val dollarCap: Boolean) : Situation

    /** She would pass an approval gate, the outside-content gate, a freeze or a spend stop. */
    data object PassesGate : Situation

    /** She would adopt something that needs the keeper's recorded yes; [approved] says it is there, for exactly this. */
    data class Adopts(val approved: Boolean) : Situation

    /** She would change something only the keeper may change. */
    data class Changes(val thing: KeeperOwned) : Situation

    /** Two creatures disagree; [settled] when the rules, or a valid ruling of hers that keeps every gate, settled it. */
    data class Disagreement(val settled: Boolean) : Situation

    /** She must rule; [readable] when there is something readable to rule on. */
    data class Ruling(val readable: Boolean) : Situation

    /** She would act on something the keeper pinned. */
    data object ActsOnPin : Situation
}

/**
 * The one place that decides whether she must hand a decision to the keeper (pure, deterministic: no clock, no model, and a
 * model's answer is never an input). Null means it is hers to decide. Every path of hers that stops to ask the keeper (a
 * priced call, an adoption, a suggestion she cannot apply, a dispute, a ruling) asks here, so the list is in one place and
 * cannot drift from its callers.
 */
fun needsKeeper(situation: Situation): KeeperReason? = when (situation) {
    Situation.RaisesLimit -> KeeperReason.RaisesALimit
    is Situation.Spends -> if (situation.dollarCap && situation.price == PriceStatus.Unknown) KeeperReason.SpendsUnpriced else null
    Situation.PassesGate -> KeeperReason.PassesAGate
    is Situation.Adopts -> if (situation.approved) null else KeeperReason.NeedsApproval
    is Situation.Changes -> if (situation.thing == KeeperOwned.Pin) KeeperReason.KeeperPinned else KeeperReason.BeyondHerAuthority
    is Situation.Disagreement -> if (situation.settled) null else KeeperReason.Disputed
    is Situation.Ruling -> if (situation.readable) null else KeeperReason.UnreadableRuling
    Situation.ActsOnPin -> KeeperReason.KeeperPinned
}

/**
 * What accepting a suggestion of hers would be, when it is the keeper's to do: she only leads to where the keeper changes it
 * (a Source for a creature, a budget, an allowance). Null for what she applies herself or only keeps for next time.
 */
internal fun QueenAction.keeperSituation(): Situation? = when (this) {
    QueenAction.SwapSource -> Situation.Changes(KeeperOwned.Source)
    QueenAction.RaiseBudget, QueenAction.RaiseAllowance -> Situation.RaisesLimit
    QueenAction.RewordStep, QueenAction.RedoFromStep, QueenAction.Rest,
    QueenAction.AddCheck, QueenAction.SplitStep, QueenAction.ReorderSteps, QueenAction.AddApproval,
    -> null
    // She only leads to the Roster; adopting what comes of it is never hers without the keeper's approval.
    QueenAction.TrainSpecialist -> Situation.Adopts(approved = false)
}
