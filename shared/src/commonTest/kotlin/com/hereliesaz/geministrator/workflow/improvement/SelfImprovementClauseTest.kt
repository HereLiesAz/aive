package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.WorkflowLibraryHost
import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.haiveBuiltInRoles
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.workflow.CodingWorkflowSuite
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.schedule.InMemoryWorkflowScheduleStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The amendment clause (docs/architecture/SELF_IMPROVEMENT.md §4): each point of it, as a test. */
class SelfImprovementClauseTest {
    private val loop = SelfImprovementLoop.definition()

    @Test
    fun theLoopIsAValidReadOnlyHiveThatTheSuiteShipsWithAnEvidenceFedWeeklySchedule() {
        assertEquals(emptyList(), WorkflowGraphValidator.validate(loop))
        assertEquals(emptyList(), LoopIntegrity.violations(loop))
        val entry = CodingWorkflowSuite.all().single { it.definition.id.value == SelfImprovementLoop.HIVE_ID }
        assertEquals(loop, entry.definition)
        assertNotNull(entry.schedule).also { assertTrue(it.withEvidence, "its objective is fed the swarm's evidence") }
        assertTrue(loop.tasks.any { it.roleId == HallMonitorRole.id }, "the Hall Monitor is the observer")
    }

    @Test
    fun point1_aLoopThatDropsItsOwnExaminationItsChallengeOrItsMetaRequirementIsNamedAsViolating() {
        val withoutExamine = loop.copy(tasks = loop.tasks.filterNot { it.id.value == "examine-loop" }.map { it.copy(dependsOn = it.dependsOn.filterNot { d -> d.value == "examine-loop" }.toSet()) })
        assertTrue(LoopIntegrity.violations(withoutExamine).any { "examine itself" in it })

        val withoutChallenge = loop.copy(tasks = loop.tasks.filterNot { it.id.value == "challenge-loop" }.map { it.copy(dependsOn = it.dependsOn.filterNot { d -> d.value == "challenge-loop" }.toSet()) })
        assertTrue(LoopIntegrity.violations(withoutChallenge).any { "challenged" in it })

        val withoutMeta = loop.copy(tasks = loop.tasks.map { if (it.id.value == "propose") it.copy(objective = "Write proposals about the swarm.") else it })
        assertTrue(LoopIntegrity.violations(withoutMeta).any { "about the loop" in it })
    }

    @Test
    fun point5_aLoopThatCouldHoldAnythingUpOrChangeAnythingIsNamedAsViolating() {
        val gate = TaskDefinition(
            id = TaskDefinitionId("gate"), name = "Wait", objective = "A person decides.", roleId = null,
            dependsOn = setOf(TaskDefinitionId("refine")), executor = TaskExecutor.HumanApproval("Wait"),
        )
        assertTrue(LoopIntegrity.violations(loop.copy(tasks = loop.tasks + gate)).any { "approval" in it })
        val approved = loop.copy(tasks = loop.tasks.map { if (it.id.value == "propose") it.copy(approvalPolicy = ApprovalPolicy.HumanApproval) else it })
        assertTrue(LoopIntegrity.violations(approved).any { "approval" in it })
        val push = TaskDefinition(
            id = TaskDefinitionId("push"), name = "Push", objective = "Push.", roleId = null,
            dependsOn = setOf(TaskDefinitionId("refine")), executor = TaskExecutor.RepositoryOperation("push"),
        )
        assertTrue(LoopIntegrity.violations(loop.copy(tasks = loop.tasks + push)).any { "only reads" in it })
    }

    @Test
    fun point4_aProposalIsAboutTheLoopByItsTargetAndKindNeverByItsWording() {
        assertTrue(ImprovementParser.isMeta(ImprovementKind.RoleInstruction, HallMonitorRole.id.value))
        assertTrue(ImprovementParser.isMeta(ImprovementKind.HiveStep, "${SelfImprovementLoop.HIVE_ID}/observe"))
        assertTrue(ImprovementParser.isMeta(ImprovementKind.Schedule, SelfImprovementLoop.SCHEDULE_ID))
        assertTrue(ImprovementParser.isMeta(ImprovementKind.LoopDefinition, "anything"))
        assertTrue(ImprovementParser.isMeta(ImprovementKind.EvidenceRule, "anything"))
        assertTrue(!ImprovementParser.isMeta(ImprovementKind.RoleInstruction, "qa-engineer"))
    }

