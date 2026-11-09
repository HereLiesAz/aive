package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.haiveBuiltInRoles
import com.hereliesaz.geministrator.workflow.CodingWorkflowSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImprovementApplierTest {
    private val loop = SelfImprovementLoop.definition()
    private val bugFix = CodingWorkflowSuite.definitions().first { it.id.value == "suite-bug-fix" }
    private val hives = listOf(loop, bugFix)

    private fun proposal(kind: ImprovementKind, target: String, text: String) = ImprovementProposal(
        id = "p", cycleRunId = "r", kind = kind, target = target, summary = "s", newText = text,
        expectedEffect = "e", undo = "u", meta = ImprovementParser.isMeta(kind, target), createdAtEpochMillis = 1L,
    )

    @Test
    fun m9_aHiveStepProposalShowsTheObjectiveItReplacesNextToTheTextThatReplacesIt() {
        val step = bugFix.tasks.first()
        val preview = assertNotNull(ImprovementApplier.preview(proposal(ImprovementKind.HiveStep, "suite-bug-fix/${step.id.value}", "A shorter objective."), haiveBuiltInRoles, hives))

        assertEquals(step.objective, preview.before, "the whole objective that would be dropped, not only the new text")
        assertEquals("A shorter objective.", preview.after)
        assertEquals(emptyList(), preview.loopViolations, "an ordinary hive has no loop rules to break")
    }

    @Test
    fun m9_aProposalThatIsNotAReplacementHasNoPreviewAndOneThatCannotApplyHasNone() {
        assertNull(ImprovementApplier.preview(proposal(ImprovementKind.RoleInstruction, "qa-engineer", "t"), haiveBuiltInRoles, hives))
        assertNull(ImprovementApplier.preview(proposal(ImprovementKind.HiveStep, "suite-bug-fix/nope", "t"), haiveBuiltInRoles, hives))
        assertNull(ImprovementApplier.preview(proposal(ImprovementKind.Other, "x", "t"), haiveBuiltInRoles, hives))
    }

    @Test
    fun s4_theCardWarnsWhenAProposalWouldDropTheRequirementThatTheLoopExamineItselfAndAllowsOneThatKeepsIt() {
        val propose = loop.tasks.single { it.id.value == "propose" }
        val dropsMeta = assertNotNull(ImprovementApplier.preview(proposal(ImprovementKind.HiveStep, "${SelfImprovementLoop.HIVE_ID}/propose", "Write proposals about the swarm."), haiveBuiltInRoles, hives))
        assertTrue(dropsMeta.loopViolations.any { "about the loop" in it }, dropsMeta.loopViolations.toString())
        assertEquals(propose.objective, dropsMeta.before)

        val keeps = assertNotNull(ImprovementApplier.preview(proposal(ImprovementKind.HiveStep, "${SelfImprovementLoop.HIVE_ID}/propose", propose.objective + " Prefer fewer, sharper proposals."), haiveBuiltInRoles, hives))
        assertEquals(emptyList(), keeps.loopViolations, "the loop stays improvable: a change that keeps its rules is allowed")
    }

    @Test
    fun m24_adoptedImprovementsCannotGrowARolesInstructionsWithoutLimit() {
        val role = BuiltInRoles.QaEngineer
        val room = ImprovementApplier.MAX_ADOPTED_CHARS
        val first = ImprovementApplier.plan(proposal(ImprovementKind.RoleInstruction, "qa-engineer", "x".repeat(1_000)), listOf(role), hives)
        assertIs<ImprovementApplier.Plan.RoleInstructions>(first)

        // Already carrying nearly the cap in adopted lines: one more is refused with the reason.
        val laden = role.copy(instructions = role.instructions + "\n\n${ImprovementApplier.ADOPTED_MARK} " + "y".repeat(room - 50))
        val refused = ImprovementApplier.plan(proposal(ImprovementKind.RoleInstruction, "qa-engineer", "z".repeat(200)), listOf(laden), hives)
        val cannot = assertIs<ImprovementApplier.Plan.Cannot>(refused)
        assertTrue("past $room" in cannot.why, cannot.why)
        assertEquals(0, ImprovementApplier.adoptedChars(role.instructions))
    }
}
