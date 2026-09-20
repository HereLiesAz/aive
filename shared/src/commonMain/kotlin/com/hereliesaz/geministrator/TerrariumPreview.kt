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
import com.hereliesaz.geministrator.domain.HallMonitorRole
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
 * The fixture intentionally renders the complete fifteen-role cast so the visual proof exercises
 * every production Rust archetype, several workflow states, the detached relationship arms, and the
 * automatic camera focus/zoom path in one deterministic screen.
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
    fun agentTask(
        id: String,
        name: String,
        role: RoleDefinition,
        vararg dependsOn: TaskDefinition,
    ): TaskDefinition = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = "Visual proof for ${role.name}.",
        roleId = role.id,
        dependsOn = dependsOn.mapTo(linkedSetOf()) { it.id },
        executor = TaskExecutor.RoleAgent(role.id),
    )

    val product = agentTask("product", "Clarify the objective", BuiltInRoles.ProductManager)
    val research = agentTask("research", "Collect evidence", BuiltInRoles.Researcher)
    val environment = agentTask("environment", "Select environment", BuiltInRoles.EpaRepresentative)

    val architecture = agentTask(
        "architecture",
        "Shape the architecture",
        BuiltInRoles.Architect,
        product,
        research,
    )
    val ux = agentTask(
        "ux",
        "Shape the experience",
        BuiltInRoles.UxDesigner,
        product,
    )
    val hallMonitor = agentTask(
        "hall-monitor",
        "Observe system behavior",
        HallMonitorRole.definition,
        research,
        environment,
    )

    val preCode = agentTask(
        "pre-code",
        "Write the verification contract",
        BuiltInRoles.CrashTestDummy,
        architecture,
        ux,
    )
    val adversarial = agentTask(
        "adversarial",
        "Challenge the plan",
        BuiltInRoles.AdversarialReviewer,
        architecture,
        hallMonitor,
    )
    val implementation = agentTask(
        "implementation",
        "Implement the objective",
        BuiltInRoles.ImplementationEngineer,
        preCode,
        adversarial,
        environment,
    )

    val verification = agentTask(
        "verification",
        "Verify independently",
        BuiltInRoles.QaEngineer,
        implementation,
    )
    val review = agentTask(
        "review",
        "Review implementation",
        BuiltInRoles.CodeReviewer,
        implementation,
    )
    val antagonist = agentTask(
        "antagonist",
        "Audit the claims",
        BuiltInRoles.Antagonist,
        implementation,
    )

    val recovery = agentTask(
        "recovery",
        "Recover a bounded failure",
        BuiltInRoles.RecoveryEngineer,
        verification,
        review,
    )
    val release = agentTask(
        "release",
        "Open the release gate",
        BuiltInRoles.ReleaseEngineer,
        verification,
        review,
        antagonist,
        recovery,
    )

    val tasks = listOf(
        product,
        research,
        environment,
        architecture,
        ux,
        hallMonitor,
        preCode,
        adversarial,
        implementation,
        verification,
        review,
        antagonist,
        recovery,
        release,
    )
    val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("terrarium-wasm-proof"),
        name = "Terrarium full-cast visual proof",
        tasks = tasks,
    )

    val stateByTask = mapOf(
        product.id to TaskRunStatus.Created,
        research.id to TaskRunStatus.Ready,
        environment.id to TaskRunStatus.Running,
        architecture.id to TaskRunStatus.AwaitingApproval,
        ux.id to TaskRunStatus.Blocked,
        hallMonitor.id to TaskRunStatus.Completed,
        preCode.id to TaskRunStatus.Completed,
        adversarial.id to TaskRunStatus.AwaitingApproval,
        implementation.id to TaskRunStatus.Running,
        verification.id to TaskRunStatus.Verifying,
        review.id to TaskRunStatus.Blocked,
        antagonist.id to TaskRunStatus.Failed,
        recovery.id to TaskRunStatus.Retrying,
        release.id to TaskRunStatus.Ready,
    )
    val roleByTask = tasks.associate { task ->
        task.id to requireNotNull(task.roleId).value
    }

    val run = WorkflowRun(
        id = WorkflowRunId("terrarium-wasm-proof-run"),
        projectId = ProjectId("terrarium-proof"),
        workflowDefinitionId = definition.id,
        objective = "Demonstrate the complete semantic node-creature cast",
        status = WorkflowRunStatus.Running,
        taskRuns = tasks.associate { task ->
            task.id to proofTaskRun(
                task = task,
                status = stateByTask.getValue(task.id),
                roleId = roleByTask.getValue(task.id),
                artifact = task.id == preCode.id,
                progress = when (task.id) {
                    implementation.id -> .56f
                    verification.id -> .72f
                    recovery.id -> .38f
                    else -> null
                },
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    return TerrariumVisualProofFixture(
        definition = definition,
        run = run,
        roles = BuiltInRoles.all + HallMonitorRole.definition,
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
