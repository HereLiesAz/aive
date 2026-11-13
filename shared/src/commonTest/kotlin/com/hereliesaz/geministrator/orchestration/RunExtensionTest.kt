package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.queen.qDefinition
import com.hereliesaz.geministrator.queen.qTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adding steps to a run that has begun: the pure rules (the engine call applies them, then the same validation any plan meets). */
class RunExtensionTest {
    private val gateId = RunExtension.GATE_STEP_ID
    private val role = BuiltInRoles.ImplementationEngineer.id.value

    private fun step(id: String, vararg after: String, approval: Boolean = false, name: String = id) =
        OrchestrationPlanStep(id, name, "Do $id", role, after.toList(), requiresHumanApproval = approval)

    private val gate = step(gateId, approval = true)
    private val current = listOf(gate, step("a", gateId))

    @Test
    fun aRunIsReadAsPlanStepsAndAStepWithNoRoleCannotBeRead() {
        val definition = qDefinition(
            qTask(gateId).copy(approvalPolicy = ApprovalPolicy.HumanApproval),
            qTask("a").copy(dependsOn = setOf(TaskDefinitionId(gateId))),
        )
        val steps = RunExtension.stepsOf(definition)!!
        assertEquals(listOf(gateId, "a"), steps.map { it.id })
        assertEquals(listOf(true, false), steps.map { it.requiresHumanApproval })
        assertEquals(listOf(gateId), steps[1].dependsOn)
        assertNull(RunExtension.stepsOf(qDefinition(qTask("x").copy(roleId = null))))
    }

    @Test
    fun onlyASwarmThatAskedForItAndWasApprovedCanBeExtended() {
        val extension = listOf(step("x", gateId))
        assertTrue(RunExtension.refusal(listOf(step("a")), extension, gateApproved = true)!!.contains("did not ask"))
        assertTrue(RunExtension.refusal(listOf(step(gateId), step("a")), extension, true)!!.contains("no approval"))
        assertTrue(RunExtension.refusal(current, extension, gateApproved = false)!!.contains("not approved"))
        assertNull(RunExtension.refusal(current, extension, gateApproved = true))
    }

    @Test
    fun theExtensionMustBeSomethingAndNotTooMuch() {
        assertTrue(RunExtension.refusal(current, emptyList(), true)!!.contains("nothing to add"))
        val many = (1..RunExtension.MAX_ADDED + 1).map { step("n$it", gateId) }
        assertTrue(RunExtension.refusal(current, many, true)!!.contains("At most"))
        assertNull(RunExtension.refusal(current, many.take(RunExtension.MAX_ADDED), true))
    }

    @Test
    fun aStepAlreadyInTheRunIsNeverReplacedOrRepeated() {
        assertTrue(RunExtension.refusal(current, listOf(step("a", gateId)), true)!!.contains("already used"))
        assertTrue(RunExtension.refusal(current, listOf(step("x", gateId), step("x", gateId)), true)!!.contains("already used"))
    }

    @Test
    fun everyAddedStepComesAfterThePlanApprovalAndDependsOnlyOnWhatExists() {
        assertTrue(RunExtension.refusal(current, listOf(step("x", "ghost")), true)!!.contains("not there"))
        // A step with no dependency, or one that only follows a step that is not behind the approval, is refused.
        val loose = listOf(gate, step("a", gateId), step("free"))
        assertTrue(RunExtension.refusal(loose, listOf(step("x")), true)!!.contains("not behind"))
        assertTrue(RunExtension.refusal(loose, listOf(step("x", "free")), true)!!.contains("not behind"))
        // Behind the approval directly, through a step already there, or through another added step.
        assertNull(RunExtension.refusal(loose, listOf(step("x", "a")), true))
        assertNull(RunExtension.refusal(current, listOf(step("x", gateId), step("y", "x")), true))
        assertNull(RunExtension.refusal(current, listOf(step("y", "x"), step("x", gateId)), true))
    }

    private fun inputs(available: Set<String>, objective: String = "Ship it") = RequirementInputs(
        objective = objective,
        objectiveExternal = false,
        roles = BuiltInRoles.all,
        availableRoleIds = available,
    )

    @Test
    fun theChecklistsRulesHoldForTheAddedSteps() {
        val ok = inputs(setOf(role))
        assertNull(RunExtension.introducedProblem(ok, current, listOf(step("x", gateId))))
        val ghost = OrchestrationPlanStep("g", "Ghost", "Do g", "ghost-role", listOf(gateId))
        assertTrue(RunExtension.introducedProblem(ok, current, listOf(ghost))!!.startsWith("R-roles"))
        assertTrue(RunExtension.introducedProblem(ok, current, listOf(step("x", gateId, name = "Timeline work")))!!.startsWith("R-words"))
        val tooMany = (1..12).map { step("n$it", gateId) }
        assertTrue(RunExtension.introducedProblem(ok, current, tooMany)!!.startsWith("R-structure"))
    }

    @Test
    fun aRuleTheRunAlreadyBrokeIsNotHeldAgainstTheExtension() {
        val already = listOf(gate, step("a", gateId, name = "Provider setup"))
        assertNull(RunExtension.introducedProblem(inputs(setOf(role)), already, listOf(step("x", gateId))))
    }

    @Test
    fun appendingLeavesEveryStepInTheRunExactlyAsItIs() {
        val gateTask = qTask(gateId).copy(approvalPolicy = ApprovalPolicy.HumanApproval)
        val a = qTask("a").copy(dependsOn = setOf(TaskDefinitionId(gateId)))
        val x = qTask("x").copy(dependsOn = setOf(TaskDefinitionId(gateId)))
        val live = qDefinition(gateTask, a)
        val built = qDefinition(gateTask, a, x)

        val result = assertIs<RunExtension.Appended.Ready>(RunExtension.append(live, built, setOf("x")))

        assertEquals(listOf(gateId, "a", "x"), result.definition.tasks.map { it.id.value })
        assertEquals(listOf(gateTask, a), result.definition.tasks.take(2))
        assertEquals(listOf(x), result.added)
    }

    @Test
    fun appendingRefusesAStepThatIsNotBuiltAndAGraphProblemItIntroduces() {
        val gateTask = qTask(gateId).copy(approvalPolicy = ApprovalPolicy.HumanApproval)
        val live = qDefinition(gateTask)
        assertIs<RunExtension.Appended.Refused>(RunExtension.append(live, qDefinition(gateTask), setOf("x")))
        val dangling = qTask("x").copy(dependsOn = setOf(TaskDefinitionId("ghost")))
        val refused = assertIs<RunExtension.Appended.Refused>(RunExtension.append(live, qDefinition(gateTask, dangling), setOf("x")))
        assertTrue("ghost" in refused.reason)
    }
}
