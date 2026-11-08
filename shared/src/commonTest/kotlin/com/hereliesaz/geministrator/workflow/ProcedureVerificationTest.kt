package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceVerification
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcedureVerificationTest {
    private val work = TaskDefinitionId("work")
    private val review = TaskDefinitionId("review")
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(
            TaskDefinition(
                id = work,
                name = "Work",
                objective = "Run the build",
                roleId = null,
                executor = TaskExecutor.GitHubAction("ci.yml"),
            ),
            TaskDefinition(
                id = review,
                name = "Review",
                objective = "Verify the build",
                roleId = BuiltInRoles.QaEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
                dependsOn = setOf(work),
                requiredArtifacts = setOf(ArtifactKind.Verification),
            ),
        ),
    )

    private fun run(reviewStatus: TaskRunStatus?, text: String?): WorkflowRun {
        val artifacts = if (text == null) {
            emptyList()
        } else {
            listOf(ArtifactRef(ArtifactId("a"), ArtifactKind.Verification, TaskRunId("r"), "x", textContent = text, createdAtEpochMillis = 1L))
        }
        val runs = buildMap {
            put(work, TaskRun(TaskRunId("w"), work, TaskRunStatus.Completed, assignedRoleId = null))
            if (reviewStatus != null) {
                put(review, TaskRun(TaskRunId("r"), review, reviewStatus, assignedRoleId = BuiltInRoles.QaEngineer.id, artifacts = artifacts))
            }
        }
        return WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("p"),
            workflowDefinitionId = definition.id,
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = runs,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
    }

    private fun verification(reviewStatus: TaskRunStatus?, text: String?) =
        ProcedureVerifications.of(definition, run(reviewStatus, text), work)

    @Test
    fun onlyAnExplicitApprovalFromADifferentReviewingTaskVerifies() {
        assertEquals(ProcedureTraceVerification.Verified, verification(TaskRunStatus.Completed, "VERDICT: approve\nok"))
        // Reported complete is not verified; neither is a review that states nothing, or no review.
        assertEquals(ProcedureTraceVerification.Unknown, verification(TaskRunStatus.Completed, "Looks fine."))
        assertEquals(ProcedureTraceVerification.Unknown, verification(TaskRunStatus.Running, null))
        assertEquals(ProcedureTraceVerification.Unknown, verification(null, null))
    }

    @Test
    fun aReviewerThatTurnedTheWorkBackRejectsIt() {
        assertEquals(ProcedureTraceVerification.Rejected, verification(TaskRunStatus.Failed, "VERDICT: reject\nbroken"))
        assertEquals(ProcedureTraceVerification.Rejected, verification(TaskRunStatus.Failed, "VERDICT: revise"))
    }

    @Test
    fun aTaskNothingReviewsIsUnknownWhateverItReported() {
        assertEquals(
            ProcedureTraceVerification.Unknown,
            ProcedureVerifications.of(definition, run(TaskRunStatus.Completed, "VERDICT: approve"), review),
        )
    }
}
