package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeachingPlanFloorTest {
    private val role = "worker"

    private fun chain(count: Int, objective: String = "Open the first control", roleId: String = role): OrchestrationPlan =
        OrchestrationPlan(
            (1..count).map { i ->
                OrchestrationPlanStep(
                    id = "s$i",
                    name = "Step $i",
                    objective = objective,
                    roleId = roleId,
                    dependsOn = if (i == 1) emptyList() else listOf("s${i - 1}"),
                )
            },
        )

    private fun rules(plan: OrchestrationPlan, broad: Boolean) = TeachingPlanFloor.check(plan, role, broad).map { it.rule }

    @Test
    fun aLongEnoughChainOfSmallStepsPasses() {
        assertTrue(rules(chain(8), broad = true).isEmpty())
        assertTrue(rules(chain(3), broad = false).isEmpty())
    }

    @Test
    fun aCoarsePlanForAWholeAppGoalIsTurnedBack() {
        assertEquals(listOf("T1"), rules(chain(3), broad = true))
    }

    @Test
    fun aFocusedGoalHasAnUpperBoundAndEveryGoalHasAMaximum() {
        assertEquals(listOf("T1"), rules(chain(TeachingLimits.FOCUSED_MAX_STEPS + 1), broad = false))
        assertEquals(listOf("T1"), rules(chain(TeachingLimits.MAX_STEPS + 1), broad = true))
    }

    @Test
    fun anObjectiveOverTheWordLimitIsTurnedBack() {
        val long = List(TeachingLimits.STEP_OBJECTIVE_MAX_WORDS + 1) { "word" }.joinToString(" ")
        assertEquals(listOf("T4"), rules(chain(1, objective = long), broad = false))
    }

    @Test
    fun stepsThatAreNotOneChainAreTurnedBack() {
        val plan = chain(3)
        val broken = OrchestrationPlan(plan.steps.map { if (it.id == "s3") it.copy(dependsOn = listOf("s1")) else it })
        assertEquals(listOf("T5"), rules(broken, broad = false))
    }

    @Test
    fun anApprovalOrAnotherRoleOrARetiredWordIsTurnedBack() {
        val base = chain(2)
        val approval = OrchestrationPlan(base.steps.map { it.copy(requiresHumanApproval = it.id == "s2") })
        assertEquals(listOf("T7"), rules(approval, broad = false))
        assertEquals(listOf("T8", "T8"), rules(chain(2, roleId = "planner"), broad = false))
        assertEquals(listOf("T10", "T10"), rules(chain(2, objective = "Open the Inbox"), broad = false))
    }

    @Test
    fun theGoalIsBroadWhenItNamesTheWholeApp() {
        assertTrue(TeachingPlanFloor.isBroad("Teach me the whole app"))
        assertTrue(TeachingPlanFloor.isBroad("show me the basics"))
        assertTrue(!TeachingPlanFloor.isBroad("teach me Sources"))
    }
}
