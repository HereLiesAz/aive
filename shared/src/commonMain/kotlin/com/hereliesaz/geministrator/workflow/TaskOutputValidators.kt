package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef

/*
 * The deterministic check on a creature's output (VerificationPolicy.Deterministic). An agent's output is model text and
 * untrusted: a compiled validator reads it, never runs it. There are no scripts and no model calls here, and an id that is
 * not registered is a violation, so nothing a saved definition names can add code.
 */

/** Failure code of a task whose output a registered validator turned back. */
const val TASK_OUTPUT_REJECTED: String = "TASK_OUTPUT_REJECTED"

/** Blocking-reason code of an optional creature that failed for good and was skipped; its message is the reason. */
const val OPTIONAL_STEP_SKIPPED: String = "OPTIONAL_STEP_SKIPPED"

/** One validator: the problems with [artifacts], each a plain sentence the next attempt is told; empty when they pass. */
fun interface TaskOutputValidator {
    fun problems(artifacts: List<ArtifactRef>): List<String>
}

/** The validators the engine may name, by id. Immutable: a registry is built once and handed to the engine. */
class TaskOutputValidatorRegistry(private val validators: Map<String, TaskOutputValidator> = emptyMap()) {
    /** The problems [validatorId] finds in [artifacts]; an unknown id is itself the problem. */
    fun problems(validatorId: String, artifacts: List<ArtifactRef>): List<String> {
        val validator = validators[validatorId] ?: return listOf("Unknown output check '$validatorId'.")
        return validator.problems(artifacts).filter { it.isNotBlank() }
    }

    fun with(validatorId: String, validator: TaskOutputValidator): TaskOutputValidatorRegistry =
        TaskOutputValidatorRegistry(validators + (validatorId to validator))

    fun knows(validatorId: String): Boolean = validatorId in validators

    companion object {
        val Empty = TaskOutputValidatorRegistry()
    }
}

/** The text handed to the next attempt: what was wrong, to fix. */
fun taskOutputRejectedMessage(problems: List<String>): String =
    "The output was turned back: " + problems.joinToString(" ")
