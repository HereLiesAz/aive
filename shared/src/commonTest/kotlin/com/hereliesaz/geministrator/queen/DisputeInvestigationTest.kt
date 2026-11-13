package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.InboxEntryKind
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REVIEW_VERDICT_METADATA
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisputeInvestigationTest {
    private val dispute = Dispute(
        question = "Which approach?",
        options = listOf(
            DisputeOption("a", "Option A", safe = true),
            DisputeOption("b", "Option B", safe = true),
            DisputeOption("c", "Option C", safe = false),
        ),
        facts = mapOf("f1" to "A fact."),
    )

    // --- the investigator ------------------------------------------------------------------------------------------

    @Test
    fun researchReturnsAnIdAndKeepsItsFindings() = runBlocking {
        val investigator = DisputeInvestigator(research = { listOf("[untrusted reference] one", " ", "[untrusted reference] two") })
        val id = investigator.launch(InvestigationRequest(InvestigationKind.Research, "What is X?", emptyList()))
        assertEquals(listOf("[untrusted reference] one", "[untrusted reference] two"), investigator.findingsOf(id!!))
    }

    @Test
    fun researchThatFindsNothingCannotInvestigate() = runBlocking {
        val none = DisputeInvestigator(research = { null })
        val empty = DisputeInvestigator(research = { emptyList() })
        val request = InvestigationRequest(InvestigationKind.Research, "What is X?", emptyList())
        assertNull(none.launch(request))
        assertNull(empty.launch(request))
        assertNull(DisputeInvestigator().launch(request))
    }

    @Test
    fun aTestIsHandedToTheTestLauncherAndLeavesNoFindings() = runBlocking {
        val seen = mutableListOf<InvestigationRequest>()
        val investigator = DisputeInvestigator(test = { seen += it; "run-1" })
        val request = InvestigationRequest(InvestigationKind.Test, "Which is faster?", listOf("a", "b"))
        assertEquals("run-1", investigator.launch(request))
        assertEquals(listOf(request), seen)
        assertTrue(investigator.findingsOf("run-1").isEmpty())
        assertNull(DisputeInvestigator(research = { listOf("x") }).launch(request))
    }

    @Test
    fun aFailingLauncherIsCannotInvestigateButCancellationPropagates() = runBlocking {
        val request = InvestigationRequest(InvestigationKind.Test, "m", listOf("a", "b"))
        assertNull(DisputeInvestigator(test = { error("boom") }).launch(request))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            DisputeInvestigator(test = { throw kotlinx.coroutines.CancellationException("stop") }).launch(request)
        }
        Unit
    }

    // --- the test swarm --------------------------------------------------------------------------------------------

    @Test
    fun theTestSwarmTriesOnlySafeOptionsAndReportsWithAReviewer() {
        val request = InvestigationRequest(InvestigationKind.Test, "Which is faster?", listOf("a", "b", "c"))
        val definition = disputeTestDefinition(WorkflowDefinitionId("t"), dispute, request, BuiltInRoles.Planner, BuiltInRoles.Reviewer)!!
        assertEquals(3, definition.tasks.size)
        val tries = definition.tasks.filter { it.roleId == BuiltInRoles.Planner.id }
        assertEquals(2, tries.size)
        assertTrue(tries.all { it.dependsOn.isEmpty() })
        assertTrue(tries[0].objective.contains("Option A") && tries[1].objective.contains("Option B"))
        assertTrue(definition.tasks.none { it.objective.contains("Option C") })
        val report = definition.tasks.single { it.roleId == BuiltInRoles.Reviewer.id }
        assertEquals(tries.map { it.id }.toSet(), report.dependsOn)
        assertEquals(IntegrationPolicy.Manual, definition.integrationPolicy)
        assertEquals(TaskDefinitionId("dispute-test-report"), report.id)
    }

    @Test
    fun theTestSwarmNeedsTwoSafeListedOptions() {
        fun build(vararg ids: String) = disputeTestDefinition(
            WorkflowDefinitionId("t"), dispute, InvestigationRequest(InvestigationKind.Test, "m", ids.toList()),
            BuiltInRoles.Planner, BuiltInRoles.Reviewer,
        )
        assertNull(build("a"))
        assertNull(build("a", "c"))
        assertNull(build("a", "a"))
        assertNull(build("a", "zzz"))
    }

    // --- the Nanny and the Antagonist ------------------------------------------------------------------------------

    private val nannyRole = HallMonitorRole.id
    private val antagonist = BuiltInRoles.Antagonist.id
    private val definition = qDefinition(qTask("work"), qTask("nanny", nannyRole), qTask("review", antagonist))

    private fun artifact(id: String, kind: ArtifactKind, task: String, at: Long, metadata: Map<String, String>) =
        ArtifactRef(id = ArtifactId(id), kind = kind, taskRunId = TaskRunId(task), label = id, textContent = "x", metadata = metadata, createdAtEpochMillis = at)

    private fun run(verdict: String?) = qRun(
        qTaskRun("work", TaskRunStatus.Running),
        qTaskRun(
            "nanny", TaskRunStatus.Completed, role = nannyRole,
            artifacts = listOf(artifact("report", ArtifactKind.HallMonitorReport, "r-nanny", 10, mapOf(HALL_MONITOR_REPORT_ID_METADATA to "rep"))),
        ),
        qTaskRun(
            "review", TaskRunStatus.Completed, role = antagonist,
            artifacts = listOfNotNull(
                verdict?.let {
                    artifact("review", ArtifactKind.HallMonitorReview, "r-review", 20, mapOf(HALL_MONITOR_REPORT_ID_METADATA to "rep", HALL_MONITOR_REVIEW_VERDICT_METADATA to it))
                },
            ),
        ),
    )

    @Test
    fun aRejectedNannyReportIsADisputeBetweenTheNannyAndTheAntagonist() {
        val case = nannyDispute(definition, run("reject"))!!
        assertEquals("r1:rep", case.key)
        assertEquals(listOf(nannyRole.value, antagonist.value), case.parties.map { it.id })
        assertTrue(case.dispute.options.any { !it.safe })
        assertEquals(2, case.dispute.options.count { it.safe })
    }

    @Test
    fun theNormalBackAndForthIsNotADispute() {
        assertNull(nannyDispute(definition, run("revise")))
        assertNull(nannyDispute(definition, run("pass")))
        assertNull(nannyDispute(definition, run(null)))
        assertNull(nannyDispute(qDefinition(qTask("a")), qRun(qTaskRun("a", TaskRunStatus.Running))))
    }

    // --- adopting a specialist -------------------------------------------------------------------------------------

    private val adoption = SpecialistAdoption("planner-writing", "planner", listOf("Keep steps under five."))
    private fun approval(
        kind: InboxEntryKind = InboxEntryKind.Question,
        approved: Boolean = true,
        of: SpecialistAdoption = adoption,
    ) = SpecialistApproval(of, kind, approved, 5L)

    private fun adopt(approval: SpecialistApproval?, applied: MutableList<SpecialistAdoption> = mutableListOf(), ok: Boolean = true, of: SpecialistAdoption = adoption) =
        runBlocking { adoptSpecialist(of, approval) { applied += it; ok } }

    @Test
    fun withoutARecordedApprovalSheRefusesAndAppliesNothing() {
        val applied = mutableListOf<SpecialistAdoption>()
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NoApproval), adopt(null, applied))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun aDeclinedApprovalOrOneForAnotherRequestOrAnotherKindOfEntryRefuses() {
        val applied = mutableListOf<SpecialistAdoption>()
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NotApproved), adopt(approval(approved = false), applied))
        assertEquals(
            AdoptionResult.Refused(AdoptionRefusal.ApprovalIsForAnother),
            adopt(approval(of = adoption.copy(lessons = listOf("Something else."))), applied),
        )
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NotAMessagesEntry), adopt(approval(kind = InboxEntryKind.BudgetSpent), applied))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun theGenericRoleIsNeverReplacedAndALessonIsNeeded() {
        val applied = mutableListOf<SpecialistAdoption>()
        val same = adoption.copy(roleId = "planner")
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.WouldReplaceTheGeneric), adopt(approval(of = same), applied, of = same))
        val bare = adoption.copy(lessons = listOf(" "))
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NothingToCarry), adopt(approval(of = bare), applied, of = bare))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun aRecordedApprovalLetsHerAdoptAndAFailedApplyIsARefusal() {
        val applied = mutableListOf<SpecialistAdoption>()
        assertIs<AdoptionResult.Adopted>(adopt(approval(), applied))
        assertEquals(listOf(adoption), applied)
        listOf(InboxEntryKind.TaskApproval, InboxEntryKind.PlanApproval, InboxEntryKind.NestedApproval).forEach {
            assertIs<AdoptionResult.Adopted>(adopt(approval(kind = it)))
        }
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.ApplyFailed), adopt(approval(), ok = false))
    }
}
