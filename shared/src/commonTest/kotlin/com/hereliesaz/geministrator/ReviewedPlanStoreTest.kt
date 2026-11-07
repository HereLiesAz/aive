package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.PendingPlanLaunch
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.ReviewedPlanStore
import com.hereliesaz.geministrator.orchestration.StoredPlanRound
import com.hereliesaz.geministrator.orchestration.planInputsFingerprint
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewedPlanStoreTest {
    private val pm = BuiltInRoles.ProductManager
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val reviewer = BuiltInRoles.AdversarialReviewer

    private fun fingerprint(
        objective: String = "Ship it",
        repository: RepositoryRef? = null,
        parents: List<String> = emptyList(),
        planners: List<com.hereliesaz.geministrator.domain.RoleDefinition> = listOf(pm, engineer),
        review: com.hereliesaz.geministrator.domain.RoleDefinition = reviewer,
    ) = planInputsFingerprint(objective, repository, parents, planners, review)

    @Test
    fun theSameInputsAlwaysGiveTheSameFingerprintWhateverOrderTheyComeIn() {
        val a = fingerprint(planners = listOf(pm, engineer), parents = listOf("run-1", "run-2"))
        val b = fingerprint(planners = listOf(engineer, pm), parents = listOf("run-2", "run-1"))
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertEquals(fingerprint(objective = "  Ship it  "), fingerprint(), "Whitespace around the objective is not a change")
    }

    @Test
    fun anythingThePlanIsMadeFromChangesTheFingerprint() {
        val base = fingerprint()
        val repo = RepositoryRef(owner = "owner", name = "repo", source = RepositorySource.GitHub)
        assertNotEquals(base, fingerprint(objective = "Ship it now"))
        assertNotEquals(base, fingerprint(repository = repo))
        assertNotEquals(fingerprint(repository = repo), fingerprint(repository = repo.copy(name = "other")))
        assertNotEquals(base, fingerprint(parents = listOf("run-1")))
        assertNotEquals(base, fingerprint(planners = listOf(pm)))
        assertNotEquals(base, fingerprint(planners = listOf(pm, engineer.copy(instructions = engineer.instructions + " Also be careful."))))
        assertNotEquals(base, fingerprint(review = reviewer.copy(instructions = "Approve everything.")))
        assertNotEquals(base, fingerprint(review = BuiltInRoles.CodeReviewer))
    }

    private fun pending(now: Long, reasons: String = "No tests.") = PendingPlanLaunch(
        fingerprint = "f",
        objective = "Ship it",
        projectName = "Project",
        packet = OrchestrationPacket(objective = "Ship it", availableAgents = emptyList(), instruction = "Plan."),
        plan = OrchestrationPlan(listOf(OrchestrationPlanStep("a", "A", "Do a", pm.id.value))),
        rounds = listOf(StoredPlanRound(OrchestrationPlan(emptyList()), approved = false, reasons = reasons)),
        reviewerRoleId = reviewer.id.value,
        reviewerName = reviewer.name,
        createdAtEpochMillis = now,
    )

    @Test
    fun aPendingDecisionSurvivesARestartEvenPastDesktopsValueLimit() {
        val inner = InMemorySettings()
        val limited = object : Settings by inner {
            override fun putString(key: String, value: String) {
                require(value.length <= 8_192) { "Value too long" }
                inner.putString(key, value)
            }
        }
        val long = "Because. ".repeat(1_500)
        ReviewedPlanStore(limited).setPending(pending(now = 1_000, reasons = long))

        val restored = ReviewedPlanStore(limited)
        assertTrue(restored.hasPending())
        assertEquals(long, restored.pending(nowEpochMillis = 2_000)?.rounds?.single()?.reasons)

        restored.clearPending()
        assertFalse(ReviewedPlanStore(limited).hasPending())
    }

    @Test
    fun aDecisionLeftWaitingForTooLongIsNotOffered() {
        val store = ReviewedPlanStore(InMemorySettings())
        store.setPending(pending(now = 1_000))

        assertTrue(store.pending(1_000 + ReviewedPlanStore.PENDING_TTL_MILLIS) != null)
        assertNull(store.pending(1_000 + ReviewedPlanStore.PENDING_TTL_MILLIS + 1))
        assertNull(store.pending(500), "A clock that went backwards is not a fresh decision")
    }

    // ---- the launch ----------------------------------------------------------------------------------------

    private class CountingPlanner(private val plan: OrchestrationPlan) : OrchestrationAgentRuntime {
        var planned = 0
        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = plan.also { planned++ }
        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = plan
    }

    private val onePlan = OrchestrationPlan(listOf(OrchestrationPlanStep("one", "One", "Do one", pm.id.value)))

    private suspend fun launch(
        runtime: ApplicationRuntime,
        planner: OrchestrationAgentRuntime,
        store: ReviewedPlanStore,
        objective: String = "Ship it",
        confirm: suspend (PlanDecision) -> Boolean,
    ) = runtime.launchOrchestratedWorkflow(
        projectName = "Plan",
        objective = objective,
        orchestrationRuntime = planner,
        planReviewer = { PlanVerdict.Approve("Fine.") },
        confirmPlan = confirm,
        planStore = store,
    )

    @Test
    fun aRestartDuringTheDecisionBringsTheSamePlanBackAndStartingItMakesTheRun() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            // The app is closed while the keeper is deciding: the launch is cancelled without an answer.
            assertFailsWith<CancellationException> {
                launch(first, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { throw CancellationException("closed") }
            }
            assertTrue(ReviewedPlanStore(settings).hasPending())
            assertTrue(persistence.definitions.all().isEmpty(), "Nothing was started")

            // The next launch of the app, nothing changed.
            val second = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            var shown: PlanDecision? = null
            val offered = second.resumePendingPlanLaunch(ReviewedPlanStore(settings)) { decision -> shown = decision; true }

            assertTrue(offered)
            assertEquals("One", shown?.outcome?.plan?.steps?.single()?.name)
            assertEquals("Ship it", shown?.objective)
            assertTrue(shown?.outcome?.approved == true)
            assertEquals(1, persistence.definitions.all().size, "The keeper started it")
            assertFalse(ReviewedPlanStore(settings).hasPending(), "Answered: not offered again")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDecisionWhoseInputsHaveChangedIsDroppedNotOffered() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            assertFailsWith<CancellationException> {
                launch(first, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { throw CancellationException("closed") }
            }
            // The reviewer's instructions were edited while the app was closed.
            persistence.roles.put(reviewer.copy(instructions = "Approve everything."))

            val second = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            var asked = false
            val offered = second.resumePendingPlanLaunch(ReviewedPlanStore(settings)) { asked = true; true }

            assertFalse(offered)
            assertFalse(asked)
            assertTrue(persistence.definitions.all().isEmpty())
            assertFalse(ReviewedPlanStore(settings).hasPending(), "Dropped")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun decliningForgetsTheDecision() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            launch(runtime, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { false }

            assertFalse(ReviewedPlanStore(settings).hasPending())
            assertTrue(persistence.definitions.all().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun launchingTheSameObjectiveAgainIsPlannedReviewedAndDecidedAfreshEveryTime() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val planner = CountingPlanner(onePlan)
        var asked = 0
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            repeat(2) { launch(runtime, planner, ReviewedPlanStore(settings)) { asked++; true } }

            assertEquals(2, planner.planned, "A new launch is a new plan")
            assertEquals(2, asked, "and a new decision")
        } finally {
            scope.cancel()
        }
    }
}
