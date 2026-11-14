package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CHECKPOINT_COMMIT_METADATA_KEY
import com.hereliesaz.geministrator.domain.TEMPLATE_METADATA_KEY
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/** What check-work does with a piece of work: review it against criteria, or refuse. */
sealed interface CheckWorkSelection {
    /**
     * [criteria] are numbered by code from the template's review block (or the four fixed generic ones); [generic] says the
     * work had no template and was reviewed as a plain document. [requirements] is the text the reviewer is given.
     */
    data class Review(
        val requirements: String,
        val criteria: List<String>,
        val generic: Boolean,
        val templateKey: String?,
        val validatorId: String,
    ) : CheckWorkSelection

    data class Refuse(val reason: String) : CheckWorkSelection

    companion object {
        /** The four criteria any untemplated document is reviewed against. A model never invents criteria. */
        val GENERIC_CRITERIA: List<String> = listOf(
            "It answers what the task asked.",
            "Its claims are supported by what was given.",
            "It is consistent with itself.",
            "Nothing the task named is missing.",
        )

        /**
         * Chooses how [work] is checked. By the template the work carries (`aive.template`), the template's criteria, numbered, then
         * the [extra] requirements; with no template, the generic document (unless [allowGeneric] is off). Refuses effectful output
         * (a connected-tool result, a repository change), work with no text, and work that is itself a review. Pure.
         */
        fun select(work: ArtifactRef, extra: String? = null, allowGeneric: Boolean = true): CheckWorkSelection {
            if (work.textContent.isNullOrBlank()) return Refuse("There is no text to check.")
            val ref = work.metadata[TEMPLATE_METADATA_KEY]?.let(TemplateRef::parse)
            val template = ref?.let { BuiltInTemplates.byId[it.id] }
            val effectful = template?.effectful == true || CHECKPOINT_COMMIT_METADATA_KEY in work.metadata ||
                (template == null && work.kind == ArtifactKind.CodeChange)
            if (effectful) return Refuse("It is the result of a change in a tool or a repository, which a reviewer cannot see. Read it back instead.")
            val producible = template?.let { Producibles.forTemplate(it.id) }
            if (producible != null && !producible.reviewable) {
                return Refuse(producible.notReviewableReason ?: "That kind of work is not reviewed.")
            }
            val generic = producible == null
            if (generic && !allowGeneric) return Refuse("It has no template, and review without a template is switched off.")
            val criteria = producible?.criteria?.takeIf { it.isNotEmpty() } ?: GENERIC_CRITERIA
            val requirements = buildString {
                criteria.forEachIndexed { i, c -> append(i + 1).append(". ").append(c).append('\n') }
                extra?.trim()?.takeIf { it.isNotEmpty() }?.let { append("Also: ").append(it).append('\n') }
                if (producible != null) {
                    append("Already checked by code: ").append(producible.alreadyChecked).append(".\n")
                    append("Left to you: ").append(producible.leftToReviewer).append('\n')
                } else {
                    append("This work has no template and is reviewed generically.\n")
                }
                append("Answer `VERDICT:` then one line `CRITERION n: met` or `CRITERION n: unmet - why` for each numbered criterion.")
            }
            return Review(requirements, criteria, generic, ref?.key, StepOutputValidators.VERDICT_CRITERIA)
        }
    }
}
