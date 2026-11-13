package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.WorkflowLibraryHost
import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TestDesignPolicy
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
        assertTrue(loop.tasks.any { it.roleId == HallMonitorRole.id }, "the Nanny is the observer")
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

    @Test
    fun point2_aCycleMustExamineTheLoopWithEvidenceAndAThinMetaNoChangeDoesNotCount() {
        fun cycle(at: Long, meta: Int = 0, noChange: String? = null) = CycleRecord(runId = "r$at", atEpochMillis = at, proposals = meta, metaProposals = meta, metaNoChange = noChange)
        val cites = "The loop's 4 steps finished in 3 of 3 cycles with 0 malformed lines, so no change is warranted."
        // A proposal about the loop is an examination; so is a reason of a sentence's worth that cites a figure.
        assertTrue(cycle(1, meta = 1).examinedLoop)
        assertTrue(cycle(1, noChange = cites).examinedLoop)
        // A bare gesture, a short reason and a long reason with no figure in it are not.
        assertTrue(!cycle(1).examinedLoop)
        assertTrue(!cycle(1, noChange = "Nothing to change.").examinedLoop)
        assertTrue(!cycle(1, noChange = "The loop is fine as it is and there is nothing in it that needs any change at all.").examinedLoop)
        assertEquals(60, MetaNoChange.MIN_CHARS)

        // LoopObligation says when the loop has not examined itself.
        val now = 100L * 24 * 3_600_000
        assertEquals(LoopObligation.Standing.NeverRan, LoopObligation.standing(ImprovementState(), now))
        assertEquals(LoopObligation.Standing.Examined, LoopObligation.standing(ImprovementState(cycles = listOf(cycle(now - 1_000, meta = 1))), now))
        assertEquals(LoopObligation.Standing.LastCycleSkippedItself, LoopObligation.standing(ImprovementState(cycles = listOf(cycle(now - 1_000))), now))
        assertEquals(LoopObligation.Standing.Overdue, LoopObligation.standing(ImprovementState(cycles = listOf(cycle(now - LoopObligation.MAX_GAP_MILLIS - 1, meta = 1))), now))
    }

    @Test
    fun point6_anAdoptionKeepsBeforeAndAfterSoItCanBeRevertedOnlyWhileTheTextIsStillAsAdopted() {
        val adopted = ImprovementProposal(
            id = "p", cycleRunId = "r", kind = ImprovementKind.HiveStep, target = "${SelfImprovementLoop.HIVE_ID}/observe", summary = "s",
            newText = "after", expectedEffect = "e", undo = "u", meta = false, createdAtEpochMillis = 1L,
            status = ProposalStatus.Adopted, appliedBefore = "before", appliedAfter = "after",
        )
        // Reverting is possible while the text is what adoption wrote, and the before text is kept to restore.
        assertTrue(ImprovementApplier.canRevert(adopted, currentText = "after"))
        assertEquals("before", adopted.appliedBefore)
        // A later edit is not undone, and a proposal that was never adopted has nothing to revert.
        assertTrue(!ImprovementApplier.canRevert(adopted, currentText = "after, then edited by the keeper"))
        assertTrue(!ImprovementApplier.canRevert(adopted.copy(status = ProposalStatus.Proposed), currentText = "after"))
        // The ledger never forgets an adopted proposal, so a revert stays possible however full it gets.
        val crowd = List(ProposalRetention.MAX + 5) { adopted.copy(id = "a$it") } + List(10) { adopted.copy(id = "d$it", status = ProposalStatus.Dismissed) }
        val kept = ProposalRetention.bound(crowd)
        assertTrue(kept.none { it.status == ProposalStatus.Dismissed }, "dismissed ones go first")
        assertEquals(ProposalRetention.MAX + 5, kept.count { it.status == ProposalStatus.Adopted })
    }

    private fun withTask(id: String, change: (TaskDefinition) -> TaskDefinition) = loop.copy(tasks = loop.tasks.map { if (it.id.value == id) change(it) else it })

    @Test
    fun m6_everyStepOfTheShippedLoopFailsTheRunRatherThanAskingAPersonAndALoopThatAsksOneIsNamedAsViolating() {
        assertTrue(loop.tasks.all { it.escalationPolicy == EscalationPolicy.FailWorkflow }, "no step can leave the run waiting on a person")
        for (id in listOf("examine-loop", "challenge-loop", "propose", "refine", "observe")) {
            val asking = withTask(id) { it.copy(escalationPolicy = EscalationPolicy.RequireHumanDecision) }
            assertTrue(LoopIntegrity.violations(asking).any { "wait on a person" in it && "'$id'" in it }, id)
        }
        val reassigning = withTask("propose") { it.copy(escalationPolicy = EscalationPolicy.Reassign(BuiltInRoles.ImplementationEngineer.id)) }
        assertTrue(LoopIntegrity.violations(reassigning).any { "'propose'" in it })
    }

    @Test
    fun s4_theLoopsIntegrityCoversEveryKindOfStepThatCouldChangeOrHoldSomethingUp() {
        fun extra(executor: TaskExecutor) = loop.copy(tasks = loop.tasks + TaskDefinition(
            id = TaskDefinitionId("extra"), name = "Extra", objective = "x", roleId = null, dependsOn = setOf(TaskDefinitionId("refine")), executor = executor,
        ))
        for (executor in listOf(
            TaskExecutor.TestRunner("echo"), TaskExecutor.ExternalService("svc"), TaskExecutor.Deployment("prod"),
            TaskExecutor.NestedWorkflow(WorkflowDefinitionId("other")), TaskExecutor.Script(com.hereliesaz.geministrator.domain.ScriptLanguage.Python, "print(1)", com.hereliesaz.geministrator.domain.ScriptRunner.LocalSandbox),
            TaskExecutor.Distributed(TaskExecutor.RoleAgent(BuiltInRoles.Architect.id)),
        )) {
            assertTrue(LoopIntegrity.violations(extra(executor)).any { "only reads" in it }, executor.toString())
        }
        assertTrue(LoopIntegrity.violations(withTask("propose") { it.copy(requiredRoleAuthority = RoleAuthority.ApproveRelease) }).any { "changes nothing" in it })
        assertTrue(LoopIntegrity.violations(withTask("propose") { it.copy(providerConstraints = ProviderConstraints.RequireCapabilities(setOf(AgentCapability.ShellExecution))) }).any { "'propose'" in it })
        assertTrue(LoopIntegrity.violations(loop.copy(testDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation)).any { "test-design" in it })
    }

    @Test
    fun s4_aStepRunByARoleThatCanWriteOrRunCommandsIsNamedEvenAtProposePlanAuthority() {
        val roles = haiveBuiltInRoles
        assertEquals(emptyList(), LoopIntegrity.violations(loop, roles), "the shipped loop uses roles that only read")

        val shellRole = BuiltInRoles.ProductManager.copy(capabilitiesRequired = setOf(AgentCapability.ShellExecution))
        val writer = BuiltInRoles.ProductManager.copy(authorities = setOf(RoleAuthority.ProposePlan, RoleAuthority.Implement))
        for (role in listOf(shellRole, writer)) {
            val installed = roles.map { if (it.id == role.id) role else it }
            assertTrue(LoopIntegrity.violations(loop, installed).any { "'propose'" in it && "can write" in it }, role.toString())
        }
        assertEquals(emptyList(), LoopIntegrity.violations(loop), "without the roles to look at, only the hive is judged")
    }

    @Test
    fun s4_theStructureIsCheckedNotTheWords_aStepCannotBeSkippedWithheldOrSelfGradedAndTheLoopHasOneEnd() {
        assertTrue(LoopIntegrity.violations(withTask("examine-loop") { it.copy(condition = TaskCondition.OnFailure(TaskDefinitionId("observe"))) }).any { "skipped" in it })
        assertTrue(LoopIntegrity.violations(withTask("challenge-loop") { it.copy(holdOutput = true) }).any { "withhold" in it })
        // The Hall Monitor grading its own overlay, or the examination waiting on its report.
        assertTrue(LoopIntegrity.violations(withTask("examine-loop") { it.copy(roleId = HallMonitorRole.id, executor = TaskExecutor.RoleAgent(HallMonitorRole.id)) }).any { "independently" in it })
        assertTrue(LoopIntegrity.violations(withTask("examine-loop") { it.copy(dependsOn = setOf(TaskDefinitionId("observe"))) }).any { "independently" in it })
        // The challenge by the same role that made the examination.
        assertTrue(LoopIntegrity.violations(withTask("challenge-loop") { it.copy(roleId = BuiltInRoles.Architect.id, executor = TaskExecutor.RoleAgent(BuiltInRoles.Architect.id)) }).any { "different role" in it })
        // Proposals that do not follow from the challenge, a last step that is not last, an objective that is only whitespace.
        assertTrue(LoopIntegrity.violations(withTask("propose") { it.copy(dependsOn = setOf(TaskDefinitionId("challenge-report"))) }).any { "challenge of the examination" in it })
        assertTrue(LoopIntegrity.violations(withTask("refine") { it.copy(dependsOn = emptySet()) }).any { "every other step" in it })
        assertTrue(LoopIntegrity.violations(withTask("examine-loop") { it.copy(objective = "   ") }).any { "examine itself" in it })
        // The word "loop" in a step that does nothing of the kind no longer passes: what counts is the step, not its text.
        assertTrue(LoopIntegrity.violations(withTask("examine-loop") { it.copy(objective = "loop loop loop", roleId = HallMonitorRole.id, executor = TaskExecutor.RoleAgent(HallMonitorRole.id)) }).isNotEmpty())
    }

    @Test
    fun s4_theProposeStepMustTellTheWriterTheLineFormsTheParserReadsAndTheLastStepMustToo() {
        val noGrammar = withTask("propose") { it.copy(objective = "Say something about the loop itself, or META-NO-CHANGE, with itself in it.") }
        assertTrue(LoopIntegrity.violations(noGrammar).any { "about the loop" in it })
        val noMetaKind = withTask("propose") { it.copy(objective = it.objective.replace("loop-definition", "x").replace("evidence-rule", "y")) }
        assertTrue(LoopIntegrity.violations(noMetaKind).any { "about the loop" in it })
        val finalWithoutForm = withTask("refine") { it.copy(objective = "Output a summary.") }
        assertTrue(LoopIntegrity.violations(finalWithoutForm).any { "proposal lines" in it })
        assertTrue(loop.tasks.single { it.id.value == "propose" }.objective.contains(SelfImprovementLoop.ESCAPING), "the writer is told how to write a pipe")
    }

    @Test
    fun s4_aScheduleDoesNotStartALoopThatNoLongerKeepsItsRulesAndSaysWhyButTheKeeperMayStillEditItFreely() {
        assertEquals(null, LoopIntegrity.startRefusal(loop, haiveBuiltInRoles))
        val broken = withTask("propose") { it.copy(escalationPolicy = EscalationPolicy.RequireHumanDecision) }
        val why = LoopIntegrity.startRefusal(broken, haiveBuiltInRoles)
        assertNotNull(why)
        assertTrue("was not started" in why && "'propose'" in why, why)
    }

    @Test
    fun f6b_independenceIsJudgedOnTheRoleDispatchUsesNotTheDeclaredRoleId() {
        val examine = loop.tasks.single { it.id.value == "examine-loop" }
        val challenge = loop.tasks.single { it.id.value == "challenge-loop" }
        fun with(vararg changed: TaskDefinition) = loop.copy(tasks = loop.tasks.map { t -> changed.firstOrNull { it.id == t.id } ?: t })

        // The step declares another role, but dispatch runs the Hall Monitor.
        val monitorRuns = with(examine.copy(executor = TaskExecutor.RoleAgent(HallMonitorRole.id)))
        assertTrue(LoopIntegrity.violations(monitorRuns).any { "independently of the Nanny" in it })

        // The challenger runs as the same role as the examiner although the declared ids differ.
        val sameRole = with(challenge.copy(executor = TaskExecutor.RoleAgent(examine.roleId!!)))
        assertTrue(LoopIntegrity.violations(sameRole).any { "different role" in it })

        // An ancestor that declares another role but runs as the Hall Monitor.
        val ancestorId = examine.dependsOn.firstOrNull()
        if (ancestorId != null) {
            val ancestor = loop.tasks.single { it.id == ancestorId }
            val sneaky = with(ancestor.copy(executor = TaskExecutor.RoleAgent(HallMonitorRole.id)))
            assertTrue(LoopIntegrity.violations(sneaky).any { "independently of the Nanny" in it })
        }
    }
}
