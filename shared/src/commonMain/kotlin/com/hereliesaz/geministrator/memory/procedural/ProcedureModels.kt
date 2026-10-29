package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import kotlinx.serialization.Serializable

/**
 * Procedural memories are NOT executable contextual memories. Everything in this foundation is
 * a candidate; only a future governed validator/invoker may promote or execute a pinned version.
 */
@Serializable
enum class ProcedureKind { Reflex, Habit }

@Serializable
enum class ProcedureSideEffect { ReadOnly, Reversible, Mutating, Privileged }

@Serializable
enum class ProcedureParameterType { Text, Integer, Boolean, Path, Identifier }

@Serializable
data class ProcedureParameter(
    val name: String,
    val type: ProcedureParameterType,
    val required: Boolean = true,
    val maxLength: Int = 256,
) {
    init {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) { "Invalid parameter name" }
        require(maxLength in 1..4096) { "Parameter bound must be 1..4096" }
    }
}

/**
 * A step describes an existing executor operation; no script body, shell interpolation, or
 * arbitrary remotely supplied command is accepted as a procedure in this initial slice.
 * [dependsOn] must reference earlier steps, keeping every habit finite and acyclic.
 */
@Serializable
data class ProcedureStep(
    val id: String,
    val executorKind: String,
    val operationId: String,
    val sideEffect: ProcedureSideEffect,
    val parameterBindings: Map<String, String> = emptyMap(),
    val dependsOn: Set<String> = emptySet(),
) {
    init {
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) { "Invalid step id" }
        require(executorKind.isNotBlank() && executorKind.length <= 64)
        require(operationId.isNotBlank() && operationId.length <= 128)
        require(parameterBindings.size <= 32)
        require(parameterBindings.all { (key, value) ->
            key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && value.length <= 256
        })
        require(dependsOn.size <= 32)
    }
}

/** Workflow identity is part of the address; names alone cannot identify trusted code. */
@Serializable
data class ProcedureReference(
    val workflowId: String,
    val name: String,
    val version: Int,
) {
    init {
        require(workflowId.isNotBlank() && workflowId.length <= 256)
        require(name.matches(Regex("[a-z][a-z0-9.-]{0,127}")))
        require(version > 0)
    }

    val displayId: String get() = "proc:$name@$version"
}

/**
 * Immutable proposal derived from source episodes. [contentDigest] identifies an immutable
 * artifact to be checked at validation/invocation time; the foundation never loads or runs it.
 */
@Serializable
data class ProcedureDefinition(
    val ref: ProcedureReference,
    val kind: ProcedureKind,
    val summary: String,
    val contentDigest: String,
    val sourceEpisodeIds: Set<MemoryEpisodeId>,
    val parameters: List<ProcedureParameter>,
    val steps: List<ProcedureStep>,
    val preconditions: List<String> = emptyList(),
    val verificationChecks: List<String> = emptyList(),
    val createdAtEpochMillis: Long,
) {
    init {
        require(summary.isNotBlank() && summary.length <= 240)
        require(contentDigest.matches(Regex("sha256:[0-9a-f]{64}"))) { "Procedure digest must be SHA-256" }
        require(sourceEpisodeIds.isNotEmpty() && sourceEpisodeIds.size <= 128)
        require(sourceEpisodeIds.all { it.value.isNotBlank() })
        require(parameters.size <= 32 && parameters.map { it.name }.distinct().size == parameters.size)
        require(steps.size in 1..32)
        require(kind != ProcedureKind.Reflex || steps.size == 1)
        require(kind != ProcedureKind.Habit || steps.size >= 2)
        require(steps.map { it.id }.distinct().size == steps.size)
        val predecessors = mutableSetOf<String>()
        steps.forEach { step ->
            require(step.dependsOn.all { it in predecessors }) { "Dependencies must refer to earlier steps" }
            predecessors += step.id
        }
        require(preconditions.size <= 32 && verificationChecks.size <= 32)
        require((preconditions + verificationChecks).all { it.isNotBlank() && it.length <= 256 })
    }
}

@Serializable
enum class ProcedureEvidenceOutcome { Unknown, Failed, Completed, Verified }

/**
 * Evidence is an attributed report, not an authorization or proof of correctness.
 * Later validation must independently authenticate receipts before promoting a candidate.
 */
@Serializable
data class ProcedureEvidence(
    val id: String,
    val procedure: ProcedureReference,
    val sourceEpisodeId: MemoryEpisodeId,
    val taskRunId: String,
    val receiptId: String,
    val outcome: ProcedureEvidenceOutcome,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(sourceEpisodeId.value.isNotBlank())
        require(taskRunId.isNotBlank() && taskRunId.length <= 256)
        require(receiptId.isNotBlank() && receiptId.length <= 256)
    }
}

/** Search/display only; no invoker exists until promotion and authority gates are implemented. */
data class ProcedureCandidate(
    val definition: ProcedureDefinition,
    val inherited: Boolean,
) {
    val executable: Boolean get() = false
}
