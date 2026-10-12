package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.Consequence
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Reversal
import com.hereliesaz.conveyance.SubjectId
import com.hereliesaz.conveyance.Weight

/**
 * What a destroyed subject leaves behind, in the place it was.
 *
 * The location is the whole point. An undo bar at the bottom of the screen takes the person's
 * attention somewhere else, occupies space it then gives back, and expires while they are still
 * looking at the row that vanished. A residue sits in the slot the subject held, which is exactly
 * where the hand already is and where the eye already went.
 */
@Immutable
data class Residue(
    val subject: SubjectId,
    /** The act that brings it back. Guaranteed to exist: destruction cannot be declared without one. */
    val inverse: Act,
    val at: ElementId,
    val weight: Weight,
) {
    /** How long this stays recoverable. Scales with what the destruction actually cost. */
    val windowMillis: Long get() = Reversal.windowMillis(weight)
}

/**
 * The residues currently recoverable.
 *
 * This is the framework's entire safety mechanism for destruction, and it is the reason no
 * confirmation dialog is needed or offered. Nothing here asks the person whether they are sure;
 * it simply keeps the door open behind them for a while proportional to how far they walked.
 */
@Stable
class Ghosts {

    private val held = mutableStateMapOf<SubjectId, Residue>()

    val all: List<Residue> get() = held.values.toList()

    operator fun get(subject: SubjectId): Residue? = held[subject]

    fun holds(subject: SubjectId): Boolean = held.containsKey(subject)

    /**
     * Record what a destruction left behind.
     *
     * Refuses anything that is not a destruction. A residue left by a send or an alter would be an
     * undo affordance for something that was never destroyed, which teaches the person a rule that
     * is not true.
     */
    fun leave(act: Act, at: ElementId) {
        val consequence = act.consequence
        require(consequence is Consequence.Destroy) {
            "Only a destruction leaves a residue: ${act.id} is a ${act.verb}."
        }
        val inverse = requireNotNull(act.inverse) {
            "A destruction without an inverse cannot have been constructed."
        }
        held[consequence.subject] = Residue(consequence.subject, inverse, at, act.weight)
    }

    /**
     * Take it back.
     *
     * Returns the act that restores the subject, or null if the window has already closed. The
     * caller engages it like any other act, so a recovery moves through the same five states and
     * the same grammar as everything else — being undone is not a special case of being done.
     */
    fun recover(subject: SubjectId): Act? = held.remove(subject)?.inverse

    /** The window closed. The subject is gone for good, and quietly. */
    fun release(subject: SubjectId) {
        held.remove(subject)
    }
}

val LocalGhosts = staticCompositionLocalOf { Ghosts() }
