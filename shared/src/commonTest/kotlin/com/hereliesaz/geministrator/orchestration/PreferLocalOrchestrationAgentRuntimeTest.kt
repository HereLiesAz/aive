package com.hereliesaz.geministrator.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class PreferLocalOrchestrationAgentRuntimeTest {
    private val packet = OrchestrationPacket(
        objective = "o",
        availableAgents = listOf(OrchestrationRole("r", "R", emptyList())),
        instruction = "i",
    )

    private fun planner(stepId: String?) = object : OrchestrationAgentRuntime {
        override suspend fun plan(packet: OrchestrationPacket) = result()
        override suspend fun repair(packet: OrchestrationPacket) = result()
        private fun result(): OrchestrationPlan {
            val id = stepId ?: error("planner failed")
            return OrchestrationPlan(listOf(OrchestrationPlanStep(id, id, id, "r")))
        }
    }

    @Test
    fun usesLocalWhenReady() = runTest {
        val runtime = PreferLocalOrchestrationAgentRuntime(planner("local"), { true }, planner("cloud"))
        assertEquals("local", runtime.plan(packet).steps.single().id)
    }

    @Test
    fun usesCloudWhenLocalNotReady() = runTest {
        val runtime = PreferLocalOrchestrationAgentRuntime(planner("local"), { false }, planner("cloud"))
        assertEquals("cloud", runtime.plan(packet).steps.single().id)
    }

    @Test
    fun fallsBackToCloudWhenLocalFails() = runTest {
        val runtime = PreferLocalOrchestrationAgentRuntime(planner(null), { true }, planner("cloud"))
        assertEquals("cloud", runtime.repair(packet).steps.single().id)
    }

    @Test
    fun propagatesLocalFailureWithoutCloud() = runTest {
        val runtime = PreferLocalOrchestrationAgentRuntime(planner(null), { true }, null)
        assertFailsWith<IllegalStateException> { runtime.plan(packet) }
    }
}
