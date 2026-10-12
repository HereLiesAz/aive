package com.hereliesaz.conveyance

/**
 * Something a person can do.
 *
 * An act is not a button. A button is an appearance with a callback attached, and the gap between
 * that appearance and what actually happens is exactly where instruction has to be inserted to patch
 * things up — the tooltip, the "Are you sure?", the toast reporting on something that happened
 * somewhere off-screen. An act closes that gap by carrying its own consequence, its own conditions
 * and, where reality permits it, its own reversal.
 *
 * [emphasis] is deliberately semantic rather than visual. The act says how much expressive attention
 * it deserves; the binding and product theme decide what Heroic, Primary, Secondary, and Supporting
 * look and feel like in that product.
 *
 * There is no public constructor. Acts are made through verb factories, each of which takes exactly
 * what its verb needs and nothing else. A strong rule is paired with a named semantic opt-out when
 * that rule genuinely does not describe reality; the opt-out says what the act is instead of merely
 * suppressing a check.
 */
class Act private constructor(
    val id: ActId,
    val consequence: Consequence,
    val scope: Scope,
    val requires: List<Gate>,
    /** The act that undoes this one, when one exists in the world being modelled. */
    val inverse: Act?,
    /** Semantic expressive importance. The binding decides how this token is rendered. */
    val emphasis: ActEmphasis,
    private val perform: suspend () -> Outcome,
) {
    /** Which verb this act speaks. Derived; there is no routing decision to make. */
    val verb: Verb get() = Verb.of(consequence)

    /** The consequence-motion grammar this act will produce. Derived. */
    val signature: Signature get() = Grammar.of(consequence)

    /**
     * Whether this can be taken back.
     *
     * Entering is always reversible and needs no declared inverse, because the framework renders
     * Return itself. A reversible destruction declares an inverse through [destroy]. A destruction
     * that truly cannot be reversed must opt out explicitly through [destroyIrreversibly].
     */
    val reversible: Boolean get() = inverse != null || consequence is Consequence.Enter

    /**
     * The inertia this act's motion carries, and therefore how costly it feels in the hand.
     * Derived from consequence, scope and reversibility — never chosen as decoration.
     */
    val weight: Weight get() = Weight.of(consequence, scope, reversible)

    /** The first unmet condition, or null when the world is ready. */
    fun blockingGate(): Gate? = requires.firstOrNull { !it.satisfied }

    /** [ActState.Ready], or [ActState.Blocked] naming the gate and therefore its address. */
    fun state(): ActState = blockingGate()?.let(ActState::Blocked) ?: ActState.Ready

    /**
     * Engage the act, reporting each state to [emit] as the same element passes through them.
     *
     * A blocked act does **not** fail and does not do nothing: it returns [ActState.Blocked], which
     * is the binding's cue to escort the person to the gate's address.
     *
     * @return the terminal state — Blocked, Settled, or Refused.
     */
    suspend fun engage(emit: (ActState) -> Unit = {}): ActState {
        blockingGate()?.let { gate ->
            val blocked = ActState.Blocked(gate)
            emit(blocked)
            return blocked
        }
        emit(ActState.Yielding())
        val terminal = when (val outcome = runCatching { perform() }.getOrElse {
            Outcome.Failed(Refusal.Interrupted)
        }) {
            Outcome.Done -> ActState.Settled
            is Outcome.Failed -> ActState.Refused(outcome.refusal)
        }
        emit(terminal)
        return terminal
    }

    override fun toString() = "Act($id, $verb, $weight, $emphasis)"

    companion object {

        /** More of what is already here becomes visible. */
        fun reveal(
            id: String,
            target: ElementId,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Reveal(target), Scope.Detail, requires,
            inverse = null, emphasis = emphasis, perform = perform,
        )

        /**
         * The person goes somewhere, and the place already knows the element that becomes it.
         *
         * Rule: entered places have a visual antecedent.
         * Opt-out: [Place.root] names a genuine entry point where no antecedent exists; root places
         * are beginnings, not destinations for this factory.
         */
        fun enter(
            id: String,
            place: Place,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Enter(place), Scope.Item, requires,
            inverse = null, emphasis = emphasis, perform = perform,
        )

        /** A new subject exists, in a named collection, having come out of this control. */
        fun create(
            id: String,
            subject: SubjectId,
            into: ElementId,
            scope: Scope = Scope.Item,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Create(subject, into), scope, requires,
            inverse = null, emphasis = emphasis, perform = perform,
        )

        /**
         * Rule: destruction is reversible whenever the world provides an inverse.
         *
         * [inverse] is deliberately required here. The framework should force the designer to find
         * the respectful reversible construction instead of reaching for confirmation out of habit.
         *
         * Opt-out: when destruction is **actually irreversible**, use [destroyIrreversibly]. That is
         * not `inverse = null`; it is a separate declaration saying the stronger rule does not match
         * reality in this case.
         */
        fun destroy(
            id: String,
            subject: SubjectId,
            target: ElementId,
            inverse: Act,
            scope: Scope = Scope.Item,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Destroy(subject, target), scope, requires,
            inverse = inverse, emphasis = emphasis, perform = perform,
        )

        /**
         * Explicit opt-out from reversible destruction.
         *
         * Use only when the world being modelled offers no meaningful inverse: a remote irreversible
         * side effect, a legal submission, a physical action, or another consequence that cannot be
         * restored by the product. The explicit factory keeps irreversibility visible to weight,
         * audits and bindings instead of weakening [destroy] for every ordinary deletion.
         */
        fun destroyIrreversibly(
            id: String,
            subject: SubjectId,
            target: ElementId,
            scope: Scope = Scope.Item,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Destroy(subject, target), scope, requires,
            inverse = null, emphasis = emphasis, perform = perform,
        )

        /** A subject changes in place. Only the changed property moves. */
        fun alter(
            id: String,
            subject: SubjectId,
            property: String,
            target: ElementId,
            scope: Scope = Scope.Detail,
            requires: List<Gate> = emptyList(),
            inverse: Act? = null,
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Alter(subject, property, target), scope, requires,
            inverse = inverse, emphasis = emphasis, perform = perform,
        )

        /** A subject leaves the person's control, toward something they can see. */
        fun send(
            id: String,
            subject: SubjectId,
            to: ElementId,
            scope: Scope = Scope.Item,
            requires: List<Gate> = emptyList(),
            emphasis: ActEmphasis = ActEmphasis.Supporting,
            inverse: Act? = null,
            perform: suspend () -> Outcome = { Outcome.Done },
        ) = Act(
            ActId(id), Consequence.Send(subject, to), scope, requires,
            inverse = inverse, emphasis = emphasis, perform = perform,
        )
    }
}
