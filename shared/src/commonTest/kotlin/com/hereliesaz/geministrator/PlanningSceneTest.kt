package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.PlanningPhase
import com.hereliesaz.geministrator.orchestration.PlanningStage
import com.hereliesaz.geministrator.orchestration.PlanningStages
import com.hereliesaz.geministrator.orchestration.PlanningVerdict
import com.hereliesaz.geministrator.orchestration.firstStep
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningSceneTest {
    private val planner = BuiltInRoles.Orchestrator
    private val reviewer = BuiltInRoles.AdversarialReviewer
    private val engineer = BuiltInRoles.ImplementationEngineer

    private fun stage(phase: PlanningPhase, round: Int = 1, verdict: PlanningVerdict? = null, withPlan: Boolean = true) = PlanningStage(
        phase = phase,
        planner = planner,
        reviewer = reviewer,
        firstStepRole = engineer.takeIf { withPlan },
        firstStepName = "Build it".takeIf { withPlan },
        round = round,
        verdict = verdict,
    )

    private fun births(stage: PlanningStage): Map<String, CreatureBirth> {
        val scene = planningScene(stage)
        return projectBirths(scene.definition, scene.run)
    }

    private fun status(stage: PlanningStage, id: String): TaskRunStatus =
        planningScene(stage).run.taskRuns.values.first { it.taskDefinitionId.value == id }.status

    @Test
    fun whilePlanningTheReviewerIsAnEggThePlannerGestatesAndNothingOfTheFirstStepIsDrawn() {
        val born = births(stage(PlanningPhase.Planning, round = 0, withPlan = false))

        val egg = assertIs<CreatureBirth.Egg>(born[PLANNING_REVIEW_ID])
        assertEquals(listOf(EggGestator(PLANNING_PLAN_ID, 0.5f)), egg.gestators)
        assertEquals(CreatureBirth.Hidden, born[PLANNING_FIRST_ID])
        assertNull(born[PLANNING_PLAN_ID], "The planner is born: it is the one at work")
    }

    @Test
    fun whenThePlanIsCompleteTheReviewerHatchesAndTheFirstStepsEggIsGestatedByBoth() {
        val born = births(stage(PlanningPhase.Reviewing))

        assertNull(born[PLANNING_REVIEW_ID], "The reviewer has hatched")
        val egg = assertIs<CreatureBirth.Egg>(born[PLANNING_FIRST_ID])
        assertEquals(listOf(EggGestator(PLANNING_PLAN_ID, 1f), EggGestator(PLANNING_REVIEW_ID, 0.5f)), egg.gestators)
        assertEquals(0.75f, egg.growth)
    }

    @Test
    fun theFirstStepIsTheRoleOfThePlansFirstStepWithNothingBeforeIt() {
        val scene = planningScene(stage(PlanningPhase.Reviewing))
        assertEquals(engineer.id, scene.definition.tasks.first { it.id.value == PLANNING_FIRST_ID }.roleId)
        assertEquals("Build it", scene.definition.tasks.first { it.id.value == PLANNING_FIRST_ID }.name)

        val plan = OrchestrationPlan(
            listOf(
                OrchestrationPlanStep("later", "Later", "x", "pm", dependsOn = listOf("first")),
                OrchestrationPlanStep("first", "First", "x", "engineer"),
            ),
        )
        assertEquals("first", plan.firstStep()?.id)
    }

    @Test
    fun reworkingKeepsTheReviewerHatchedAndTheFirstEggUnhatched() {
        val reworking = stage(PlanningPhase.Reworking, round = 2)
        val born = births(reworking)

        assertNull(born[PLANNING_REVIEW_ID])
        val egg = assertIs<CreatureBirth.Egg>(born[PLANNING_FIRST_ID])
        assertEquals(listOf(0.5f, 0.25f), egg.gestators.map { it.contribution })
        assertEquals(TaskRunStatus.Running, status(reworking, PLANNING_PLAN_ID), "The planner works again")
    }

    @Test
    fun noEndOfTheReviewLetsTheFirstStepHatchUntilTheKeeperStartsTheRun() {
        for (verdict in PlanningVerdict.entries) {
            val awaiting = stage(PlanningPhase.AwaitingKeeper, round = 2, verdict = verdict)
            val first = births(awaiting)[PLANNING_FIRST_ID]
            when (verdict) {
                // The reviewer failed, so what it was gestating withers; the keeper may still start the run.
                PlanningVerdict.Unreachable -> assertEquals(CreatureBirth.Withered, first)
                else -> assertIs<CreatureBirth.Egg>(first, "Held by the keeper's decision: $verdict")
            }
        }
        assertEquals(TaskRunStatus.AwaitingApproval, status(stage(PlanningPhase.AwaitingKeeper, verdict = PlanningVerdict.Approved), PLANNING_REVIEW_ID))
        assertEquals(TaskRunStatus.Escalated, status(stage(PlanningPhase.AwaitingKeeper, verdict = PlanningVerdict.NotApproved), PLANNING_REVIEW_ID))
    }

    @Test
    fun theFirstStepHatchesOnlyWhenTheRunIsStarted() {
        val started = stage(PlanningPhase.Started)
        assertNull(births(started)[PLANNING_FIRST_ID], "Born: released by the planner and the reviewer both being done")
        assertEquals(TaskRunStatus.Ready, status(started, PLANNING_FIRST_ID))
        for (phase in PlanningPhase.entries - PlanningPhase.Started) {
            val unborn = births(stage(phase, withPlan = phase != PlanningPhase.Planning))[PLANNING_FIRST_ID]
            assertTrue(unborn != null, "Not born in $phase")
        }
    }

    // ---- the launch reports where it is -----------------------------------------------------------------

    private class Planner(private val first: OrchestrationPlan, private val reworked: OrchestrationPlan) : OrchestrationAgentRuntime {
        override suspend fun plan(packet: OrchestrationPacket) = first
        override suspend fun repair(packet: OrchestrationPacket) = reworked
    }

    @Test
    fun theLaunchReportsEachStageAsItHappens() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pm = BuiltInRoles.ProductManager.id.value
        val verdicts = mutableListOf<PlanVerdict>(PlanVerdict.Reject("Do better."), PlanVerdict.Approve("Fine."))
        val seen = mutableListOf<PlanningStage>()
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = InMemoryWorkflowPersistence())
            withContext(PlanningStages { seen += it }) {
                runtime.launchOrchestratedWorkflow(
                    projectName = "Plan",
                    objective = "Ship it",
                    orchestrationRuntime = Planner(
                        OrchestrationPlan(listOf(OrchestrationPlanStep("one", "One", "Do one", pm))),
                        OrchestrationPlan(listOf(OrchestrationPlanStep("two", "Two", "Do two", pm))),
                    ),
                    planReviewer = { verdicts.removeAt(0) },
                    confirmPlan = { true },
                )
            }

            assertEquals(
                listOf(
                    PlanningPhase.Planning to 0,
                    PlanningPhase.Reviewing to 1,
                    PlanningPhase.Reworking to 2,
                    PlanningPhase.Reviewing to 2,
                    PlanningPhase.AwaitingKeeper to 2,
                    PlanningPhase.Started to 2,
                ),
                seen.map { it.phase to it.round },
            )
            assertNull(seen.first().firstStepRole, "No plan yet: no first step")
            assertEquals("One", seen[1].firstStepName)
            assertEquals("Two", seen[3].firstStepName, "The reworked plan's first step")
            assertEquals(PlanningVerdict.Approved, seen[4].verdict)
            assertEquals(BuiltInRoles.Reviewer.id, seen[1].reviewer.id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLaunchWithoutAReviewReportsNoStages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pm = BuiltInRoles.ProductManager.id.value
        val seen = mutableListOf<PlanningStage>()
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = InMemoryWorkflowPersistence())
            withContext(PlanningStages { seen += it }) {
                runtime.launchOrchestratedWorkflow(
                    projectName = "Plan",
                    objective = "Ship it",
                    orchestrationRuntime = Planner(
                        OrchestrationPlan(listOf(OrchestrationPlanStep("one", "One", "Do one", pm))),
                        OrchestrationPlan(listOf(OrchestrationPlanStep("one", "One", "Do one", pm))),
                    ),
                )
            }
            assertTrue(seen.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
