package com.hereliesaz.geministrator.memory.procedural

import kotlinx.serialization.Serializable

@Serializable
enum class ProcedureTracePhase { Dispatch, Reconcile }

@Serializable
enum class ProcedureTraceOutcome { Running, Verifying, Completed, Failed, Threw }

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
) {
    init {
        require(workflowId.isNotBlank() && projectId.isNotBlank())
        require(taskRunId.isNotBlank() && taskDefinitionId.isNotBlank())
        require(attempt >= 0 && artifactCount >= 0 && artifactKinds.size <= 32)
    }
    val receiptKey: String get() = "$workflowId:$taskRunId:$attempt:${phase.name}:${outcome.name}"
    fun contextualSummary(): String =
        "System executor $executorKind ${phase.name.lowercase()} reported ${outcome.name.lowercase()}. " +
            "Verification: unknown. Artifact count: $artifactCount; kinds: ${artifactKinds.joinToString(", ")}."
}

fun interface ProcedureTraceSink {
    suspend fun record(trace: ProcedureTrace)
}

object NoOpProcedureTraceSink : ProcedureTraceSink {
    override suspend fun record(trace: ProcedureTrace) = Unit
}
