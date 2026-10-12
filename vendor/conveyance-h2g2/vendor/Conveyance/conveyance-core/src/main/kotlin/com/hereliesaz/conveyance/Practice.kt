package com.hereliesaz.conveyance

/**
 * How much ceremony an act still deserves.
 *
 * Conveyance optimises for the person who has never seen this before. The person who has seen it
 * four thousand times wants speed, and will experience a Tell as a stutter and a heavy weight as
 * molasses. So ceremony attenuates with familiarity — the way a skilled musician's motions get
 * smaller — while the grammar itself never changes. A Send is always a Send.
 */
enum class Ceremony {
    /** Unpractised. Full articulation, and the Tell is owed. */
    Full,

    /** Familiar. Full grammar, shortened articulation. */
    Practised,

    /** Fluent. The motion is present but minimal; it is confirmation, not instruction. */
    Fluent,
}

/**
 * Per-element practice counts, and the decisions that follow from them.
 *
 * There is no "expert mode" toggle, because a toggle is a preference someone has to be told about,
 * and telling someone about a preference is instruction. Elements simply track their own use.
 *
 * Not thread-safe by design: practice is recorded from wherever interaction is handled, which on
 * every binding this framework targets is a single thread.
 */
class Practice {

    private val counts = mutableMapOf<ActId, Int>()

    /** Practised thresholds. Deliberately low: fluency arrives faster than designers expect. */
    private companion object {
        const val PRACTISED_AT = 3
        const val FLUENT_AT = 20
    }

    fun count(act: ActId): Int = counts[act] ?: 0

    fun record(act: ActId) {
        counts[act] = count(act) + 1
    }

    fun ceremonyFor(act: ActId): Ceremony = when {
        count(act) >= FLUENT_AT -> Ceremony.Fluent
        count(act) >= PRACTISED_AT -> Ceremony.Practised
        else -> Ceremony.Full
    }

    /**
     * Whether this act still owes its Tell — the once-only half-rep an unpractised element performs
     * of its own interaction.
     *
     * A Tell is owed exactly once, before the first use, and never again. An element that keeps
     * hinting after a person has done the thing is nagging, and nagging is a construction sign that
     * has learned to blink.
     */
    fun owesTell(act: ActId): Boolean = count(act) == 0

    /**
     * The weight an act should carry for *this* person, right now.
     *
     * Familiarity may lighten an act by one step and no further, and never below its class base.
     * That floor is the important half: deleting stays Heavy on the ten-thousandth time, because
     * the cost to the person did not change just because their hand got quicker. Practice earns
     * speed, never a reduction in stakes.
     */
    fun weightFor(act: Act): Weight {
        val declared = act.weight
        if (ceremonyFor(act.id) != Ceremony.Fluent) return declared
        return declared.lighter().atLeast(Weight.baseOf(act.consequence))
    }
}
