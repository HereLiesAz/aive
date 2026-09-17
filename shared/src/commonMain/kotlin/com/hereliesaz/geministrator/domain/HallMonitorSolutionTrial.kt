package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/**
 * Durable link between a paused Hall Monitor review and an isolated counterfactual run.
 *
 * The trial run is deliberately separate from the paused source run. Its WorkflowRun is the
 * authoritative source for execution status and artifacts; this record only preserves why that run
 * exists and which proposed solution it exercises.
 */
@Serializable
data class HallMonitorSolutionTrial(
    val id: String,
    val findingId: String,
    val solutionIndex: Int,
    val solutionTitle: String,
    val action: HallMonitorAction,
    val target: String,
    val validationTests: List<String>,
    val workflowDefinitionId: WorkflowDefinitionId,
    val workflowRunId: WorkflowRunId,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Hall Monitor solution trial id must not be blank" }
        require(findingId.isNotBlank()) { "Hall Monitor solution trial finding id must not be blank" }
        require(solutionIndex >= 0) { "Hall Monitor solution index must not be negative" }
        require(solutionTitle.isNotBlank()) { "Hall Monitor solution title must not be blank" }
        require(target.isNotBlank()) { "Hall Monitor solution target must not be blank" }
        require(validationTests.isNotEmpty()) { "Hall Monitor solution trial requires validation tests" }
    }
}
