package com.hereliesaz.geministrator.memory.procedural

import kotlinx.serialization.Serializable

/**
 * [Verification] receipts are written after the fact, one per verified or rejected execution, as
 * new records beside the original receipt. A receipt is never rewritten.
 */
@Serializable
enum class ProcedureTracePhase { Dispatch, Reconcile, Verification }

@Serializable
enum class ProcedureTraceOutcome { Running, Verifying, Completed, Failed, Threw }

/**
 * Whether an independent reviewer later approved the work. An executor reporting Completed is
 * never verification: a receipt is born [Unknown] and only a verifying task's verdict changes it
 * (workflow/ProcedureVerification.kt).
 */
@Serializable
enum class ProcedureTraceVerification { Unknown, Verified, Rejected }

/**
 * One allowlisted, typed value the executor was configured with: a number, a flag, or a single
 * short token. Free text, paths and anything key-shaped are never captured, so a slot can only be
 * the kind of varying filler that repeated runs of one operation share a frame around.
 */
@Serializable
data class ProcedureTraceSlot(
    val name: String,
    val type: ProcedureParameterType,
    val value: String,
) {
    init {
        require(name.matches(NAME)) { "Invalid slot name" }
        when (type) {
            ProcedureParameterType.Integer -> require(value.matches(INTEGER)) { "Invalid integer slot" }
            ProcedureParameterType.Boolean -> require(value == "true" || value == "false") { "Invalid boolean slot" }
            ProcedureParameterType.Identifier ->
                require(value.matches(IDENTIFIER) && !looksLikeKey(value)) { "Invalid identifier slot" }
            else -> throw IllegalArgumentException("Slot type $type is not captured")
        }
    }

    companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val INTEGER = Regex("-?[0-9]{1,18}")
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,39}")
        const val MAX_SLOTS = 16

        /** A long run with no separator reads as a key, not a name. */
        fun looksLikeKey(value: String): Boolean =
            value.length >= 20 && value.none { it == '.' || it == '-' || it == '_' || it == '/' }
    }
}

@Serializable
data class ProcedureTrace(
    val workflowId: String,
    val projectId: String,
    val taskRunId: String,
    val taskDefinitionId: String,
    val attempt: Int,
    val phase: ProcedureTracePhase,
    val executorKind: String,
    val outcome: ProcedureTraceOutcome,
    val artifactKinds: List<String> = emptyList(),
    val artifactCount: Int = 0,
    val recordedAtEpochMillis: Long,
    /**
     * The executor's configured target by name (for example `github-action:ci.yml`), or null when it
     * has none that is safe to keep. Never a command, script source or input text.
     */
    val operation: String? = null,
    /** Allowlisted typed values the executor was configured with, sorted by name. */
    val slots: List<ProcedureTraceSlot> = emptyList(),
    val verification: ProcedureTraceVerification = ProcedureTraceVerification.Unknown,
) {
    init {
        require(workflowId.isNotBlank() && projectId.isNotBlank())
        require(taskRunId.isNotBlank() && taskDefinitionId.isNotBlank())
        require(attempt >= 0 && artifactCount >= 0 && artifactKinds.size <= 32)
        require(operation == null || operation.matches(OPERATION)) { "Invalid operation name" }
        require(slots.size <= ProcedureTraceSlot.MAX_SLOTS && slots.map { it.name }.distinct().size == slots.size) {
            "Slots must be unique and bounded"
        }
    }

    val receiptKey: String
        get() = "$workflowId:$taskRunId:$attempt:${phase.name}:${outcome.name}" +
            if (phase == ProcedureTracePhase.Verification) ":${verification.name}" else ""

    /**
     * The text banked as the receipt's episode. Memory condenses episodes whose frame matches and
     * whose fillers differ or agree, so the operation is the frame and the slot values are the
     * fillers. A receipt with neither reads exactly as it always did.
     */
    fun contextualSummary(): String =
        "System executor $executorKind ${phase.name.lowercase()} reported ${outcome.name.lowercase()}. " +
            (operation?.let { "Operation: $it. " } ?: "") +
            (slots.takeIf { it.isNotEmpty() }
                ?.let { "Slots: ${it.joinToString("; ") { slot -> "${slot.name}=${slot.value}" }}. " } ?: "") +
            "Verification: ${verification.name.lowercase()}. " +
            "Artifact count: $artifactCount; kinds: ${artifactKinds.joinToString(", ")}."

    companion object {
        val OPERATION = Regex("[a-z][a-z-]*:[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    }
}

fun interface ProcedureTraceSink {
    suspend fun record(trace: ProcedureTrace)
}

object NoOpProcedureTraceSink : ProcedureTraceSink {
    override suspend fun record(trace: ProcedureTrace) = Unit
}

/** Read-only view of a workflow bank's recorded receipts; empty when capture or memory is off. */
fun interface ProcedureTraceReader {
    suspend fun traces(workflowId: String): List<ProcedureTrace>
}

object NoOpProcedureTraceReader : ProcedureTraceReader {
    override suspend fun traces(workflowId: String): List<ProcedureTrace> = emptyList()
}
