package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
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

/**
 * Deterministic visual-proof fixture for WASM screenshots. It deliberately renders through the same
 * production [GeministratorWorkflowTerrarium] used by live workflows; only the runtime data is a
 * self-contained fixture. No preview-only renderer or fake animation path exists.
 *
 * The fixture intentionally uses the canonical five-character cast from the workflow UI review so
 * the screenshot proves the actual Rust-generated Orchestrator, Implementation Engineer, Crash Test
 * Dummy, QA Engineer, and Code Reviewer together. Implementation starts selected so the same proof
 * also exercises automatic camera focus/zoom.
 */
@Composable
fun TerrariumVisualProofScreen(
    modifier: Modifier = Modifier,
) {
    val fixture = terrariumVisualProofFixture()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Azphalt.currentGround.page)
            .padding(20.dp),
    ) {
        GeministratorWorkflowTerrarium(
            definition = fixture.definition,
            run = fixture.run,
            roles = fixture.roles,
            selectedTaskId = "implementation",
            onTaskSelected = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal data class TerrariumVisualProofFixture(
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val roles: List<RoleDefinition>,
)

internal fun terrariumVisualProofFixture(): TerrariumVisualProofFixture {
    val preCode = TaskDefinition(
        id = TaskDefinitionId("pre-code"),
        name = "Write the verification contract",
        objective = "Define the pre-code tests and failure cases before implementation begins.",
        roleId = BuiltInRoles.CrashTestDummy.id,
        executor = TaskExecutor.RoleAgent(BuiltInRoles.CrashTestDummy.id),
    )
    val implementation = TaskDefinition(
        id = TaskDefinitionId("implementation"),
        name = "Implement the objective",
        objective = "Implement the approved objective against the verification contract.",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = setOf(preCode.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )
    val verification = TaskDefinition(
        id = TaskDefinitionId("verification"),
        name = "Verify independently",
        objective = "Falsify the implementation against the approved acceptance criteria and tests.",
        roleId = BuiltInRoles.QaEngineer.id,
        dependsOn = setOf(implementation.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
    )
    val review = TaskDefinition(
        id = TaskDefinitionId("review"),
        name = "Review implementation",
        objective = "Review the verified implementation for correctness and unintended side effects.",
        roleId = BuiltInRoles.CodeReviewer.id,
        dependsOn = setOf(verification.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.CodeReviewer.id),
    )

    val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("terrarium-wasm-proof"),
        name = "Terrarium visual proof",
        tasks = listOf(preCode, implementation, verification, review),
    )
    val run = WorkflowRun(
        id = WorkflowRunId("terrarium-wasm-proof-run"),
        projectId = ProjectId("terrarium-proof"),
        workflowDefinitionId = definition.id,
        objective = "Demonstrate the canonical semantic node-creature cast",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            preCode.id to proofTaskRun(
                preCode,
                TaskRunStatus.Completed,
                BuiltInRoles.CrashTestDummy.id.value,
                artifact = true,
            ),
            implementation.id to proofTaskRun(
                implementation,
                TaskRunStatus.Running,
                BuiltInRoles.ImplementationEngineer.id.value,
                progress = .56f,
            ),
            verification.id to proofTaskRun(
                verification,
                TaskRunStatus.Ready,
                BuiltInRoles.QaEngineer.id.value,
            ),
            review.id to proofTaskRun(
                review,
                TaskRunStatus.Blocked,
                BuiltInRoles.CodeReviewer.id.value,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )
    return TerrariumVisualProofFixture(
        definition = definition,
        run = run,
        roles = listOf(
            BuiltInRoles.Orchestrator,
            BuiltInRoles.ImplementationEngineer,
            BuiltInRoles.CrashTestDummy,
            BuiltInRoles.QaEngineer,
            BuiltInRoles.CodeReviewer,
        ),
    )
}

private fun proofTaskRun(
    task: TaskDefinition,
    status: TaskRunStatus,
    roleId: String?,
    artifact: Boolean = false,
    progress: Float? = null,
): TaskRun {
    val taskRunId = TaskRunId("proof:${task.id.value}")
    val artifacts = if (artifact) {
        listOf(
            ArtifactRef(
                id = ArtifactId("proof:${task.id.value}:artifact"),
                taskRunId = taskRunId,
                kind = ArtifactKind.TaskPlan,
                label = "${task.name} proof artifact",
                uri = "memory://proof/${task.id.value}",
                createdAtEpochMillis = 1L,
            ),
        )
    } else {
        emptyList()
    }
    return TaskRun(
        id = taskRunId,
        taskDefinitionId = task.id,
        status = status,
        assignedRoleId = roleId?.let(::RoleDefinitionId),
        artifacts = artifacts,
        progress = progress,
        executor = task.executor,
    )
}
