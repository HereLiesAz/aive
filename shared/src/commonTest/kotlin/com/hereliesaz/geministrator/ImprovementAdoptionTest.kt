package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalLedger
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalStatus
import com.hereliesaz.geministrator.memory.procedural.RoleObservation
import com.hereliesaz.geministrator.memory.procedural.RoleObservationKind
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.workflow.CodingWorkflowSuite
import com.hereliesaz.geministrator.workflow.improvement.ImprovementApplier
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalMirrors
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import com.hereliesaz.geministrator.workflow.improvement.SettingsImprovementStore
import com.hereliesaz.geministrator.workflow.schedule.InMemoryWorkflowScheduleStore
import com.hereliesaz.geministrator.workflow.schedule.ScheduleDecision
import com.hereliesaz.geministrator.workflow.schedule.ScheduleTicker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the Improvement screen's decisions do to the roles, the hives and the ledger (docs/architecture/SELF_IMPROVEMENT.md §3a). */
class ImprovementAdoptionTest {
    private val persistence = InMemoryWorkflowPersistence()
    private val authored = SettingsAuthoredWorkflowStore(InMemorySettings())
    private val host = WorkflowLibraryHost(persistence = persistence, storeService = null, authoredStore = authored, launchWorkflow = { _, _, _, _ -> }, onPackagesChanged = {})
    private val store = SettingsImprovementStore(InMemorySettings())
    private val roleLedger = RoleInstructionProposalLedger()
    private val qa = BuiltInRoles.QaEngineer
    private val engineer = BuiltInRoles.ImplementationEngineer

    private var failSave: String? = null
    private val saves = mutableListOf<Pair<String, String>>()

    private val adoption = ImprovementAdoption(
        store = store, host = host,
        saveRole = { roleId, instructions ->
            failSave?.let { error(it) }
            val role = host.installedRoles().first { it.id.value == roleId }
            persistence.roles.put(role.copy(instructions = instructions))
            saves += roleId to instructions
        },
        roleLedger = { roleLedger },
        now = { 5_000L },
    )

    private suspend fun role(id: String): RoleDefinition = host.installedRoles().first { it.id.value == id }

    private fun proposal(id: String, kind: ImprovementKind, target: String, text: String, source: ImprovementSource = ImprovementSource.Loop) = ImprovementProposal(
        id = id, cycleRunId = "c", kind = kind, target = target, summary = "Summary $id", newText = text, expectedEffect = "e", undo = "u",
        meta = false, createdAtEpochMillis = 1L, source = source,
    )

    private suspend fun waiting(vararg proposals: ImprovementProposal) = store.merge { it.copy(proposals = proposals.toList()) }

    private suspend fun statusOf(id: String) = store.state().proposals.first { it.id == id }

    private suspend fun installBugFix(): Pair<WorkflowDefinition, String> {
        val hive = host.saveAuthored(CodingWorkflowSuite.definitions().first { it.id.value == "suite-bug-fix" })
        return hive to hive.tasks.first().id.value
    }

    @Test
    fun m3_aDoubleTapOnAdoptAppliesOnceAndTheRevertStillRestoresTheOriginalText() = runTest {
        val (hive, task) = installBugFix()
        val original = hive.tasks.first { it.id.value == task }.objective
        waiting(proposal("p1", ImprovementKind.HiveStep, "suite-bug-fix/$task", "The new objective."))

        val results = listOf(async { adoption.adopt("p1") }, async { adoption.adopt("p1") }).awaitAll()

        assertEquals(1, results.count { it == AdoptionResult.Done }, results.toString())
        assertEquals(1, results.count { it == AdoptionResult.AlreadyDecided }, results.toString())
        val adopted = statusOf("p1")
        assertEquals(ProposalStatus.Adopted, adopted.status)
        assertEquals(original, adopted.appliedBefore, "what it replaced, not the text the first tap wrote")
        assertEquals("The new objective.", adopted.appliedAfter)

        assertEquals(AdoptionResult.Done, adoption.revert("p1"))
        assertEquals(original, host.entries().first { it.definition.id == hive.id }.definition.tasks.first { it.id.value == task }.objective)
        assertEquals(ProposalStatus.Reverted, statusOf("p1").status)
        assertEquals(AdoptionResult.AlreadyDecided, adoption.revert("p1"), "a revert is decided once, too")
    }

