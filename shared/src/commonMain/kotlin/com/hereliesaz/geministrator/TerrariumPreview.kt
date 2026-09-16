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
            selectedTaskId = null,
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
    val research = TaskDefinition(
        id = TaskDefinitionId("research"),
        name = "Gather evidence",
        objective = "Research the problem and return evidence to the architect.",
        roleId = BuiltInRoles.Researcher.id,
        executor = TaskExecutor.RoleAgent(BuiltInRoles.Researcher.id),
    )
    val architecture = TaskDefinition(
        id = TaskDefinitionId("architecture"),
        name = "Shape the plan",
        objective = "Turn evidence into an implementation plan.",
        roleId = BuiltInRoles.Architect.id,
        dependsOn = setOf(research.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.Architect.id),
    )
    val externalReview = TaskDefinition(
        id = TaskDefinitionId("external-review"),
        name = "Send pull request",
        objective = "Ask the external pull-request service to deliver the review payload.",
        roleId = null,
        dependsOn = setOf(architecture.id),
        executor = TaskExecutor.ExternalService("github-pull-request", "deliver"),
    )
    val regressionTests = TaskDefinition(
        id = TaskDefinitionId("regression-tests"),
        name = "Run regression suite",
        objective = "Run the workflow regression suite.",
        roleId = null,
        dependsOn = setOf(architecture.id),
        executor = TaskExecutor.TestRunner("./gradlew test"),
    )
    val implementation = TaskDefinition(
        id = TaskDefinitionId("implementation"),
        name = "Implement the change",
        objective = "Implement the approved architecture using delivered review context and tests.",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = setOf(externalReview.id, regressionTests.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )
    val approval = TaskDefinition(
        id = TaskDefinitionId("approval"),
        name = "Human approval",
        objective = "Wait for integration approval.",
        roleId = null,
        dependsOn = setOf(implementation.id),
        executor = TaskExecutor.HumanApproval("Integration approval"),
    )
    val verification = TaskDefinition(
        id = TaskDefinitionId("verification"),
        name = "Verify independently",
        objective = "Falsify completion claims against the acceptance criteria.",
        roleId = BuiltInRoles.QaEngineer.id,
        dependsOn = setOf(approval.id),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.QaEngineer.id),
    )

    val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("terrarium-wasm-proof"),
        name = "Terrarium visual proof",
        tasks = listOf(
            research,
            architecture,
            externalReview,
            regressionTests,
            implementation,
            approval,
            verification,
        ),
    )
    val run = WorkflowRun(
        id = WorkflowRunId("terrarium-wasm-proof-run"),
        projectId = ProjectId("terrarium-proof"),
        workflowDefinitionId = definition.id,
        objective = "Demonstrate the living workflow terrarium",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            research.id to proofTaskRun(
                research,
                TaskRunStatus.Completed,
                BuiltInRoles.Researcher.id.value,
                artifact = true,
            ),
            architecture.id to proofTaskRun(
                architecture,
                TaskRunStatus.Completed,
                BuiltInRoles.Architect.id.value,
                artifact = true,
            ),
            externalReview.id to proofTaskRun(externalReview, TaskRunStatus.Running, null),
            regressionTests.id to proofTaskRun(regressionTests, TaskRunStatus.Completed, null),
            implementation.id to proofTaskRun(
                implementation,
                TaskRunStatus.Running,
                BuiltInRoles.ImplementationEngineer.id.value,
                progress = .56f,
            ),
            approval.id to proofTaskRun(approval, TaskRunStatus.Blocked, null),
            verification.id to proofTaskRun(
                verification,
                TaskRunStatus.Ready,
                BuiltInRoles.QaEngineer.id.value,
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
            BuiltInRoles.Researcher,
            BuiltInRoles.Architect,
            BuiltInRoles.ImplementationEngineer,
            BuiltInRoles.QaEngineer,
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
