package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceVerification

/**
 * Whether a task's work was independently verified, read from the run and nothing else.
 *
 * An executor that reports Completed has verified nothing. A task counts as verified only when a
 * different task that depends on it and requires a Verification artifact completed with an
 * explicit approving verdict ([ArtifactVerdicts]). A reviewer that turned the work back (the gate
 * failed on a blocking verdict) makes it rejected, and one rejection outweighs any approval. A
 * review that finished without stating a verdict, or no review at all, leaves it unknown.
 */
object ProcedureVerifications {
    fun of(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        taskId: TaskDefinitionId,
    ): ProcedureTraceVerification {
        var approved = false
        for (reviewer in definition.tasks) {
            if (taskId !in reviewer.dependsOn || ArtifactKind.Verification !in reviewer.requiredArtifacts) continue
            val reviewerRun = run.taskRuns[reviewer.id] ?: continue
            val verdicts = reviewerRun.artifacts.mapNotNull(ArtifactVerdicts::of)
            if (verdicts.any { it.blocks }) return ProcedureTraceVerification.Rejected
            if (reviewerRun.status == TaskRunStatus.Completed && verdicts.any { it == ArtifactVerdict.Approve }) {
                approved = true
            }
        }
        return if (approved) ProcedureTraceVerification.Verified else ProcedureTraceVerification.Unknown
    }
}
