package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock

/**
 * Builds the exact provider-facing request used for both initial dispatch and persisted-run resume.
 *
 * Keeping this in one place prevents restart from bypassing payload redaction or drifting from the
 * request shape the provider originally received.
 */
internal fun buildProviderTaskRequest(
    project: Project,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    task: TaskDefinition,
    taskRun: TaskRun,
    role: RoleDefinition,
): AgentTaskRequest {
    val redaction = definition.payloadRedactionPolicy
    val objective = if (redaction.redactObjective) "[redacted]" else task.objective
    val roleInstructions = if (redaction.redactRoleInstructions) {
        swarmInstructions
    } else {
        "$swarmInstructions\n\n${role.instructions}"
    }
    val dependencyArtifacts = task.dependsOn
        .mapNotNull(run.taskRuns::get)
        .flatMap(TaskRun::artifacts)
        .filter { it.kind !in redaction.excludedArtifactKinds }

    return AgentTaskRequest(
        taskRunId = taskRun.id,
        objective = objective,
        roleInstructions = roleInstructions,
        acceptanceCriteria = task.acceptanceCriteria,
        contextArtifacts = dependencyArtifacts,
        requiredArtifacts = task.requiredArtifacts,
        repository = project.repository,
        requirePlanApproval = task.approvalPolicy != com.hereliesaz.geministrator.domain.ApprovalPolicy.None,
        promptContext = PromptContext(
            stablePrefix = listOf(
                PromptContextBlock(
                    "Workflow objective",
                    if (redaction.redactObjective) "[redacted]" else run.objective,
                ),
                PromptContextBlock(
                    "Role",
                    if (redaction.redactRoleInstructions) "[redacted]" else role.instructions,
                ),
            ),
            dynamicContext = listOf(
                PromptContextBlock("Task", objective),
                PromptContextBlock("Attempt", taskRun.attempt.toString()),
            ),
            reusePolicy = definition.promptReusePolicy,
            cacheNamespace = "${run.id.value}:${role.id.value}",
        ),
        orchestrationContext = AgentOrchestrationContext(
            projectId = project.id,
            workflowRunId = run.id,
            workflowDefinitionId = definition.id,
            taskDefinitionId = task.id,
            roleId = role.id,
        ),
    )
}