    @Test
    fun m2_aDecisionOnAProposalThatIsGoneOrAlreadyDecidedChangesNothingAndSaysSo() = runTest {
        val (hive, task) = installBugFix()
        waiting(proposal("p1", ImprovementKind.HiveStep, "suite-bug-fix/$task", "New."))
        store.decide("p1") { it.copy(status = ProposalStatus.Dismissed) }

        assertEquals(AdoptionResult.AlreadyDecided, adoption.adopt("p1"), "dismissed in the meantime")
        assertEquals(AdoptionResult.AlreadyDecided, adoption.adopt("evicted"), "no longer in the ledger")
        assertEquals(AdoptionResult.AlreadyDecided, adoption.dismiss("evicted"))
        assertEquals(hive.tasks.first().objective, host.entries().first { it.definition.id == hive.id }.definition.tasks.first().objective, "nothing was written")
    }

    @Test
    fun m7_aRoleEditedAfterTheScreenWasDrawnIsNotWrittenOverByAnAdoption() = runTest {
        waiting(
            proposal("p0", ImprovementKind.RoleInstruction, "architect", "An earlier decision on the same screen."),
            proposal("p1", ImprovementKind.RoleInstruction, "qa-engineer", "Always quote the failing command."),
        )
        assertEquals(AdoptionResult.Done, adoption.adopt("p0"))
        // The keeper edits the role on the Mind tab while the Improvement screen is still open.
        persistence.roles.put(qa.copy(instructions = "Edited by the keeper."))

        assertEquals(AdoptionResult.Done, adoption.adopt("p1"))

        val saved = saves.last().second
        assertEquals("Edited by the keeper.\n\n${ImprovementApplier.ADOPTED_MARK} Always quote the failing command.", saved, "the edit is kept, the lesson is added to it")
        assertEquals("Edited by the keeper.", statusOf("p1").appliedBefore)

        // A revert is judged against the text as it is now, so a later edit is not undone.
        persistence.roles.put(role("qa-engineer").copy(instructions = saved + "\nKeeper added this later."))
        assertEquals(AdoptionResult.RevertConflict, adoption.revert("p1"))
        assertEquals(ProposalStatus.Adopted, statusOf("p1").status)
    }

    @Test
    fun m7_aRunHistoryProposalDraftedAgainstTextTheKeeperSinceChangedIsSetAsideNotAppliedOverTheEdit() = runTest {
        val roles = host.installedRoles()
        roleLedger.observe(failures(), roles, nowEpochMillis = 100L)
        store.merge { state ->
            ProposalMirrors.roleHistory(state, roleLedger.state.value, 100L).let { it.copy(proposals = it.proposals + proposal("p0", ImprovementKind.RoleInstruction, "architect", "Earlier.")) }
        }
        val id = store.state().proposals.first { it.source == ImprovementSource.RoleHistory }.id
        assertEquals(AdoptionResult.Done, adoption.adopt("p0"))
        persistence.roles.put(engineer.copy(instructions = "Rewritten by the keeper."))

        assertEquals(AdoptionResult.Stale, adoption.adopt(id))

        assertEquals(1, saves.size, "only the earlier decision saved a role; the keeper's rewrite was not overwritten")
        assertEquals(ProposalStatus.Superseded, statusOf(id).status)
        assertEquals("Rewritten by the keeper.", role("implementation-engineer").instructions)
    }

    private fun failures() = listOf("a", "b").map { task ->
        RoleObservation(RoleObservationKind.Failure, engineer.id, "r1", task, 1, "Unit tests failed: 3 failures", 10L)
    }

    @Test
    fun m8_aSaveThatFailsLeavesTheProposalWaitingAndItsLessonsToBeProposedAgain() = runTest {
        val roles = host.installedRoles()
        roleLedger.observe(failures(), roles, nowEpochMillis = 100L)
        store.merge { ProposalMirrors.roleHistory(it, roleLedger.state.value, 100L) }
        val id = store.state().proposals.single().id

        failSave = "disk full"
        val failed = assertIs<AdoptionResult.SaveFailed>(adoption.adopt(id))
        assertEquals("disk full", failed.reason)
        assertEquals(ProposalStatus.Proposed, statusOf(id).status, "nothing was applied, so nothing is recorded as adopted")
        assertEquals(RoleInstructionProposalStatus.Pending, roleLedger.state.value.proposals.single().status, "and the lessons are not marked decided")

        failSave = null
        assertEquals(AdoptionResult.Done, adoption.adopt(id))
        assertEquals(ProposalStatus.Adopted, statusOf(id).status)
        assertEquals(RoleInstructionProposalStatus.Approved, roleLedger.state.value.proposals.single().status)
        assertEquals(1, saves.size)
        assertEquals(5_000L, statusOf(id).decidedAtEpochMillis)

        // And a loop-made role proposal whose save fails is also still waiting.
        waiting(proposal("p2", ImprovementKind.RoleInstruction, "qa-engineer", "x"))
        failSave = "nope"
        assertIs<AdoptionResult.SaveFailed>(adoption.adopt("p2"))
        assertEquals(ProposalStatus.Proposed, statusOf("p2").status)
    }

