package com.hereliesaz.conveyance

/**
 * Somewhere a person can be.
 *
 * Rule: a place reached through the interface has an antecedent. [origin] names the element the
 * place grows out of and can return toward, preserving the relationship the person just acted on.
 *
 * Opt-out: [root] names a genuine beginning where there is no visual antecedent. Root is not a
 * continuity switch; it says this journey began here. Products may have several legitimate roots
 * such as startup, deep-link, restored-session, notification, or external-entry destinations.
 */
class Place private constructor(
    val id: PlaceId,
    /** The element this place grows out of, and shrinks back toward on the way out. */
    val origin: ElementId?,
    val subject: SubjectId? = null,
) {
    val isRoot: Boolean get() = origin == null

    override fun toString() =
        if (isRoot) "Place(${id}, root)" else "Place($id, from $origin)"

    companion object {
        /** Rule form: a place reached from an element, which becomes its antecedent. */
        fun from(id: String, origin: ElementId, subject: SubjectId? = null) =
            Place(PlaceId(id), origin, subject)

        /** Opt-out: a genuine entry point with no visual antecedent. */
        fun root(id: String) = Place(PlaceId(id), origin = null)
    }
}
