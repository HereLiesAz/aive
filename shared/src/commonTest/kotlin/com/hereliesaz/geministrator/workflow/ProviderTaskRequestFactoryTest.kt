package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.PayloadRedactionPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderTaskRequestFactoryTest {
    @Test
    fun providerRequestAppliesRedactionToResumeAndPromptContext() {
        val dependencyId = TaskDefinitionId("dependency")
        val targetId = TaskDefinitionId("target")
        val role = RoleDefinition(
            id = RoleDefinitionId("secret-role"),
            name = "Secret role",
            description = "test",
            instructions = "TOP SECRET ROLE INSTRUCTIONS",
        )
        val blockedArtifact = ArtifactRef(
            id = ArtifactId("blocked"),
            kind = ArtifactKind.Review,
            taskRunId = TaskRunId("dependency-run"),
            label = "Sensitive review",
            textContent = "do not send",
            createdAtEpochMillis = 1L,
        )
        val allowedArtifact = ArtifactRef(
            id = ArtifactId("allowed"),
            kind = ArtifactKind.CodeChange,
            taskRunId = TaskRunId("dependency-run"),
            label = "Allowed change",
            textContent = "safe",
            createdAtEpochMillis = 1L,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Definition",
            tasks = listOf(
                TaskDefinition(
                    id = dependencyId,
                    name = "TOP SECRET DEPENDENCY NAME",
                    objective = "Dependency",
                    roleId = role.id,
                ),
                TaskDefinition(
                    id = targetId,
                    name = "TOP SECRET TARGET NAME",
                    objective = "TOP SECRET TASK OBJECTIVE",
                    roleId = role.id,
                    dependsOn = setOf(dependencyId),
                ),
            ),
            payloadRedactionPolicy = PayloadRedactionPolicy(
                excludedArtifactKinds = setOf(ArtifactKind.Review),
                redactObjective = true,
                redactRoleInstructions = true,
            ),
        )
        val dependencyRun = TaskRun(
            id = TaskRunId("dependency-run"),
            taskDefinitionId = dependencyId,
            status = TaskRunStatus.Completed,
            assignedRoleId = role.id,
            artifacts = listOf(blockedArtifact, allowedArtifact),
        )
        val targetRun = TaskRun(
            id = TaskRunId("target-run"),
            taskDefinitionId = targetId,
            status = TaskRunStatus.Running,
            assignedRoleId = role.id,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("project"),
            workflowDefinitionId = definition.id,
            objective = "TOP SECRET WORKFLOW OBJECTIVE",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(dependencyId to dependencyRun, targetId to targetRun),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        val utilities = RecordingLocalOrchestrationUtilities()
        val request = buildProviderTaskRequest(
            project = Project(
                id = run.projectId,
                name = "Project",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            ),
            definition = definition,
            run = run,
            task = definition.tasks.last(),
            taskRun = targetRun,
            role = role,
            orchestrationUtilities = utilities,
        )

        assertEquals("[redacted]", request.objective)
        assertFalse(request.roleInstructions.contains(role.instructions))
        assertEquals(listOf(allowedArtifact), request.contextArtifacts)
        val promptText = (request.promptContext.stablePrefix + request.promptContext.dynamicContext)
            .joinToString("\n") { it.content }
        assertFalse(promptText.contains("TOP SECRET"))
        assertTrue(promptText.contains("[redacted]"))
        assertTrue(request.promptContext.dynamicContext.any { it.label == "Execution state" })
        assertTrue(request.promptContext.dynamicContext.any { it.label == "Handoff" })
        assertTrue(request.promptContext.dynamicContext.any { it.label == "Local control assessment" })
        assertTrue(request.promptContext.dynamicContext.any { it.label == "Verification plan" })
        assertEquals(1, utilities.executionSummaryCalls)
        assertEquals(1, utilities.handoffCalls)
        assertEquals(1, utilities.completionCalls)
        assertEquals(1, utilities.escalationCalls)
        assertEquals(1, utilities.verificationPlanningCalls)
    }
}
