package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HallMonitorSolutionTrialRoutingTest {
    @Test
    fun trialActionRoundTripsFindingAndSolution() {
        val action = hallMonitorTrialActionId("latency:plateau::nested", 3)
        val parsed = parseHallMonitorTrialActionId(action)

        assertEquals("latency:plateau::nested", parsed?.findingId)
        assertEquals(3, parsed?.solutionIndex)
    }

    @Test
    fun ordinaryTaskIdIsNotATrialAction() {
        assertNull(parseHallMonitorTrialActionId("implementation"))
    }

    @Test
    fun launchObjectiveRoundTripsFindingAndSolution() {
        val objective = hallMonitorTrialLaunchObjective("memory-routing", 1)
        val parsed = parseHallMonitorTrialLaunchObjective(objective)

        assertEquals("memory-routing", parsed?.findingId)
        assertEquals(1, parsed?.solutionIndex)
    }
}
