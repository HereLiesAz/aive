package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.RefinementLevel
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RefinementLadderTest {
    private val ladder = CompoundInferencePolicy.RefinementLadder(
        levels = listOf(
            RefinementLevel(BuiltInRoles.ProductManager.id, "intent and responsibilities"),
            RefinementLevel(BuiltInRoles.Architect.id, "decomposition and acceptance criteria"),
        ),
        gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
        finalRoleId = BuiltInRoles.ImplementationEngineer.id,
    )

    private fun definition(policy: CompoundInferencePolicy = ladder): WorkflowDefinition {
        val triggerId = TaskDefinitionId("trigger")
        val taskId = TaskDefinitionId("work")
        return WorkflowDefinition(
            id = WorkflowDefinitionId("ladder"),
            name = "Refinement ladder",
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
                    name = "Work",
                    objective = "Deliver the bounded change",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    dependsOn = setOf(triggerId),
                    condition = TaskCondition.OnFailure(triggerId),
                    verificationPolicy = VerificationPolicy.Required(BuiltInRoles.QaEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    compoundInferencePolicy = policy,
                ),
                TaskDefinition(
                    id = TaskDefinitionId("downstream"),
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
    }

    @Test
    fun expanderChainsGatedLevelsBeforeTheOriginalTaskAndVerifier() {
        val expanded = RefinementLadderExpander.expand(definition(), BuiltInRoles.all)
        val byId = expanded.tasks.associateBy(TaskDefinition::id)
        val trigger = TaskDefinitionId("trigger")
        val taskId = TaskDefinitionId("work")
        val l1 = TaskDefinitionId("work--rl-level-1")
        val g1 = TaskDefinitionId("work--rl-gate-1")
        val l2 = TaskDefinitionId("work--rl-level-2")
        val g2 = TaskDefinitionId("work--rl-gate-2")
        val verifier = TaskDefinitionId("work--rl-verifier")

        assertEquals(8, expanded.tasks.size)
        assertEquals(setOf(trigger), byId.getValue(l1).dependsOn)
        assertEquals(setOf(trigger, l1), byId.getValue(g1).dependsOn)
        assertEquals(setOf(trigger, l1, g1), byId.getValue(l2).dependsOn)
        assertEquals(setOf(trigger, l1, g1, l2), byId.getValue(g2).dependsOn)
        assertEquals(setOf(trigger, l1, g1, l2, g2), byId.getValue(taskId).dependsOn)
        assertEquals(setOf(trigger, l1, g1, l2, g2, taskId), byId.getValue(verifier).dependsOn)
        assertEquals(setOf(taskId, verifier), byId.getValue(TaskDefinitionId("downstream")).dependsOn)

        listOf(l1, g1, l2, g2, taskId, verifier).forEach { id ->
            assertEquals(TaskCondition.OnFailure(trigger), byId.getValue(id).condition)
        }
        assertEquals(CompoundInferencePolicy.Single, byId.getValue(taskId).compoundInferencePolicy)
        assertEquals(BuiltInRoles.ProductManager.id, byId.getValue(l1).roleId)
        assertEquals(BuiltInRoles.AdversarialReviewer.id, byId.getValue(g1).roleId)
        assertEquals(BuiltInRoles.AdversarialReviewer.id, byId.getValue(g2).roleId)
        assertTrue("intent and responsibilities" in byId.getValue(l1).objective)
        assertTrue("intent and responsibilities" in byId.getValue(g1).objective)
    }

    @Test
    fun gatesAskForAVerdictAndStructuredLevelsCarryTheContract() {
        val structured = CompoundInferencePolicy.RefinementLadder(
            levels = listOf(
                RefinementLevel(BuiltInRoles.ProductManager.id, "intent", structured = true),
                RefinementLevel(BuiltInRoles.Architect.id, "steps"),
            ),
            gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
            finalRoleId = BuiltInRoles.ImplementationEngineer.id,
        )
        val byId = RefinementLadderExpander.expand(definition(structured), BuiltInRoles.all)
            .tasks.associateBy(TaskDefinition::id)
        val levelOne = byId.getValue(TaskDefinitionId("work--rl-level-1")).objective
        val levelTwo = byId.getValue(TaskDefinitionId("work--rl-level-2")).objective
        assertTrue("aive.ladder.level.v1" in levelOne)
        assertTrue("aive.ladder.level.v1" !in levelTwo)
        listOf("work--rl-gate-1", "work--rl-gate-2").forEach { gate ->
            assertTrue("VERDICT: approve" in byId.getValue(TaskDefinitionId(gate)).objective)
        }
        assertTrue("aive.ladder.level.v1" in byId.getValue(TaskDefinitionId("work--rl-gate-1")).objective)
    }

    @Test
    fun definitionWithoutALadderIsReturnedUnchanged() {
        val plain = definition(CompoundInferencePolicy.Single)
        assertEquals(plain, RefinementLadderExpander.expand(plain, BuiltInRoles.all))
    }

    @Test
    fun policyRejectsGateReviewerThatIsALevelOrTheFinalRole() {
        val level = RefinementLevel(BuiltInRoles.Architect.id, "decomposition")
        assertFailsWith<IllegalArgumentException> {
            CompoundInferencePolicy.RefinementLadder(
                levels = listOf(level),
                gateReviewerRoleId = BuiltInRoles.Architect.id,
                finalRoleId = BuiltInRoles.ImplementationEngineer.id,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CompoundInferencePolicy.RefinementLadder(
                levels = listOf(level),
                gateReviewerRoleId = BuiltInRoles.ImplementationEngineer.id,
                finalRoleId = BuiltInRoles.ImplementationEngineer.id,
            )
        }
    }

    @Test
    fun policyBoundsTheNumberOfLevelsAndRequiresAFocus() {
        val level = RefinementLevel(BuiltInRoles.Architect.id, "decomposition")
        assertFailsWith<IllegalArgumentException> {
            CompoundInferencePolicy.RefinementLadder(
                levels = emptyList(),
                gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
                finalRoleId = BuiltInRoles.ImplementationEngineer.id,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CompoundInferencePolicy.RefinementLadder(
                levels = List(CompoundInferencePolicy.MAX_REFINEMENT_LEVELS + 1) { level },
                gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
                finalRoleId = BuiltInRoles.ImplementationEngineer.id,
            )
        }
        assertFailsWith<IllegalArgumentException> { RefinementLevel(BuiltInRoles.Architect.id, " ") }
    }

    @Test
    fun expanderRejectsALevelRoleWithImplementationAuthority() {
        val bad = CompoundInferencePolicy.RefinementLadder(
            levels = listOf(RefinementLevel(BuiltInRoles.ImplementationEngineer.id, "everything")),
            gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
            finalRoleId = BuiltInRoles.ReleaseEngineer.id,
        )
        assertFailsWith<IllegalArgumentException> {
            RefinementLadderExpander.expand(definition(bad), BuiltInRoles.all)
        }
    }
}
