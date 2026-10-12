package com.hereliesaz.conveyance

/**
 * Identity is what makes conveyance teachable.
 *
 * A person learns "this thing does that" only if *this thing* stays the same thing across time and
 * across places. Every identity here is stable for the lifetime of the concept it names, not for the
 * lifetime of a composition, a screen, or a scroll position.
 */

/** A thing a person can do. Stable across every place the act is offered. */
data class ActId(val value: String) {
    init { require(value.isNotBlank()) { "An act without an identity cannot be learned." } }
    override fun toString() = value
}

/** Somewhere a person can be. */
data class PlaceId(val value: String) {
    init { require(value.isNotBlank()) { "A place without an identity cannot be returned to." } }
    override fun toString() = value
}

/** A thing in the product a person cares about: a document, a track, a friend. */
data class SubjectId(val value: String) {
    init { require(value.isNotBlank()) { "A subject without an identity cannot be followed." } }
    override fun toString() = value
}

/**
 * A rendered element, addressable by the model.
 *
 * Consequences name one of these, gates live at one of these, and places grow out of one of these.
 * That is what lets motion be derived rather than configured: the framework always knows both
 * endpoints.
 */
data class ElementId(val value: String) {
    init { require(value.isNotBlank()) { "An element that cannot be named cannot be travelled to." } }
    override fun toString() = value
}
