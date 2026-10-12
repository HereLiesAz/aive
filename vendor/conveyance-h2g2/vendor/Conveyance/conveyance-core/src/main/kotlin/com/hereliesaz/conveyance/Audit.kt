package com.hereliesaz.conveyance

/**
 * The framework describing a surface to something that will judge it.
 *
 * Some of Conveyance's most important questions are relational rather than structural: can a person
 * tell what an act will do before taking it, are several under-employed fragments really one missing
 * object, does the visible Act outline have one hero of the hill, and does a rendered hierarchy agree
 * with the product's declared intent? [AuditFrame] is the semantic half of that evidence. A visual
 * evaluator may see only pixels; Conscience gets the truth afterwards.
 */
data class AuditFrame(
    val surface: String,
    val census: Census,
    val elements: List<AuditElement>,
    /** Every address currently a gate's own. */
    val gateAddresses: Set<ElementId> = emptySet(),
)

/** One element as the framework knows it: where it is, what it does, and what it offers. */
data class AuditElement(
    val id: ElementId,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val visible: Boolean,
    /** Present when this element offers an act. Absent means it does nothing when touched. */
    val act: ActId? = null,
    /**
     * The Act lifecycle this element is rendered inside, when the binding can prove that relation.
     *
     * This is intentionally distinct from [act]: a progress or completion fragment may report an
     * Act without itself offering that Act. Bindings should leave this null rather than guess when
     * lifecycle membership cannot be observed directly.
     */
    val lifecycleAct: ActId? = null,
    val verb: Verb? = null,
    /** What the act will change, rendered for human-readable audit output. */
    val consequence: String? = null,
    /** The actual semantic destination of the offered Act, preserved for relational analysis. */
    val target: ElementId? = null,
    val weight: Weight? = null,
    /** Whether the act can be taken back. A person deserves to know this before acting. */
    val reversible: Boolean = false,
    /** Whether the act is currently gated. */
    val blocked: Boolean = false,
    val jobs: Set<Job> = emptySet(),
    /**
     * The functional emphasis declared by the offered Act.
     *
     * This is not a style measurement, Employment, or UI state. The binding can resolve the declared
     * level against other visible Acts; Conscience keeps the declaration so conflicts such as two
     * visible Heroic Acts can be explained without mutating either Act.
     */
    val emphasis: ActEmphasis? = null,
    /**
     * Whether this element was explicitly declared [Employment.Ambient].
     *
     * Kept separate from [jobs] because the two answer different questions: jobs are what the
     * element is observed doing; Ambient says it intentionally is not a working element and therefore
     * opts out of the four-job rule.
     */
    val ambient: Boolean = false,
) {
    /** Whether this carries a cost a person cannot take back. */
    val staked: Boolean get() = act != null && !reversible
}
