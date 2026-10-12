package com.hereliesaz.conveyance

/**
 * How long a destroyed subject stays recoverable, and why that is not a constant.
 *
 * The Ghost is this framework's only safety mechanism for destruction: there is no confirmation
 * dialog and there cannot be one, because [Act.destroy] takes a non-null inverse and nothing else is
 * offered. So the window is doing the work a modal would otherwise do, and it has to be sized like
 * it means it.
 *
 * The window scales with [Weight], which is to say with what the act actually cost the person.
 * Dismissing a notification and deleting a year of work should not get the same few seconds. This is
 * the same principle as weight itself — the interface's behaviour tracks the stakes rather than the
 * convenience of whoever built it.
 */
object Reversal {

    /**
     * The recovery window, in milliseconds.
     *
     * Deliberately generous at the top end. A person who has just destroyed something significant
     * often does not realise it immediately, and the cost of holding a residue a little longer is
     * a small piece of screen space; the cost of releasing it too early is their work.
     */
    fun windowMillis(weight: Weight): Long = when (weight) {
        Weight.Light -> 4_000
        Weight.Medium -> 10_000
        Weight.Heavy -> 30_000
    }

    /** The window for a specific act, which knows its own weight. */
    fun windowMillis(act: Act): Long {
        require(act.consequence is Consequence.Destroy) {
            "Only a destruction leaves a residue: ${act.id} is a ${act.verb}."
        }
        return windowMillis(act.weight)
    }
}
