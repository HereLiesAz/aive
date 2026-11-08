package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
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
import com.hereliesaz.geministrator.memory.procedural.ProcedureTrace
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceVerification
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcedureVerificationObserverTest {
    private val build = TaskDefinitionId("build")
    private val review = TaskDefinitionId("review")
    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "w",
        tasks = listOf(
            TaskDefinition(
                id = build,
                name = "Build",
                objective = "Run CI",
                roleId = null,
                executor = TaskExecutor.GitHubAction("ci.yml", ref = "main"),
            ),
            TaskDefinition(
                id = review,
                name = "Review",
                objective = "Verify the build",
                roleId = BuiltInRoles.QaEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
                dependsOn = setOf(build),
                requiredArtifacts = setOf(ArtifactKind.Verification),
            ),
        ),
    )

    private fun run(buildStatus: TaskRunStatus, reviewStatus: TaskRunStatus, verdict: String?): WorkflowRun {
        val artifacts = verdict?.let {
            listOf(ArtifactRef(ArtifactId("a"), ArtifactKind.Verification, TaskRunId("rv"), "x", textContent = it, createdAtEpochMillis = 1L))
        }.orEmpty()
        return WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                build to TaskRun(TaskRunId("rb"), build, buildStatus, assignedRoleId = null, executor = definition.tasks[0].executor),
                review to TaskRun(TaskRunId("rv"), review, reviewStatus, assignedRoleId = BuiltInRoles.QaEngineer.id, artifacts = artifacts),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 77L,
        )
    }

    private fun observe(vararg runs: WorkflowRun): List<ProcedureTrace> = runBlocking {
        val seen = mutableListOf<ProcedureTrace>()
        val observer = ProcedureVerificationObserver { com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink { seen += it } }
        runs.forEach { observer.observe(project, definition, it) }
        seen
    }

    @Test
    fun anApprovalIsWrittenOnceAsAVerificationReceiptBesideTheExecution() {
        val approved = run(TaskRunStatus.Completed, TaskRunStatus.Completed, "VERDICT: approve\nok")
        val receipts = observe(approved, approved, approved)
        val receipt = receipts.single()
        assertEquals(ProcedureTracePhase.Verification, receipt.phase)
        assertEquals(ProcedureTraceVerification.Verified, receipt.verification)
        assertEquals("github-action:ci.yml", receipt.operation)
        assertEquals(listOf("ref"), receipt.slots.map { it.name })
        assertEquals(77L, receipt.recordedAtEpochMillis)
        assertTrue(receipt.receiptKey.endsWith(":Verification:Completed:Verified"))
    }

    @Test
    fun aRejectionIsWrittenAndAnythingUnreviewedOrUnfinishedIsNot() {
        val rejected = observe(run(TaskRunStatus.Completed, TaskRunStatus.Failed, "VERDICT: reject\nbroken")).single()
        assertEquals(ProcedureTraceVerification.Rejected, rejected.verification)

        assertTrue(observe(run(TaskRunStatus.Completed, TaskRunStatus.Completed, "Looks fine.")).isEmpty())
        assertTrue(observe(run(TaskRunStatus.Completed, TaskRunStatus.Running, null)).isEmpty())
        // The work itself did not finish, so there is nothing to verify.
        assertTrue(observe(run(TaskRunStatus.Running, TaskRunStatus.Completed, "VERDICT: approve")).isEmpty())
    }
}
