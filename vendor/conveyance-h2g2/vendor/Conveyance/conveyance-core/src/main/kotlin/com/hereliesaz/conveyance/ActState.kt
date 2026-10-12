package com.hereliesaz.conveyance

/**
 * The five states every act renders, in the same place, as the same element.
 *
 * This is Law 1 expressed as a type. There is no way to describe a control that handles only one of
 * these, which is what makes the separate spinner, the separate snackbar and the separate error
 * banner unnecessary rather than merely discouraged.
 */
sealed interface ActState {

    /** Available. The world is ready and so is the element. */
    data object Ready : ActState

    /**
     * Unavailable, and it knows why and where.
     *
     * Never renders as reduced opacity. Pressing it escorts the person to [gate]'s address.
     */
    data class Blocked(val gate: Gate) : ActState

    /**
     * Working.
     *
     * The engaged element deforms under load while remaining recognisably itself. [extent] is `null`
     * for work whose end is not known, in which case the deformation is rhythmic rather than
     * proportional.
     */
    data class Yielding(val extent: Float? = null) : ActState {
        init {
            require(extent == null || extent in 0f..1f) { "Extent is a fraction of known work, or null." }
        }
    }

    /** Done. The element carries the result rather than announcing it. */
    data object Settled : ActState

    /** Failed. The element holds the reason and, where it helps, the retry — in place. */
    data class Refused(val refusal: Refusal) : ActState {
        val retryable: Boolean get() = refusal.retryable
    }
}
