package com.hereliesaz.geministrator.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrchestrationRuntimeTest {
    private val packet = OrchestrationPacket(
        objective = "Ship it",
        availableAgents = listOf(
            OrchestrationRole(
                id = "implementation-engineer",
                name = "Implementation Engineer",
                authorities = listOf("Implement"),
            ),
        ),
        instruction = "Plan",
    )

    @Test
    fun validDependencyDagIsAccepted() {
        val plan = OrchestrationPlan(
            steps = listOf(
                OrchestrationPlanStep(
                    id = "implement",
                    name = "Implement",
                    objective = "Implement the objective",
                    roleId = "implementation-engineer",
                ),
                OrchestrationPlanStep(
                    id = "verify",
                    name = "Verify",
                    objective = "Verify the implementation",
                    roleId = "implementation-engineer",
                    dependsOn = listOf("implement"),
                ),
            ),
        )

        assertEquals(plan, plan.validateAgainst(packet))
    }

    @Test
    fun unavailableRoleIsRejected() {
        val plan = OrchestrationPlan(
            listOf(
                OrchestrationPlanStep(
                    id = "work",
                    name = "Work",
                    objective = "Do work",
                    roleId = "invented-role",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> { plan.validateAgainst(packet) }
    }

    @Test
    fun dependencyCycleIsRejected() {
        val plan = OrchestrationPlan(
            steps = listOf(
                OrchestrationPlanStep(
                    id = "a",
                    name = "A",
                    objective = "A",
                    roleId = "implementation-engineer",
                    dependsOn = listOf("b"),
                ),
                OrchestrationPlanStep(
                    id = "b",
                    name = "B",
                    objective = "B",
                    roleId = "implementation-engineer",
                    dependsOn = listOf("a"),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> { plan.validateAgainst(packet) }
    }
}
