package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TEMPLATE_METADATA_KEY
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.CheckWorkSelection
import com.hereliesaz.geministrator.template.StepInstantiation

/** What a check-work step does with the untemplated step it checks (docs/Step-Templates.md). */
sealed interface CheckWorkWrapDecision {
    /** Nothing changes: the step is not a check-work, its work is not another step's output, or that step has a template or contract of its own. */
    data object Unchanged : CheckWorkWrapDecision

    /** The work is plain text with no template: it is reviewed against [requirements] (the four generic criteria). */
    data class Wrap(val requirements: String) : CheckWorkWrapDecision

    /** The work is not reviewed, for [reason] in plain words. */
    data class Refuse(val reason: String) : CheckWorkWrapDecision
}

/**
 * Calls [CheckWorkSelection.select] for a check-work step whose `work` input is another step's output when that step has no
 * template or contract of its own. A step with a contract, or an output that names a template, is left exactly as it was.
 * Pure: reads the definition and the run, changes neither.
 */
object CheckWorkWrap {
    /** The blocking reason code of a check-work step held because its work is not reviewed. */
    const val CHECK_WORK_REFUSED = "CHECK_WORK_REFUSED"

    private const val NO_TEXT = "There is no text to check."

    fun decide(definition: WorkflowDefinition, run: WorkflowRun, task: TaskDefinition): CheckWorkWrapDecision {
        val contract = task.contract ?: return CheckWorkWrapDecision.Unchanged
        if (contract.startingPoint || contract.template.id != BuiltInTemplates.CHECK_WORK_ID) return CheckWorkWrapDecision.Unchanged
        val fromId = contract.inputs["work"]?.let(StepInstantiation::boundTask) ?: return CheckWorkWrapDecision.Unchanged
        val upstream = definition.tasks.firstOrNull { it.id == fromId } ?: return CheckWorkWrapDecision.Unchanged
        if (upstream.contract != null) return CheckWorkWrapDecision.Unchanged
        val upstreamRun = run.taskRuns[fromId] ?: return CheckWorkWrapDecision.Unchanged
        if (upstreamRun.status != TaskRunStatus.Completed) return CheckWorkWrapDecision.Unchanged
        val artifacts = upstreamRun.artifacts
        if (artifacts.any { TEMPLATE_METADATA_KEY in it.metadata }) return CheckWorkWrapDecision.Unchanged
        val extra = contract.inputs["requirements"]?.takeIf { StepInstantiation.boundTask(it) == null }
        val choices = artifacts.map { CheckWorkSelection.select(it, extra) }
        // Any reason other than "no text" stops the check (a change in a tool or repository, a review); an artifact with no text is passed over.
        val stopped = choices.filterIsInstance<CheckWorkSelection.Refuse>().firstOrNull { it.reason != NO_TEXT }
        if (stopped != null) return CheckWorkWrapDecision.Refuse(stopped.reason)
        val review = choices.filterIsInstance<CheckWorkSelection.Review>().firstOrNull()
            ?: return CheckWorkWrapDecision.Refuse(NO_TEXT)
        return CheckWorkWrapDecision.Wrap(review.requirements)
    }
}