    @Test
    fun point4_theApplierTreatsTheLoopsOwnPartsExactlyLikeAnyOthers() {
        val roles = haiveBuiltInRoles
        val hives = listOf(loop) + CodingWorkflowSuite.definitions().filter { it.id.value == "suite-bug-fix" }
        fun proposal(kind: ImprovementKind, target: String) = ImprovementProposal(
            id = "p", cycleRunId = "r", kind = kind, target = target, summary = "s", newText = "new text",
            expectedEffect = "e", undo = "u", meta = ImprovementParser.isMeta(kind, target), createdAtEpochMillis = 1L,
        )
        // The Hall Monitor's role and an ordinary role: the same kind of plan.
        assertIs<ImprovementApplier.Plan.RoleInstructions>(ImprovementApplier.plan(proposal(ImprovementKind.RoleInstruction, HallMonitorRole.id.value), roles, hives))
        assertIs<ImprovementApplier.Plan.RoleInstructions>(ImprovementApplier.plan(proposal(ImprovementKind.RoleInstruction, "qa-engineer"), roles, hives))
        // A step of the loop's own hive and a step of another hive: the same kind of plan.
        assertIs<ImprovementApplier.Plan.HiveStepObjective>(ImprovementApplier.plan(proposal(ImprovementKind.HiveStep, "${SelfImprovementLoop.HIVE_ID}/observe"), roles, hives))
        assertIs<ImprovementApplier.Plan.HiveStepObjective>(ImprovementApplier.plan(proposal(ImprovementKind.HiveStep, "suite-bug-fix/diagnose"), roles, hives))
        // Even the step that examines the loop can be rewritten.
        val plan = ImprovementApplier.plan(proposal(ImprovementKind.LoopDefinition, "${SelfImprovementLoop.HIVE_ID}/examine-loop"), roles, hives)
        val rewritten = ImprovementApplier.applyTo(loop, assertIs(plan))
        assertEquals("new text", rewritten.tasks.first { it.id.value == "examine-loop" }.objective)
    }

    @Test
    fun point3_theLoopIsAnAuthoredCopyTheKeeperCanEditAnyWayTheyLikeEvenToViolateTheClause() = runTest {
        val persistence = InMemoryWorkflowPersistence()
        val authored = SettingsAuthoredWorkflowStore(InMemorySettings())
        val host = WorkflowLibraryHost(persistence = persistence, storeService = null, authoredStore = authored, launchWorkflow = { _, _, _, _ -> }, onPackagesChanged = {})
        val schedules = InMemoryWorkflowScheduleStore()

        host.installCodingSuite(schedules, nowEpochMillis = 5L)

        val id = WorkflowDefinitionId(SelfImprovementLoop.HIVE_ID)
        assertTrue(id in authored.all(), "an authored hive, not a package or built-in")
        val schedule = schedules.all().single { it.id == SelfImprovementLoop.SCHEDULE_ID }
        assertTrue(schedule.withEvidence && !schedule.enabled, "evidence-fed, and off until the keeper turns it on")
        // The keeper may rewrite it however they like: the system saves a valid graph and does not refuse.
        val stripped = persistence.definitions.get(id)!!.let { d -> d.copy(tasks = d.tasks.filterNot { it.id.value == "challenge-loop" }.map { it.copy(dependsOn = it.dependsOn.filterNot { x -> x.value == "challenge-loop" }.toSet()) }) }
        host.saveAuthored(stripped)
        assertEquals(stripped, persistence.definitions.get(id))
        assertTrue(LoopIntegrity.violations(stripped).isNotEmpty(), "and the checker says what they gave up")
    }
}
