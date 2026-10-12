package com.hereliesaz.conveyance

/**
 * How much expressive attention an [Act] is allowed to command.
 *
 * This is a semantic token, not an appearance. Core Conveyance never decides that Heroic means
 * "large", "bright", "bouncy", or any other particular treatment. A binding or product theme maps
 * the resolved token onto shape, motion, type, space, colour, haptics, sound, surrounding response,
 * or any other channels it owns.
 *
 * Heroic is screen-relative. A screen may resolve at most one Heroic act. If two or more visible
 * acts claim Heroic, the entire emphasis ladder drops one rung for that screen: Heroic becomes
 * Primary, Primary becomes Secondary, Secondary becomes Tertiary, Tertiary becomes Supporting,
 * and Supporting remains Supporting. Competing hero moments therefore do not create two heroes;
 * they cost the whole screen one level of expressive emphasis.
 */
enum class ActEmphasis {
    /** The screen's single engineered hero moment: maximum expressive license. */
    Heroic,

    /** A leading act once the screen's hierarchy is resolved. */
    Primary,

    /** Important, but below the leading act. */
    Secondary,

    /** Useful and visible without competing for leadership. */
    Tertiary,

    /** The floor: present and useful without asking to dominate the composition. */
    Supporting;

    /** Drop this token exactly one rung, stopping at [Supporting]. */
    fun demoted(): ActEmphasis = when (this) {
        Heroic -> Primary
        Primary -> Secondary
        Secondary -> Tertiary
        Tertiary -> Supporting
        Supporting -> Supporting
    }

    /**
     * Resolve this declared token against the number of Heroic claims on the current screen.
     *
     * Zero or one Heroic claim leaves the ladder untouched. Two or more demote every act once.
     */
    fun resolve(heroicClaims: Int): ActEmphasis = if (heroicClaims >= 2) demoted() else this
}
