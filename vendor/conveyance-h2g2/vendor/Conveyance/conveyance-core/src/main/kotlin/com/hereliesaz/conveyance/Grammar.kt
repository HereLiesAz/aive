package com.hereliesaz.conveyance

/**
 * The nine verbs. Each has exactly one signature. Each signature means only its verb.
 *
 * This is what a person actually learns. Not a list of features — a physics. After a handful of
 * encounters they can predict what an unfamiliar control will do, because they have learned the
 * rules of the world rather than the contents of this screen. That predictive competence is the
 * whole of the "it just clicks" feeling, and it is the only thing in this framework that compounds:
 * the grammar is paid for once and every feature shipped afterwards inherits it for nothing.
 *
 * A grammar with exceptions is not a grammar. A signature borrowed for decoration is a false
 * statement in the language, and does more damage than no motion at all.
 */
enum class Verb {
    /** More of what is already here. */
    Reveal,

    /** The element becomes the place. */
    Enter,

    /** The place becomes the element again, where it was. */
    Return,

    /** Precipitate from the control, travel to the collection. */
    Create,

    /** Collapse in place, leave a recoverable residue. */
    Destroy,

    /** Only the changed property moves. */
    Alter,

    /** Travel toward the destination, diminish into it. */
    Send,

    /** Resist, then escort to the gate. */
    Refuse,

    /** The engaged element deforms under load. */
    Yield;

    companion object {
        /** Every consequence class has exactly one verb. There is no routing decision to make. */
        fun of(consequence: Consequence): Verb = when (consequence) {
            is Consequence.Reveal -> Reveal
            is Consequence.Enter -> Enter
            is Consequence.Create -> Create
            is Consequence.Destroy -> Destroy
            is Consequence.Alter -> Alter
            is Consequence.Send -> Send
        }
    }
}

/** Where a motion begins or ends. Positions in the model, resolved to real bounds by a binding. */
enum class Anchor {
    /** The exact point the person touched. */
    Touchpoint,

    /** The element the person is operating, whatever it is. */
    Engaged,

    /** The element an act or a place came out of. */
    Origin,

    /** The collection a subject belongs to. */
    Collection,

    /** The on-screen representation of where something is going. */
    Destination,

    /** The element where an unmet condition is resolved. */
    Gate,

    /** A whole place. */
    Place,

    /** Exactly where the subject already is. Nothing travels. */
    InPlace,
}

/**
 * One verb's motion, described structurally rather than as timings.
 *
 * Deliberately free of durations, easing curves and distances: those are the binding's business and
 * the platform's. What is normative here is the *relationship* — what moves, from where to where,
 * what becomes what, and what is left behind. That is the part a person learns, and the part that
 * must survive being rendered on a phone, a desktop and a browser without drifting.
 */
data class Signature(
    val verb: Verb,
    val from: Anchor,
    val to: Anchor,
    /** Does something cross space. */
    val translates: Boolean,
    /** Does one identity become another — the continuity morph. */
    val morphs: Boolean,
    /** Does the engaged element itself distort while remaining itself. */
    val deforms: Boolean,
    /** Is a recoverable residue left behind — the Ghost. */
    val leavesResidue: Boolean,
    /** Do neighbours move to make room, or close it. */
    val displaces: Boolean,
) {
    /**
     * The same signature with traversal removed and endpoints kept, for a person who has asked for
     * reduced motion.
     *
     * Motion carries meaning here, so "disable animations" cannot mean "disable meaning". What is
     * removed is duration, never identity: the morph still happens, the residue is still left, the
     * deformation is still shown. If the reduced register loses the grammar, then the grammar was
     * being carried by ornament rather than by structure, and the design was wrong.
     */
    fun reduced(): Signature = copy(translates = false)
}

/** The table. One entry per verb, and it is not extensible by a consumer. */
object Grammar {

    private val table: Map<Verb, Signature> = listOf(
        Signature(Verb.Reveal, Anchor.Touchpoint, Anchor.InPlace,
            translates = false, morphs = false, deforms = false, leavesResidue = false, displaces = true),
        Signature(Verb.Enter, Anchor.Origin, Anchor.Place,
            translates = true, morphs = true, deforms = false, leavesResidue = false, displaces = false),
        Signature(Verb.Return, Anchor.Place, Anchor.Origin,
            translates = true, morphs = true, deforms = false, leavesResidue = false, displaces = false),
        Signature(Verb.Create, Anchor.Origin, Anchor.Collection,
            translates = true, morphs = false, deforms = false, leavesResidue = false, displaces = true),
        Signature(Verb.Destroy, Anchor.InPlace, Anchor.InPlace,
            translates = false, morphs = false, deforms = false, leavesResidue = true, displaces = true),
        Signature(Verb.Alter, Anchor.InPlace, Anchor.InPlace,
            translates = false, morphs = false, deforms = false, leavesResidue = false, displaces = false),
        Signature(Verb.Send, Anchor.Origin, Anchor.Destination,
            translates = true, morphs = false, deforms = false, leavesResidue = false, displaces = false),
        Signature(Verb.Refuse, Anchor.Engaged, Anchor.Gate,
            translates = true, morphs = false, deforms = true, leavesResidue = false, displaces = false),
        Signature(Verb.Yield, Anchor.Engaged, Anchor.Engaged,
            translates = false, morphs = false, deforms = true, leavesResidue = false, displaces = false),
    ).associateBy { it.verb }

    val signatures: Collection<Signature> get() = table.values

    operator fun get(verb: Verb): Signature = table.getValue(verb)

    fun of(consequence: Consequence): Signature = get(Verb.of(consequence))
}
