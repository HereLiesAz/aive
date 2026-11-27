package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delivery is not acceptance: what a producer earns depends on what its consumers did with the artifact. */
class TrophicTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = id, objective = "Do $id", roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    // plan (engineer) -> build (engineer) -> review (QA)
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("trophic"), name = "Trophic",
        tasks = listOf(task("plan", engineer), task("build", engineer, "plan"), task("review", qa, "build")),
    )

    private fun run(
        id: String = "run",
        build: TaskRunStatus = TaskRunStatus.Completed,
        review: TaskRunStatus = TaskRunStatus.Completed,
        reviewText: String? = null,
        reviewBlocked: Boolean = false,
    ): WorkflowRun {
        fun runOf(taskId: String, status: TaskRunStatus, provider: String, text: String? = null): Pair<TaskDefinitionId, TaskRun> {
            val t = definition.tasks.first { it.id.value == taskId }
            return t.id to TaskRun(
                id = TaskRunId("$id-$taskId"), taskDefinitionId = t.id, status = status, attempt = 1,
                assignedRoleId = t.roleId, assignedProviderId = AgentProviderId(provider),
                providerRunId = ProviderRunId("$id-session-$taskId"),
                artifacts = listOf(ArtifactRef(ArtifactId("$id-artifact-$taskId"),
                    if (text != null) ArtifactKind.Verification else ArtifactKind.Research,
                    TaskRunId("$id-$taskId"), taskId, textContent = text ?: "work", createdAtEpochMillis = 1L)),
                blockingReason = if (reviewBlocked && taskId == "review")
                    BlockingReason("gate-verdict-blocked", "turned back") else null,
            )
        }
        return WorkflowRun(
            id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(runOf("plan", TaskRunStatus.Completed, "a"), runOf("build", build, "a"),
                runOf("review", review, "b", reviewText)),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            roleSnapshot = roles, definitionSnapshot = definition,
        )
    }

    /** [delivered]: consumer task -> producer task whose artifact reached its request; null = no report at all. */
    private fun reports(run: WorkflowRun, delivered: Map<String, String?>) = run.taskRuns.values.mapNotNull { task ->
        val name = task.taskDefinitionId.value
        if (name !in delivered) return@mapNotNull null
        ProviderUsageRecorded(run.id, task.taskDefinitionId, requireNotNull(task.assignedProviderId),
            requireNotNull(task.providerRunId), model = "m", modelConsistent = true, occurredAtEpochMillis = 2L,
            deliveredArtifactIds = delivered[name]?.let { setOf("${run.id.value}-artifact-$it") } ?: emptySet())
    }

    private val fullDelivery = mapOf("build" to "plan", "review" to "build")
    private val noDelivery = mapOf("build" to null, "review" to null)

    @Test
    fun aCompletedConsumerAcceptsAReadAndAnApprovalOutranksIt() {
        val reading = run(review = TaskRunStatus.Running)
        val read = trophicEvidence(reading, reports(reading, fullDelivery)).getValue(engineer.id)
        assertEquals(TrophicRunEvidence(reads = 1, approvals = 0, barren = false), read)

        val approved = run(reviewText = "VERDICT: approve")
        val both = trophicEvidence(approved, reports(approved, fullDelivery)).getValue(engineer.id)
        assertEquals(TrophicRunEvidence(reads = 1, approvals = 1, barren = false), both)
        assertEquals(5_000 + 400 + 800, TrophicHistory().advance(approved, reports(approved, fullDelivery)).balances[engineer.id])
    }

    @Test
    fun deliveryAloneIsNeitherCreditNorStarvation() {
        // The consumer failed after reading: delivered, never consumed.
        val failed = run(build = TaskRunStatus.Failed, review = TaskRunStatus.Running)
        assertNull(trophicEvidence(failed, reports(failed, fullDelivery))[engineer.id])
        // No delivery report at all is unknown, not ignored.
        val unreported = run()
        assertEquals(emptyMap(), trophicEvidence(unreported, emptyList()))
        // A reviewer that completed without a stated verdict used the work; acceptance is unproven.
        val unstated = run(reviewText = null)
        val noPlan = reports(unstated, mapOf("review" to "build"))
        assertNull(trophicEvidence(unstated, noPlan)[engineer.id]?.takeIf { it.barren })
    }

    @Test
    fun outputNobodyWasHandedStarvesAndTheLastTaskIsExempt() {
        val r = run()
        val evidence = trophicEvidence(r, reports(r, mapOf("build" to "unrelated", "review" to "unrelated")))
        assertEquals(TrophicRunEvidence(0, 0, barren = true), evidence.getValue(engineer.id))
        assertNull(evidence[qa.id]) // review has no dependents: its output goes to the keeper
        assertEquals(4_000, TrophicHistory().advance(r, reports(r, mapOf("build" to "unrelated", "review" to "unrelated"))).balances[engineer.id])
    }

    @Test
    fun aReviewerTurningWorkBackIsUsedButNotFeeding() {
        val rejected = run(review = TaskRunStatus.Failed, reviewText = "VERDICT: reject", reviewBlocked = true)
        val evidence = trophicEvidence(rejected, reports(rejected, mapOf("build" to "unrelated", "review" to "build")))
        assertTrue(evidence.getValue(engineer.id).barren)
        val revised = run(reviewText = "VERDICT: revise")
        val mixed = trophicEvidence(revised, reports(revised, fullDelivery)).getValue(engineer.id)
        assertEquals(TrophicRunEvidence(reads = 1, approvals = 0, barren = false), mixed)
    }

    @Test
    fun historyIsIdempotentClampedAndStarvationReachesZero() {
        val feed = run("feed", reviewText = "VERDICT: approve")
        val fed = TrophicHistory(balances = mapOf(engineer.id to 9_500)).advance(feed, reports(feed, fullDelivery))
        assertEquals(10_000, fed.balances[engineer.id])
        assertEquals(fed, fed.advance(feed, reports(feed, fullDelivery)))

        var history = TrophicHistory()
        val states = (1..5).map { n ->
            val barren = run("barren-$n")
            history = history.advance(barren, reports(barren, mapOf("build" to "unrelated", "review" to "unrelated")))
            trophicStateOf(history.balances.getValue(engineer.id))
        }
        assertEquals(listOf(TrophicState.Hungry, TrophicState.Hungry, TrophicState.Hungry,
            TrophicState.Starving, TrophicState.Starved), states)
        assertEquals(0, history.balances[engineer.id])
        assertEquals(0, history.advance(run("barren-6"), emptyList()).balances[engineer.id]) // no evidence, no change
    }

    @Test
    fun onlyAHatchedEnabledAgentWithASpentReserveIsRetired() {
        val hatched = engineer.copy(id = RoleDefinitionId("child"), hatchedFromRunId = "run-1", enabled = true)
        val stillFed = hatched.copy(id = RoleDefinitionId("fed"))
        val disabled = hatched.copy(id = RoleDefinitionId("off"), enabled = false)
        val balances = mapOf(engineer.id to 0, hatched.id to 0, stillFed.id to 100, disabled.id to 0)
        assertEquals(listOf(hatched.id), starvedRoles(listOf(engineer, hatched, stillFed, disabled), balances).map { it.id })
        assertEquals(emptyList(), starvedRoles(listOf(hatched), emptyMap()))
    }

    @Test
    fun needsCarriesTheRoleReserveOnlyForAnAgent() {
        val r = run()
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("build"), definition, r, roles,
            reports(r, fullDelivery), trophic = mapOf(engineer.id to 4_200)))
        assertEquals(4_200, needs.trophicHundredths)
        assertNull(assertNotNull(creatureNeeds(TaskDefinitionId("build"), definition, r, roles)).trophicHundredths)
    }

    @Test
    fun aConsumersSupplyIsSharedAmongTheProducersItAccepted() {
        val dummy = BuiltInRoles.CrashTestDummy
        val planner = BuiltInRoles.Planner
        val shared = WorkflowDefinition(
            id = WorkflowDefinitionId("share"), name = "Share",
            tasks = listOf(task("p1", engineer), task("p2", dummy), task("c", planner, "p1", "p2")),
        )
        fun taskRun(id: String, role: RoleDefinition) = TaskDefinitionId(id) to TaskRun(
            id = TaskRunId("s-$id"), taskDefinitionId = TaskDefinitionId(id), status = TaskRunStatus.Completed, attempt = 1,
            assignedRoleId = role.id, assignedProviderId = AgentProviderId("a"), providerRunId = ProviderRunId("s-session-$id"),
            artifacts = listOf(ArtifactRef(ArtifactId("s-artifact-$id"), ArtifactKind.Research, TaskRunId("s-$id"), id,
                textContent = "work", createdAtEpochMillis = 1L)),
        )
        val settled = WorkflowRun(
            id = WorkflowRunId("share-run"), projectId = ProjectId("p"), workflowDefinitionId = shared.id, objective = "o",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(taskRun("p1", engineer), taskRun("p2", dummy), taskRun("c", planner)),
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            roleSnapshot = listOf(engineer, dummy, planner), definitionSnapshot = shared,
        )
        fun handed(vararg from: String) = ProviderUsageRecorded(settled.id, TaskDefinitionId("c"), AgentProviderId("a"),
            ProviderRunId("s-session-c"), model = "m", modelConsistent = true, occurredAtEpochMillis = 2L,
            deliveredArtifactIds = from.map { "s-artifact-$it" }.toSet())
        // Two producers read by one consumer split one read: 4 points between them, 2 each.
        val both = trophicEvidence(settled, listOf(handed("p1", "p2")))
        assertEquals(200, both.getValue(engineer.id).credit)
        assertEquals(200, both.getValue(dummy.id).credit)
        assertEquals(5_200, TrophicHistory().advance(settled, listOf(handed("p1", "p2"))).balances[engineer.id])
        // Handed only one, the consumer's whole read goes to it, and the one it passed over starves.
        val one = trophicEvidence(settled, listOf(handed("p1")))
        assertEquals(400, one.getValue(engineer.id).credit)
        assertTrue(one.getValue(dummy.id).barren)
        assertEquals(0, one.getValue(dummy.id).credit)
    }

    @Test
    fun aProducerWithNothingDeliverableWasNotPassedOver() {
        // plan produced only a URI: providers hand over text artifacts, so it could not have been shown to build.
        val base = run()
        val plan = TaskDefinitionId("plan")
        val source = base.taskRuns.getValue(plan)
        val uriOnly = base.copy(taskRuns = base.taskRuns + (plan to source.copy(artifacts = listOf(
            ArtifactRef(ArtifactId("run-artifact-plan"), ArtifactKind.Research, source.id, "plan", uri = "file:///plan.txt", createdAtEpochMillis = 1L)))))
        val buildTask = definition.tasks.first { it.id.value == "build" }
        val deliveries = reportedArtifactDeliveries(uriOnly, reports(uriOnly, mapOf("build" to "unrelated", "review" to "build")))
        assertEquals(TrophicOutcome.Unknown, trophicOutcome(uriOnly, plan, buildTask, deliveries, uriOnly.roleSnapshot.associateBy { it.id }))
        // With a text artifact that the consumer was not handed, it is passed over.
        val withText = base
        val passedOver = reportedArtifactDeliveries(withText, reports(withText, mapOf("build" to "unrelated", "review" to "build")))
        assertEquals(TrophicOutcome.Ignored, trophicOutcome(withText, plan, buildTask, passedOver, withText.roleSnapshot.associateBy { it.id }))
    }

    @Test
    fun theAntagonistNeverStarvesOrIsRetired() {
        val antagonist = BuiltInRoles.Antagonist.copy(hatchedFromRunId = "run-1", enabled = true)
        val hatched = engineer.copy(id = RoleDefinitionId("child"), hatchedFromRunId = "run-1", enabled = true)
        // Even spent and "hatched", the Antagonist is not on the list; the ordinary child is.
        assertEquals(listOf(hatched.id), starvedRoles(listOf(antagonist, hatched), mapOf(antagonist.id to 0, hatched.id to 0)).map { it.id })
    }
}
