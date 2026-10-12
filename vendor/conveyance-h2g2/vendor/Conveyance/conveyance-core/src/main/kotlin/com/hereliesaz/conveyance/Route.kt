package com.hereliesaz.conveyance

/**
 * What to do next, on the way to what a person actually reached for.
 *
 * Not an instruction. The framework does not have a sentence for this and never will; a [Step] is
 * consumed by the Escort, which carries the person to the thing rather than describing it.
 */
sealed interface Step {

    /** Nothing is in the way. What was reached for can simply happen. */
    data object Ready : Step

    /**
     * Do this one first, and [opens] is the condition it satisfies.
     *
     * This is the answer to a question a disabled control cannot answer and a tooltip answers
     * badly: not *why* you cannot do that, but *what to do instead, right now*.
     */
    data class Do(val act: Act, val opens: Gate) : Step

    /**
     * Nothing anywhere can open [gate], and this is a genuine dead end.
     *
     * Reported rather than hidden. A person is still carried to the gate's address, because that is
     * the best available answer, but the product has a hole in it and the framework knows it.
     */
    data class Stranded(val gate: Gate) : Step
}

/**
 * The graph a set of acts already is.
 *
 * Nothing here is authored. An act names the conditions it needs, and each of those names the
 * element where it is resolved; an element that offers an act is the act that resolves it. Those two
 * facts, both already required for other reasons, are the edges — so the flowchart of a product's
 * prerequisites exists the moment the acts do, and cannot drift out of step with them the way a
 * hand-written one does.
 *
 * What it buys is the difference between an escort that dumps a person at the first thing that is
 * missing and one that carries them to the first thing they can actually *do*. When the missing
 * thing is itself unavailable, the first is a dead end wearing a helpful expression.
 */
object Route {

    /**
     * The nearest act that gets [goal] moving, searched breadth-first so it is the nearest one.
     *
     * [offering] answers "what act lives at this address", which is the registry's job — the search
     * itself has no idea what a screen is, and is therefore testable without one.
     */
    fun from(goal: Act, offering: (ElementId) -> Act?): Step {
        var frontier = goal.requires.filterNot { it.satisfied }
        if (frontier.isEmpty()) return Step.Ready

        val seen = mutableSetOf<String>()
        var stranded: Gate? = null

        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Gate>()
            frontier.forEach { gate ->
                if (!seen.add(gate.id)) return@forEach
                val opener = offering(gate.livesAt)
                if (opener == null) {
                    // Remember the first dead end but keep looking: a later gate may still have a
                    // way through, and carrying someone to a hole is the last resort, not the first.
                    if (stranded == null) stranded = gate
                    return@forEach
                }
                val unmet = opener.requires.filterNot { it.satisfied }
                if (unmet.isEmpty()) return Step.Do(opener, gate)
                next += unmet
            }
            frontier = next
        }

        return Step.Stranded(stranded ?: goal.requires.first { !it.satisfied })
    }
}
