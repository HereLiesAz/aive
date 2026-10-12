package com.hereliesaz.conveyance

/**
 * Inertia, and therefore stakes.
 *
 * Weight is the mass the framework gives an element's motion. A heavy element is slow to start under
 * the finger and slow to stop, so it *feels* consequential and gives the hand a beat in which to
 * change its mind. That beat is the confirmation dialog, replaced by physics, without the
 * interruption or the insult.
 *
 * Weight is **derived, never chosen**. It is not exposed as a styling parameter anywhere in this
 * framework, because a designer picking weight for aesthetic reasons would be lying to a person's
 * hands about how much a thing costs them.
 */
enum class Weight {
    /** Responsive, slight overshoot. Chips, toggles, selections, trivial alterations. */
    Light,

    /** Crisp, settles clean. Cards, sheets, creation, sending. */
    Medium,

    /** High inertia, no overshoot. Places, destruction, anything with a real cost. */
    Heavy;

    internal fun atLeast(other: Weight): Weight = if (ordinal >= other.ordinal) this else other

    internal fun lighter(): Weight = entries[maxOf(0, ordinal - 1)]

    internal fun heavier(): Weight = entries[minOf(entries.lastIndex, ordinal + 1)]

    companion object {

        /**
         * The derivation. Three inputs, all of them facts rather than preferences:
         *
         * - the **consequence class**, which sets the base;
         * - the **scope**, which is how much of the person's world is touched;
         * - whether the act is **reversible**, because a thing you can take back costs less to try.
         *
         * Reversibility can only ever lighten by one step, and never below the base for the class —
         * an undoable deletion is still a deletion, and should not feel like flipping a switch.
         */
        fun of(consequence: Consequence, scope: Scope, reversible: Boolean): Weight {
            val base = baseOf(consequence)
            val scoped = when (scope) {
                Scope.Detail -> base
                Scope.Item -> base
                Scope.Collection -> base.heavier()
                Scope.Everything -> Heavy
            }
            return if (reversible) scoped.lighter().atLeast(base) else scoped
        }

        /**
         * The floor for a consequence class, before scope or reversibility.
         *
         * Nothing may sink below its class base — not a reversal, not familiarity. Deleting is
         * Heavy on the ten-thousandth time as surely as the first, because the cost to the person
         * did not change just because their hand got quicker.
         */
        fun baseOf(consequence: Consequence): Weight = when (consequence) {
            is Consequence.Reveal -> Light
            is Consequence.Alter -> Light
            is Consequence.Create -> Medium
            is Consequence.Send -> Medium
            is Consequence.Enter -> Heavy
            is Consequence.Destroy -> Heavy
        }
    }
}
