package com.hereliesaz.conveyance

/**
 * A condition standing between a person and an act — and, crucially, **an address**.
 *
 * This framework has no disabled state. A greyed-out control is a sign nailed to a post: it
 * announces a rule and then abandons you. A gate is a live condition that knows where it can be
 * satisfied, so a blocked act can carry the person there instead of refusing them in place.
 *
 * [livesAt] is not optional and never will be. If you cannot name the element where the blocker is
 * resolved, you have built a dead end, and a dead end always ends up needing a paragraph to escape.
 */
class Gate(
    val id: String,
    val livesAt: ElementId,
    private val condition: () -> Boolean,
) {
    init { require(id.isNotBlank()) { "A gate without an identity cannot be reported or audited." } }

    val satisfied: Boolean get() = condition()

    override fun toString() = "Gate($id -> $livesAt, satisfied=$satisfied)"
}

/**
 * Why an act did not complete.
 *
 * Semantic rather than textual on purpose. The element renders the kind; the accessibility layer
 * turns the kind into a sentence (see the framework spec, §6.2). A free-text error message would put
 * the product's failure vocabulary in the hands of whoever was closest to the keyboard, and would
 * put prose on a surface that has none.
 */
enum class Refusal {
    /** The far side did not answer. Nothing is known to have happened. */
    Unreachable,

    /** The far side answered, and said no. Trying again will not help. */
    Denied,

    /** Someone or something else changed this first. */
    Conflicted,

    /** This was true once and is not any more. */
    Expired,

    /** What was supplied cannot be accepted as it stands. */
    Invalid,

    /** It stopped part-way, through no decision of anyone's. */
    Interrupted;

    /**
     * Whether the same attempt, unchanged, is worth making again.
     *
     * Derived, not declared — a developer marking a [Denied] act as retryable would be inviting a
     * person to fail repeatedly, which is the opposite of compassion.
     */
    val retryable: Boolean
        get() = when (this) {
            Unreachable, Interrupted, Conflicted -> true
            Denied, Expired, Invalid -> false
        }
}

/** The result of performing an act. */
sealed interface Outcome {
    /** It happened. The element settles carrying the result; nothing announces it from elsewhere. */
    data object Done : Outcome

    /** It did not happen, for a reason the element can hold and act on now. */
    data class Failed(val refusal: Refusal) : Outcome
}
