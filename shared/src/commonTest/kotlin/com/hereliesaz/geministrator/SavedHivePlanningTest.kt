package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.ReviewedPlanStore
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedHivePlanningTest {
    private val pm = BuiltInRoles.ProductManager
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val orchestrator = BuiltInRoles.Orchestrator

    private fun agent(id: String, role: com.hereliesaz.geministrator.domain.RoleDefinition, vararg after: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id.replaceFirstChar(Char::uppercase),
        objective = "Do $id",
        roleId = role.id,
        executor = TaskExecutor.RoleAgent(role.id),
        dependsOn = after.mapTo(linkedSetOf(), ::TaskDefinitionId),
        environmentPlanningPolicy = com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy.NotRequired,
    )

    private val roughHive = WorkflowDefinition(
        id = WorkflowDefinitionId("saved-hive"),
        name = "Saved hive",
        tasks = listOf(
            agent("plan", orchestrator),
            agent("spec", pm, "plan").copy(retryPolicy = RetryPolicy(maxAttempts = 7)),
            agent("build", engineer, "spec").copy(approvalPolicy = ApprovalPolicy.HumanApproval),
        ),
    )

    // ---- what counts as a rough design --------------------------------------------------------------------

    @Test
    fun aHiveWithAnOrchestratorIsARoughDesignWhoseOtherTasksAreItsSteps() {
        val design = assertNotNull(savedHiveRoughDesign(roughHive))
        assertEquals(setOf(TaskDefinitionId("plan")), design.orchestratorIds)
        assertEquals(listOf("spec", "build"), design.steps.map { it.id })
        assertEquals(emptyList(), design.steps[0].dependsOn, "A dependency on the Orchestrator is the plan itself")
        assertEquals(listOf("spec"), design.steps[1].dependsOn)
        assertTrue(design.steps[1].requiresHumanApproval)
    }

    @Test
    fun aHiveThePlannerCannotCarryLaunchesAsItAlwaysDid() {
        assertNull(savedHiveRoughDesign(roughHive.copy(tasks = roughHive.tasks.drop(1))), "No Orchestrator")
        assertNull(
            savedHiveRoughDesign(roughHive.copy(tasks = roughHive.tasks.map { if (it.id.value == "spec") it.copy(condition = TaskCondition.OnFailure(TaskDefinitionId("build"))) else it })),
            "A task that waits on an outcome would be dropped from a plan",
        )
        assertNull(
            savedHiveRoughDesign(roughHive.copy(tasks = roughHive.tasks.map { if (it.id.value == "spec") it.copy(approvalPolicy = ApprovalPolicy.RoleApproval(com.hereliesaz.geministrator.domain.RoleAuthority.ApprovePlan)) else it })),
            "Only the keeper's approval is a plan step",
        )
    }

    // ---- the planned hive ---------------------------------------------------------------------------------

    private val packet = OrchestrationPacket(
        objective = "Ship it",
        currentState = "rough-design",
        availableAgents = listOf(pm, engineer).map { com.hereliesaz.geministrator.orchestration.OrchestrationRole(it.id.value, it.name, emptyList()) },
        instruction = "Plan.",
    )

    @Test
    fun theRunIsOfThePlannedHiveUnderANewIdKeepingWhatTheDesignerSetOnAKeptStep() {
        val plan = OrchestrationPlan(
            listOf(
                OrchestrationPlanStep("spec", "Spec it", "Write the spec", pm.id.value),
                OrchestrationPlanStep("build", "Build it", "Build", engineer.id.value, dependsOn = listOf("spec"), requiresHumanApproval = true),
                OrchestrationPlanStep("extra", "Extra", "A step the planner added", engineer.id.value, dependsOn = listOf("build")),
            ),
        )
        val planned = plannedHiveDefinition(roughHive, plan, packet, listOf(pm, engineer), WorkflowDefinitionId("saved-hive--planned-1"))

        assertEquals("saved-hive--planned-1", planned.id.value)
        assertEquals(listOf("spec", "build", "extra"), planned.tasks.map { it.id.value }, "The Orchestrator's task has done its work")
        assertEquals(7, planned.tasks.first { it.id.value == "spec" }.retryPolicy.maxAttempts, "A kept step keeps the rest of what was drawn")
        assertEquals("Spec it", planned.tasks.first { it.id.value == "spec" }.name, "but takes the plan's wording")
        assertEquals(RetryPolicy(), planned.tasks.first { it.id.value == "extra" }.retryPolicy, "A new step is made as any planned step is")
        assertEquals(roughHive.id.value, "saved-hive", "The saved design is left as drawn")
    }

    // ---- the launch ---------------------------------------------------------------------------------------

    private class CountingPlanner(private val plan: OrchestrationPlan) : OrchestrationAgentRuntime {
        var planned = 0
        var lastPacket: OrchestrationPacket? = null
        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = plan.also { planned++; lastPacket = packet }
        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = plan
    }

    private val onePlan = OrchestrationPlan(listOf(OrchestrationPlanStep("spec", "Spec it", "Write the spec", pm.id.value)))

    private suspend fun launch(
        runtime: ApplicationRuntime,
        planner: OrchestrationAgentRuntime,
        store: ReviewedPlanStore,
        definition: WorkflowDefinition = roughHive,
        confirm: suspend (PlanDecision) -> Boolean,
    ) = runtime.launchSavedWorkflow(
        definition = definition,
        objective = "Ship it",
        planner = planner,
        planReviewer = { PlanVerdict.Approve("Fine.") },
        confirmPlan = confirm,
        planStore = store,
    )

    @Test
    fun aSavedRoughDesignIsPlannedReviewedAndDecidedBeforeAnyRunExists() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            val planner = CountingPlanner(onePlan)
            var shown: PlanDecision? = null
            launch(runtime, planner, ReviewedPlanStore(InMemorySettings())) { decision ->
                shown = decision
                assertTrue(persistence.definitions.all().isEmpty(), "Nothing has started while the keeper decides")
                true
            }

            assertEquals(1, planner.planned)
            assertEquals("rough-design", planner.lastPacket?.currentState)
            assertEquals(listOf("spec", "build"), planner.lastPacket?.currentPlan?.map { it.id }, "The planner is shown the design")
            assertTrue(shown?.outcome?.approved == true)
            val stored = persistence.definitions.all()
            assertEquals(1, stored.size)
            assertTrue(stored.single().id.value.startsWith("saved-hive--planned-"), "A new id: the saved design is not overwritten")
            assertEquals(listOf("spec"), stored.single().tasks.map { it.id.value })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun decliningStartsNothing() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            launch(runtime, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { false }

            assertTrue(persistence.definitions.all().isEmpty())
            assertFalse(ReviewedPlanStore(settings).hasPending())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun launchingItAgainIsPlannedAndDecidedAfreshEveryTime() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val planner = CountingPlanner(onePlan)
        var asked = 0
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            repeat(2) { launch(runtime, planner, ReviewedPlanStore(InMemorySettings())) { asked++; true } }

            assertEquals(2, planner.planned)
            assertEquals(2, asked)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aHiveWithNoOrchestratorLaunchesAsItAlwaysDidWithoutAskingAnyone() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val planner = CountingPlanner(onePlan)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            val plain = roughHive.copy(tasks = roughHive.tasks.drop(1).map { it.copy(dependsOn = emptySet()) })
            launch(runtime, planner, ReviewedPlanStore(InMemorySettings()), plain) { error("Nobody is asked") }

            assertEquals(0, planner.planned)
            assertEquals(listOf("saved-hive"), persistence.definitions.all().map { it.id.value })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun withNoPlannerAKeeperOrAReviewerItLaunchesAsItAlwaysDid() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            runtime.launchSavedWorkflow(definition = roughHive, objective = "Ship it")

            assertEquals(listOf("saved-hive"), persistence.definitions.all().map { it.id.value })
        } finally {
            scope.cancel()
        }
    }

    // ---- resuming -----------------------------------------------------------------------------------------

    @Test
    fun aRestartDuringTheDecisionBringsTheSavedHivesPlanBackWhileTheDesignIsUnchanged() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            persistence.definitions.put(roughHive)
            val first = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            assertFailsWith<CancellationException> {
                launch(first, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { throw CancellationException("closed") }
            }
            assertTrue(ReviewedPlanStore(settings).hasPending())

            val second = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            var shown: PlanDecision? = null
            val offered = second.resumePendingPlanLaunch(ReviewedPlanStore(settings)) { shown = it; true }

            assertTrue(offered)
            assertEquals("Spec it", shown?.outcome?.plan?.steps?.single()?.name)
            val planned = persistence.definitions.all().filter { it.id.value.startsWith("saved-hive--planned-") }
            assertEquals(1, planned.size, "The keeper started it")
            assertEquals("saved-hive", persistence.definitions.get(WorkflowDefinitionId("saved-hive"))?.id?.value)
            assertFalse(ReviewedPlanStore(settings).hasPending())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSavedHiveEditedWhileTheAppWasClosedDropsTheOldDecision() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            persistence.definitions.put(roughHive)
            val first = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            assertFailsWith<CancellationException> {
                launch(first, CountingPlanner(onePlan), ReviewedPlanStore(settings)) { throw CancellationException("closed") }
            }
            persistence.definitions.put(roughHive.copy(tasks = roughHive.tasks + agent("extra", engineer, "build")))

            val second = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            var asked = false
            val offered = second.resumePendingPlanLaunch(ReviewedPlanStore(settings)) { asked = true; true }

            assertFalse(offered)
            assertFalse(asked)
            assertFalse(ReviewedPlanStore(settings).hasPending(), "Dropped")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theDesignIsPartOfWhatAPlanIsMadeFrom() {
        val a = com.hereliesaz.geministrator.orchestration.planInputsFingerprint("Ship it", null, emptyList(), listOf(pm), BuiltInRoles.AdversarialReviewer)
        val b = com.hereliesaz.geministrator.orchestration.planInputsFingerprint("Ship it", null, emptyList(), listOf(pm), BuiltInRoles.AdversarialReviewer, roughHive)
        val c = com.hereliesaz.geministrator.orchestration.planInputsFingerprint(
            "Ship it", null, emptyList(), listOf(pm), BuiltInRoles.AdversarialReviewer,
            roughHive.copy(tasks = roughHive.tasks.map { it.copy(objective = it.objective + "!") }),
        )
        assertNotEquals(a, b)
        assertNotEquals(b, c)
    }
}
