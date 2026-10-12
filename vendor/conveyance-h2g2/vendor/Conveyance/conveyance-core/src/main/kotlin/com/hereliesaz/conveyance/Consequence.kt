package com.hereliesaz.conveyance

/**
 * What visibly changes when an act completes, and **where**.
 *
 * This is the field that does the most work in the whole framework. Requiring it is what makes
 * continuity derivable instead of configurable, and what makes a hidden consequence — the root cause
 * of every toast ever written — unrepresentable. If you cannot say what changes and where, you do
 * not yet have a design.
 *
 * The consequence classes form the reference grammar. Choosing one tells the framework what changed;
 * bindings derive motion and weight from that meaning rather than asking the developer to style the
 * result manually.
 */
sealed interface Consequence {

    /** The element that visibly changes. Motion resolves between the act's element and this one. */
    val target: ElementId

    /** More of what is already here becomes visible. The person does not go anywhere. */
    data class Reveal(override val target: ElementId) : Consequence

    /**
     * The person goes somewhere. The touched element becomes that place.
     *
     * Rule: entered places have an antecedent.
     * Opt-out: [Place.root] names a genuine entry point and therefore is not an Enter destination.
     */
    data class Enter(val place: Place) : Consequence {
        init {
            require(!place.isRoot) { "A root place is not entered; it is where a person begins." }
        }

        override val target: ElementId get() = requireNotNull(place.origin)
    }

    /** A new subject exists, in a named collection. It comes out of the control that made it. */
    data class Create(val subject: SubjectId, val into: ElementId) : Consequence {
        override val target: ElementId get() = into
    }

    /**
     * A subject ceases.
     *
     * Rule: prefer [Act.destroy], which requires an inverse.
     * Opt-out: [Act.destroyIrreversibly] explicitly declares that reality provides no inverse.
     */
    data class Destroy(val subject: SubjectId, override val target: ElementId) : Consequence

    /** A subject changes in place. Only the changed property moves. */
    data class Alter(
        val subject: SubjectId,
        val property: String,
        override val target: ElementId,
    ) : Consequence

    /** A subject leaves the person's control, toward something visible on screen. */
    data class Send(val subject: SubjectId, val to: ElementId) : Consequence {
        override val target: ElementId get() = to
    }
}

/**
 * How much of the person's world an act touches.
 *
 * This is a fact about the domain, not a style choice, which is why it is safe to let a developer
 * state it: it feeds [Weight], and weight is what a person's hand reads as stakes. Getting it wrong
 * lies to their hands, so state it honestly rather than conveniently.
 */
enum class Scope {
    /** One property of one subject. Renaming. Toggling. Adjusting. */
    Detail,

    /** One whole subject. */
    Item,

    /** Many subjects at once, or the collection itself. */
    Collection,

    /** The person's account, their data as a whole, or the product's configuration. */
    Everything,
}
