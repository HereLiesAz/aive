package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.orchestration.CapabilityAssessment
import com.hereliesaz.geministrator.orchestration.CompletionInput
import com.hereliesaz.geministrator.orchestration.CriterionEvidence
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.EvidenceStatus
import com.hereliesaz.geministrator.orchestration.HandoffInput
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.orchestration.VerificationPlanningInput
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock

/**
 * Builds the exact provider-facing request used for both initial dispatch and persisted-run resume.
 *
 * Keeping this in one place prevents restart from bypassing payload redaction or drifting from the
 * request shape the provider originally received. The local orchestration utility family contributes
 * bounded control context only; workflow persistence remains the execution source of truth.
 */
internal fun buildProviderTaskRequest(
    project: Project,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    task: TaskDefinition,
    taskRun: TaskRun,
    role: RoleDefinition,
    orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
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

    val utilityBlocks = buildUtilityPromptBlocks(
        definition = definition,
        run = run,
        task = task,
        role = role,
        objective = objective,
        dependencyArtifacts = dependencyArtifacts,
        redactTaskNames = redaction.redactObjective,
        orchestrationUtilities = orchestrationUtilities,
    )

    return AgentTaskRequest(
        taskRunId = taskRun.id,
        objective = objective,
        roleInstructions = roleInstructions,
        acceptanceCriteria = task.acceptanceCriteria,
        contextArtifacts = dependencyArtifacts,
        requiredArtifacts = task.requiredArtifacts,
        repository = project.repository,
        requirePlanApproval = task.approvalPolicy != ApprovalPolicy.None,
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
            ) + utilityBlocks,
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

private fun buildUtilityPromptBlocks(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    task: TaskDefinition,
    role: RoleDefinition,
    objective: String,
    dependencyArtifacts: List<com.hereliesaz.geministrator.domain.ArtifactRef>,
    redactTaskNames: Boolean,
    orchestrationUtilities: LocalOrchestrationUtilityFamily,
): List<PromptContextBlock> {
    val taskById = definition.tasks.associateBy { it.id }
    val dependencies = task.dependsOn.mapNotNull { dependencyId ->
        run.taskRuns[dependencyId]?.let { dependencyRun ->
            dependencyId to dependencyRun
        }
    }

    fun dependencyLabel(
        id: com.hereliesaz.geministrator.domain.TaskDefinitionId,
    ): String = if (redactTaskNames) {
        id.value
    } else {
        taskById[id]?.name ?: id.value
    }

    val execution = orchestrationUtilities.summarizeExecution(definition, run)
    val executionBlock = PromptContextBlock(
        "Execution state",
        buildString {
            if (redactTaskNames) {
                appendLine("Completed tasks: ${execution.completedSteps.size}")
                appendLine("Active tasks: ${execution.activeSteps.size}")
                appendLine("Blocked tasks: ${execution.blockedSteps.size}")
                appendLine("Failed/escalated tasks: ${execution.failedSteps.size}")
                append("Awaiting approval tasks: ${execution.waitingForApprovalSteps.size}")
            } else {
                appendLine("Completed: ${execution.completedSteps.joinToString().ifBlank { "none" }}")
                appendLine("Active: ${execution.activeSteps.joinToString().ifBlank { "none" }}")
                appendLine("Blocked: ${execution.blockedSteps.joinToString().ifBlank { "none" }}")
                appendLine("Failed/escalated: ${execution.failedSteps.joinToString().ifBlank { "none" }}")
                append("Awaiting approval: ${execution.waitingForApprovalSteps.joinToString().ifBlank { "none" }}")
            }
        },
    )

    val completionCriteria = dependencies.map { (dependencyId, dependencyRun) ->
        val evidenceIds = buildList {
            add("task-run:${dependencyRun.id.value}")
            dependencyRun.artifacts.forEach { add("artifact:${it.id.value}") }
        }
        CriterionEvidence(
            criterion = "${dependencyLabel(dependencyId)} reached a successful terminal state",
            evidenceIds = evidenceIds,
            status = when (dependencyRun.status) {
                TaskRunStatus.Completed -> EvidenceStatus.Passed
                TaskRunStatus.Failed,
                TaskRunStatus.Escalated,
                TaskRunStatus.Cancelled,
                -> EvidenceStatus.Failed
                TaskRunStatus.Blocked -> EvidenceStatus.Blocked
                else -> EvidenceStatus.NotRun
            },
        )
    }
    val dependencyCompletion = orchestrationUtilities.evaluateCompletion(
        CompletionInput(
            objective = objective,
            criteria = completionCriteria,
            taskTerminal = dependencies.all { (_, dependencyRun) ->
                dependencyRun.status in setOf(
                    TaskRunStatus.Completed,
                    TaskRunStatus.Failed,
                    TaskRunStatus.Escalated,
                    TaskRunStatus.Cancelled,
                )
            },
        ),
    )

    val handoff = orchestrationUtilities.composeHandoff(
        HandoffInput(
            objective = objective,
            completed = dependencies
                .filter { (_, dependencyRun) -> dependencyRun.status == TaskRunStatus.Completed }
                .map { (dependencyId, _) -> dependencyLabel(dependencyId) },
            artifacts = dependencyArtifacts.map { it.id.value },
            state = dependencies.associate { (dependencyId, dependencyRun) ->
                dependencyLabel(dependencyId) to dependencyRun.status.name
            },
            unresolved = dependencies
                .filterNot { (_, dependencyRun) -> dependencyRun.status == TaskRunStatus.Completed }
                .map { (dependencyId, _) -> dependencyLabel(dependencyId) },
            failures = dependencies
                .filter { (_, dependencyRun) ->
                    dependencyRun.status in setOf(
                        TaskRunStatus.Failed,
                        TaskRunStatus.Escalated,
                        TaskRunStatus.Cancelled,
                    )
                }
                .map { (dependencyId, _) -> dependencyLabel(dependencyId) },
            nextAction = if (redactTaskNames) task.id.value else task.name,
            acceptanceCriteria = task.acceptanceCriteria.map { it.description },
            provenance = dependencies.map { (_, dependencyRun) -> "task-run:${dependencyRun.id.value}" },
        ),
    )
    val handoffBlock = PromptContextBlock(
        "Handoff",
        buildString {
            appendLine("Dependency gate: ${dependencyCompletion.decision.name}")
            appendLine("Completed: ${handoff.completed.joinToString().ifBlank { "none" }}")
            appendLine("Unresolved: ${handoff.unresolved.joinToString().ifBlank { "none" }}")
            appendLine("Failures: ${handoff.failures.joinToString().ifBlank { "none" }}")
            append("Artifacts: ${handoff.artifacts.joinToString().ifBlank { "none" }}")
        },
    )

    val escalation = orchestrationUtilities.evaluateEscalation(
        CapabilityAssessment(
            requiresMultiStepReasoning = task.dependsOn.size > 1,
            requiresCodebaseWideReasoning =
                AgentCapability.RepositoryWrite in role.capabilitiesRequired,
            requiredToolAvailable = true,
            hasEnoughEvidence = dependencyCompletion.unsatisfiedCriteria.isEmpty(),
            recommendedTier = "provider",
        ),
    )
    val escalationBlock = PromptContextBlock(
        "Local control assessment",
        buildString {
            append(escalation.decision.name)
            if (escalation.reasonCodes.isNotEmpty()) {
                append(" · ")
                append(escalation.reasonCodes.joinToString())
            }
        },
    )

    val verification = orchestrationUtilities.planVerification(
        VerificationPlanningInput(
            objective = objective,
            acceptanceCriteria = task.acceptanceCriteria.map { it.description },
            artifactKinds = dependencyArtifacts.mapTo(linkedSetOf()) { it.kind } + task.requiredArtifacts,
        ),
    )
    val verificationBlock = verification.steps.takeIf { it.isNotEmpty() }?.let { steps ->
        PromptContextBlock(
            "Verification plan",
            steps.joinToString("\n") { step ->
                buildString {
                    append("- ")
                    append(step.operationClass)
                    step.criterion?.let { criterion ->
                        append(": ")
                        append(criterion)
                    }
                    append(" [")
                    append(step.reasonCode)
                    append("]")
                }
            },
        )
    }

    return buildList {
        add(executionBlock)
        if (dependencies.isNotEmpty()) add(handoffBlock)
        add(escalationBlock)
        verificationBlock?.let(::add)
    }
}
