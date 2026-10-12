package com.hereliesaz.conveyance

/**
 * How prominent an element is in the current composition.
 *
 * Rank is descriptive, not a dress code. A surface may have several prominent things, no obvious
 * primary, or a deliberately chaotic hierarchy if that better serves the product. This is separate
 * from [ActEmphasis]: element rank describes composition; act emphasis says how much expressive
 * attention a consequence is allowed to command.
 */
enum class Rank { Primary, Secondary, Tertiary }

/**
 * User-facing chrome text.
 *
 * Conveyance trusts the person using the interface. Chrome should name what matters, not narrate
 * obvious mechanics, issue little commands, or compensate in prose for an affordance that ought to
 * convey itself. This is deliberately different from policing tone or style: labels may be strange,
 * funny, conversational, terse, or verbose.
 */
data class Label(val text: String) {
    init {
        require(text.isNotBlank()) { "A label cannot be blank." }
        val words = text.trim()
            .split(Regex("[\\s\\p{Zs}]+"))
            .filter { it.isNotBlank() }
            .map { it.trim(',', '.', '!', '?', ':', ';').lowercase() }

        val needless = words.filter { it in NEEDLESS_CHROME }
        require(needless.isEmpty()) {
            "\"$text\" narrates the interface with ${needless.distinct().joinToString()}. " +
                "Trust the user: name the thing or act, and let the interface convey how it works."
        }
    }

    override fun toString() = text

    private companion object {
        val NEEDLESS_CHROME = setOf(
            "tap", "click", "press", "swipe", "drag", "select", "choose",
            "please", "simply", "just",
        )
    }
}

/** An element as declared to the Conscience. */
data class DeclaredElement(
    val id: ElementId,
    val employment: Employment,
    val rank: Rank = Rank.Tertiary,
    val chrome: List<Label> = emptyList(),
    val channels: Set<Channel> = emptySet(),
)

/** One surface's worth of claims. */
data class Surface(
    val name: String,
    val elements: List<DeclaredElement> = emptyList(),
    val gates: List<Gate> = emptyList(),
    val places: List<Place> = emptyList(),
)

/** Everything needed for audits that are meaningful across a whole product. */
data class Product(
    val name: String,
    val surfaces: List<Surface> = emptyList(),
)
