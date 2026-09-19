package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonOfThoughtTest {
    @Test
    fun expanderMaterializesConditionalSkeletonParallelExpansionAggregationAndVerification() {
        val triggerId = TaskDefinitionId("trigger")
        val taskId = TaskDefinitionId("reason")
        val downstreamId = TaskDefinitionId("downstream")
        val condition = TaskCondition.OnFailure(triggerId)
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("sot"),
            name = "Skeleton-of-Thought",
            testDesignPolicy = TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = triggerId,
                    name = "Trigger",
                    objective = "Attempt primary path",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
                TaskDefinition(
                    id = taskId,
                    name = "Reason",
                    objective = "Synthesize the bounded recovery decision",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    dependsOn = setOf(triggerId),
                    condition = condition,
                    verificationPolicy = VerificationPolicy.Required(BuiltInRoles.QaEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    compoundInferencePolicy = CompoundInferencePolicy.SkeletonOfThought(
                        skeletonRoleId = BuiltInRoles.Architect.id,
                        independenceReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
                        expansionRoleIds = listOf(BuiltInRoles.Researcher.id, BuiltInRoles.ProductManager.id),
                        aggregatorRoleId = BuiltInRoles.ImplementationEngineer.id,
                    ),
                ),
                TaskDefinition(
                    id = downstreamId,
                    name = "Downstream",
                    objective = "Consume only a verified result",
                    roleId = BuiltInRoles.ReleaseEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ReleaseEngineer.id),
                    dependsOn = setOf(taskId),
                    condition = TaskCondition.OnAnyOutcome(taskId),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
            ),
        )

        val expanded = SkeletonOfThoughtExpander.expand(definition, BuiltInRoles.all)
        val byId = expanded.tasks.associateBy(TaskDefinition::id)
        val skeleton = TaskDefinitionId("reason--sot-skeleton")
        val independence = TaskDefinitionId("reason--sot-independence")
        val expansionOne = TaskDefinitionId("reason--sot-expansion-1")
        val expansionTwo = TaskDefinitionId("reason--sot-expansion-2")
        val verifier = TaskDefinitionId("reason--sot-verifier")

        assertEquals(8, expanded.tasks.size)
        assertEquals(setOf(triggerId), byId.getValue(skeleton).dependsOn)
        assertEquals(setOf(triggerId, skeleton), byId.getValue(independence).dependsOn)
        assertEquals(setOf(triggerId, skeleton, independence), byId.getValue(expansionOne).dependsOn)
        assertEquals(setOf(triggerId, skeleton, independence), byId.getValue(expansionTwo).dependsOn)
        assertEquals(
            setOf(triggerId, skeleton, independence, expansionOne, expansionTwo),
            byId.getValue(taskId).dependsOn,
        )
        assertEquals(
            setOf(triggerId, skeleton, independence, expansionOne, expansionTwo, taskId),
            byId.getValue(verifier).dependsOn,
        )
        assertEquals(setOf(taskId, verifier), byId.getValue(downstreamId).dependsOn)

        listOf(skeleton, independence, expansionOne, expansionTwo, taskId, verifier).forEach { id ->
            assertEquals(condition, byId.getValue(id).condition)
        }
        assertEquals(CompoundInferencePolicy.Single, byId.getValue(taskId).compoundInferencePolicy)
        assertEquals(CompoundInferencePolicy.Single, byId.getValue(verifier).compoundInferencePolicy)
        assertTrue(byId.getValue(expansionOne).dependsOn.contains(independence))
    }
}
