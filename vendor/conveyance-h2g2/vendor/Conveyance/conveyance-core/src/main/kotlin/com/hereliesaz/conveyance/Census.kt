package com.hereliesaz.conveyance

/**
 * How much is on screen, against how much can be done there.
 *
 * This is Twain's fourth rule made arithmetic — *the personages shall exhibit a sufficient excuse
 * for being there* — and it is the one measurement in the framework that can be taken continuously,
 * without a person, while an application is simply being used.
 *
 * **The naive version of this metric is wrong and worth naming.** Dividing elements by acts punishes
 * any screen holding content, because content is not an affordance: a gallery of fifty photographs
 * is not fifty times worse than a gallery of one. What does not scale with data, and therefore what
 * is worth counting, is **chrome** — the elements that are the product talking rather than the
 * product's subject matter. Content earns its place by being what the person came for. Chrome has to
 * argue for itself.
 *
 * So the number that matters is [chromePerAct]. Two other counts matter more, because they are not
 * matters of taste at all but straightforward defects: an act nobody can reach, and an invitation
 * that leads nowhere.
 */
data class Census(
    /** Acts currently offered on this surface. */
    val acts: Int,
    /** Acts whose element is composed and actually visible. */
    val reachable: Int,
    /** Addressed elements currently composed. */
    val elements: Int,
    /** Elements that offer an act. */
    val inviting: Int,
    /** Elements carrying the subject matter: identifying, reporting, showing a value. */
    val content: Int,
    /** Elements deliberately doing nothing, and budgeted for it. */
    val ambient: Int,
    /** Offered, but with nothing on screen to reach it by. */
    val unreachable: List<ActId> = emptyList(),
    /** On screen and inviting, but attached to no act. A promise with nothing behind it. */
    val mute: List<ElementId> = emptyList(),
    /**
     * One address, answered for by more than one composable at once.
     *
     * A deliberate, load-bearing case while a place transition is in flight -- a detail place
     * legitimately shares its subject's address with the thumbnail it grew out of, so the binding
     * has something to resolve underneath it. Outside of that, it is almost always the other thing:
     * two unrelated elements that happen to have picked the same [ElementId], one of them silently
     * shadowing the other in every count and in the audit itself. Reported rather than picked apart,
     * because telling the two cases apart is a judgement this measurement cannot make on its own.
     */
    val contested: List<ElementId> = emptyList(),
) {
    /**
     * Elements that are neither an invitation nor the subject matter.
     *
     * Borders, labels about labels, decorative rules, status text nothing acts on. Not automatically
     * wrong — some of it is legitimately load-bearing — but every one of them owes an answer.
     */
    val chrome: Int get() = (elements - inviting - content - ambient).coerceAtLeast(0)

    /**
     * The ratio worth watching.
     *
     * Content is excluded because it scales with data and chrome should not. A screen where this
     * number climbs as the product grows is a screen accreting scaffolding around a fixed set of
     * things a person can actually do — which is the construction zone, arriving one sign at a time.
     */
    val chromePerAct: Float
        get() = if (acts == 0) chrome.toFloat() else chrome.toFloat() / acts

    /** Something offered that cannot be reached is not offered. */
    val hasUnreachableActs: Boolean get() = unreachable.isNotEmpty()

    /** An invitation with no act behind it teaches a rule that is not true. */
    val hasMuteInvitations: Boolean get() = mute.isNotEmpty()

    /** Two elements answering to one address is worth a look, whether or not it turns out to be a bug. */
    val hasContestedAddresses: Boolean get() = contested.isNotEmpty()

    override fun toString(): String =
        "Census(acts=$acts reachable=$reachable elements=$elements " +
            "content=$content chrome=$chrome chromePerAct=$chromePerAct" +
            if (contested.isEmpty()) ")" else " contested=${contested.size})"

    companion object {
        /**
         * Where chrome stops being scaffolding and starts being a construction site.
         *
         * Deliberately generous, and deliberately not a hard limit. Surplusage is matter that can be
         * struck without loss, and no counter can tell which of these could be struck — only a
         * person can. Crossing this line means the surface owes an explanation, not that it is
         * wrong.
         */
        const val CROWDED = 4f
    }
}
