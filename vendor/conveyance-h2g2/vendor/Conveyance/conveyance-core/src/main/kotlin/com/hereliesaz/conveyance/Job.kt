package com.hereliesaz.conveyance

/**
 * What an element is for.
 *
 * Resourceful minimalism is deliberately generative here: a working element must do at least four
 * real jobs. The point is not tidiness or austerity. The constraint forces the designer to rethink
 * one-purpose chrome, combine responsibilities, and invent richer elements that teach more of the
 * interface through use.
 *
 * Some jobs are declared only when the framework cannot see them. Others are derived from the live
 * semantic graph. In particular, an Element targeted by an Act is genuinely doing the job [Receive]
 * and should get credit for it without making the developer repeat that fact.
 */
enum class Job {
    /** Offers an act. */
    Invite,

    /** Shows current state. */
    Report,

    /** Tells you where you are. */
    Locate,

    /** Distinguishes one subject from another. */
    Identify,

    /** Binds things together. */
    Group,

    /** Marks a boundary. */
    Separate,

    /** Shows work happening. */
    Progress,

    /** Shows work completed. */
    Confirm,

    /** Shows risk. */
    Warn,

    /** Moves you. */
    Navigate,

    /** Stops what it started. */
    Interrupt,

    /** Receives the visible consequence of an Act. Derived from the Act graph when observable. */
    Receive,
}

/** Why an element is on screen at all. */
sealed interface Employment {

    /**
     * Doing real work. Four distinct jobs is the minimum.
     *
     * This is a creative constraint, not an organizational quota. If an element cannot honestly do
     * four jobs, the intended response is to reimagine the element: merge it, transform it, let it
     * carry state or identity, make it the place a consequence lands, or otherwise give it a richer
     * role in the interface.
     */
    class Working(val jobs: Set<Job>) : Employment {
        constructor(vararg jobs: Job) : this(jobs.toSet())

        init {
            require(jobs.size >= 4) {
                "A working element needs at least four distinct jobs. Reimagine it rather than padding the declaration: $jobs"
            }
        }

        override fun toString() = "Working(${jobs.joinToString(", ")})"
    }

    /**
     * Deliberately not operational: ground, texture, breathing room, ornament, atmosphere, or another
     * intentionally non-working part of the composition.
     *
     * Ambient is not a loophole for weak controls; it means the thing is not pretending to be a
     * working element in the first place.
     */
    data object Ambient : Employment
}
