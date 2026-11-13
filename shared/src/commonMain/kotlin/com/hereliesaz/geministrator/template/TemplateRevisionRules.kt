package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.workflow.StepOutputValidators

/** The pure rules for what a proposed revision may change. The applier runs them on the data, not on the proposal's words. */
object TemplateRevisionRules {
    const val MAX_WORDING_CHARS = 4_000
    const val MAX_CRITERIA = 20
    const val MAX_CRITERION_CHARS = 400
    const val MAX_INTENT_CHARS = 300

    sealed interface Check {
        /** [loosens] names fields that loosen a limit: the keeper confirms those explicitly. */
        data class Accepted(val body: TemplateBody, val changed: Set<TunableField>, val loosens: List<String>) : Check
        data class Refused(val reasons: List<String>) : Check
    }

    /** The names of the locked fields on which [claimed] differs from [actual]; empty when it matches. */
    fun lockedViolations(actual: Map<String, String>, claimed: Map<String, String>?): List<String> {
        if (claimed == null) return emptyList()
        return (actual.keys + claimed.keys).filter { actual[it] != claimed[it] }.sorted()
    }

    private fun plain(text: String): Boolean = text.none { it.code < 32 && it != '\n' && it != '\t' }

    fun check(base: TemplateBase, draft: TemplateRevisionDraft): Check {
        val reasons = mutableListOf<String>()
        val loosens = mutableListOf<String>()
        val changed = linkedSetOf<TunableField>()
        var body = base.body

        val intent = draft.intent
        when {
            intent == null -> reasons += "A revision must say what it aims to improve."
            intent.sentence.isBlank() || intent.hypothesis.isBlank() || intent.measure.isBlank() ->
                reasons += "The aim, the evidence it answers and how it will be judged must each be stated."
            intent.sentence.length > MAX_INTENT_CHARS || intent.hypothesis.length > MAX_INTENT_CHARS ||
                intent.measure.length > MAX_INTENT_CHARS || !plain(intent.sentence + intent.hypothesis + intent.measure) ->
                reasons += "The aim, evidence or measure is too long or not plain text."
        }
        if (draft.evidenceSteps < draft.mode.minEvidenceSteps) {
            reasons += "A ${if (draft.mode == RevisionMode.Genetic) "new generation" else "quick change"} needs at least " +
                "${draft.mode.minEvidenceSteps} steps of evidence; there are ${draft.evidenceSteps}."
        }
        lockedViolations(base.locked, draft.locked).forEach { reasons += "'$it' is locked and cannot be changed by a proposal." }

        draft.wording?.let { wording ->
            when {
                wording.isBlank() -> reasons += "The wording is empty."
                wording.length > MAX_WORDING_CHARS -> reasons += "The wording is longer than $MAX_WORDING_CHARS characters."
                !plain(wording) -> reasons += "The wording holds characters that are not plain text."
                wording != base.body.wording -> {
                    body = body.copy(wording = wording)
                    changed += TunableField.Wording
                }
            }
        }
        draft.criteria?.let { criteria ->
            when {
                criteria.size > MAX_CRITERIA -> reasons += "More than $MAX_CRITERIA criteria."
                criteria.any { it.isBlank() || it.length > MAX_CRITERION_CHARS || !plain(it) } ->
                    reasons += "A criterion is empty, too long or not plain text."
                criteria != base.body.criteria -> {
                    body = body.copy(criteria = criteria)
                    changed += TunableField.Criteria
                }
            }
        }
        draft.allowedTools?.let { tools ->
            val extra = tools.filter { it !in base.body.allowedTools }
            when {
                extra.isNotEmpty() -> reasons += "Tools may only be narrowed; '${extra.first()}' is not in the current set."
                tools.toSet() != base.body.allowedTools.toSet() -> {
                    body = body.copy(allowedTools = tools.distinct())
                    changed += TunableField.ToolsNarrowed
                }
            }
        }
        draft.validators?.let { validators ->
            for ((output, id) in validators) {
                val current = base.body.validators[output]
                when {
                    current == null -> reasons += "There is no output called '$output'."
                    !StepOutputValidators.isKnown(id) -> reasons += "Unknown output check '$id'."
                    id == current -> Unit
                    StepOutputValidators.isTighter(current, id) -> {
                        body = body.copy(validators = body.validators + (output to id))
                        changed += TunableField.Validator
                    }
                    StepOutputValidators.isTighter(id, current) -> {
                        body = body.copy(validators = body.validators + (output to id))
                        changed += TunableField.Validator
                        loosens += "the check on '$output'"
                    }
                    else -> reasons += "'$id' is not a version of the check on '$output'."
                }
            }
        }
        draft.budgetTokens?.let { budget ->
            val current = base.body.budgetTokens
            when {
                budget <= 0 -> reasons += "The allowance must be above zero."
                budget == current -> Unit
                else -> {
                    body = body.copy(budgetTokens = budget)
                    changed += TunableField.Allowance
                    if (current != null && budget > current) loosens += "the allowance"
                }
            }
        }
        draft.maxAttempts?.let { attempts ->
            val current = base.body.maxAttempts
            when {
                attempts < 1 -> reasons += "Attempts must be at least one."
                current != null && attempts > current -> reasons += "Attempts may only be lowered."
                attempts != current -> {
                    body = body.copy(maxAttempts = attempts)
                    changed += TunableField.Retry
                }
            }
        }
        if (reasons.isEmpty() && changed.isEmpty()) reasons += "The proposal changes nothing."
        return if (reasons.isEmpty()) Check.Accepted(body, changed, loosens) else Check.Refused(reasons)
    }
}

/** The mode a template proposal's text asks for, or null when the text is not a template draft. */
fun revisionModeOf(proposalText: String): RevisionMode? = try {
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(TemplateRevisionDraft.serializer(), proposalText).mode
} catch (_: Exception) {
    null
}

/** When the Nanny may propose: quick changes whenever there is evidence, new generations rarely, batched at a swarm's end. */
fun proposalAllowed(mode: RevisionMode, evidenceSteps: Int, swarmEnded: Boolean): Boolean =
    evidenceSteps >= mode.minEvidenceSteps && (mode == RevisionMode.Memetic || swarmEnded)