    @Test
    fun s4_anAdoptionThatWouldLeaveTheLoopBreakingItsOwnRulesIsRefusedWithTheReasonsAndOneThatKeepsThemIsApplied() = runTest {
        host.installCodingSuite(InMemoryWorkflowScheduleStore(), nowEpochMillis = 5L)
        val loop = host.entries().first { it.definition.id.value == SelfImprovementLoop.HIVE_ID }.definition
        val propose = loop.tasks.first { it.id.value == "propose" }
        waiting(
            proposal("drops", ImprovementKind.LoopDefinition, "${SelfImprovementLoop.HIVE_ID}/propose", "Write proposals about the swarm."),
            proposal("keeps", ImprovementKind.LoopDefinition, "${SelfImprovementLoop.HIVE_ID}/propose", propose.objective + " Prefer fewer, sharper proposals."),
        )

        val refused = assertIs<AdoptionResult.BreaksLoop>(adoption.adopt("drops"))
        assertTrue(refused.violations.any { "about the loop" in it }, refused.violations.toString())
        assertEquals(ProposalStatus.Proposed, statusOf("drops").status)
        assertEquals(propose.objective, host.entries().first { it.definition.id == loop.id }.definition.tasks.first { it.id.value == "propose" }.objective, "the loop was not changed")

        assertEquals(AdoptionResult.Done, adoption.adopt("keeps"))
        assertTrue(host.entries().first { it.definition.id == loop.id }.definition.tasks.first { it.id.value == "propose" }.objective.endsWith("sharper proposals."), "the loop stays improvable")
    }

    @Test
    fun m11_runCycleNowStartsTheLoopOnceAndDoesNotTurnItsScheduleOn() = runTest {
        val schedules = InMemoryWorkflowScheduleStore()
        host.installCodingSuite(schedules, nowEpochMillis = 5L)
        assertFalse(schedules.all().first { it.id == SelfImprovementLoop.SCHEDULE_ID }.enabled)
        val started = mutableListOf<String>()
        var now = 10_000L
        val ticker = ScheduleTicker(store = schedules, now = { now }, runActive = { false }, start = { started += it.workflowId }, log = {})

        assertTrue(ImprovementAdoption.runCycleNow(schedules))
        assertIs<ScheduleDecision.Start>(ticker.tick())

        assertEquals(listOf(SelfImprovementLoop.HIVE_ID), started)
        val schedule = schedules.all().first { it.id == SelfImprovementLoop.SCHEDULE_ID }
        assertFalse(schedule.enabled, "one press must not leave a weekly autonomous cycle switched on")
        assertFalse(schedule.runNowRequested)
        now += 30L * 24 * 3_600_000
        assertEquals(ScheduleDecision.Idle, ticker.tick())
        assertEquals(1, started.size)
        assertFalse(ImprovementAdoption.runCycleNow(InMemoryWorkflowScheduleStore()), "no loop schedule, nothing to run")
    }

    @Test
    fun dismissingARunHistoryProposalAlsoDismissesItInTheMechanismThatMadeItAndOnlyOnce() = runTest {
        roleLedger.observe(failures(), host.installedRoles(), nowEpochMillis = 100L)
        store.merge { ProposalMirrors.roleHistory(it, roleLedger.state.value, 100L) }
        val id = store.state().proposals.single().id

        assertEquals(AdoptionResult.Done, adoption.dismiss(id))
        assertEquals(RoleInstructionProposalStatus.Dismissed, roleLedger.state.value.proposals.single().status)
        assertEquals(ProposalStatus.Dismissed, statusOf(id).status)
        assertEquals(AdoptionResult.AlreadyDecided, adoption.dismiss(id))
        assertNotNull(statusOf(id).decidedAtEpochMillis)
    }
}
