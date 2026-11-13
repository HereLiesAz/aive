package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun

/*
 * The token allowance of a step made from a template (docs/Step-Templates.md). The contract declares it; this is the check.
 * Pure: counted from the tokens the Source reported for the step, never from anything the creature says. A step that
 * started from a template without being enforced (a starting-point contract) and a step with no allowance have none.
 */

/** The code of a step stopped because it spent its whole token allowance. It is not retried: another attempt would spend it again. */
const val STEP_ALLOWANCE_EXCEEDED: String = "STEP_ALLOWANCE_EXCEEDED"

/** The step's declared allowance in tokens, or null when it has none that is enforced. */
fun stepTokenAllowance(task: TaskDefinition?): Long? =
    task?.contract?.takeIf { !it.startingPoint }?.tokenAllowance?.takeIf { it > 0L }

/** Tokens the step has used so far, over every attempt. */
fun stepTokensUsed(taskRun: TaskRun): Long = taskRun.usage?.totalTokens ?: 0L

/** Whether the step has used its whole allowance. */
fun stepAllowanceSpent(task: TaskDefinition?, taskRun: TaskRun): Boolean {
    val allowance = stepTokenAllowance(task) ?: return false
    return stepTokensUsed(taskRun) >= allowance
}

/** The stop's reason, in plain words: what was used, what was allowed, and where to change it. */
fun stepAllowanceReason(task: TaskDefinition, taskRun: TaskRun): BlockingReason =
    BlockingReason(
        code = STEP_ALLOWANCE_EXCEEDED,
        message = "Stopped: this step used ${stepTokensUsed(taskRun)} of its ${stepTokenAllowance(task) ?: 0L} tokens. " +
            "It was not tried again. Raise its allowance in its job to let it have more.",
    )
