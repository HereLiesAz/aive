package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaspStateTest {
    @Test
    fun states() {
        assertEquals(ClaspState.Flowing, claspState(H2g2WorkflowState.Complete, H2g2WorkflowState.Active))
        assertEquals(ClaspState.Succeeded, claspState(H2g2WorkflowState.Complete, H2g2WorkflowState.Complete))
        assertEquals(ClaspState.Failed, claspState(H2g2WorkflowState.Failed, H2g2WorkflowState.Pending))
        assertEquals(ClaspState.Blocked, claspState(H2g2WorkflowState.Complete, H2g2WorkflowState.Blocked))
        assertEquals(ClaspState.Quiet, claspState(H2g2WorkflowState.Active, H2g2WorkflowState.Pending))
    }

    @Test
    fun flowingCrossesTheClaspBlockedStopsAtIt() {
        assertEquals(0.5f to null, claspPulse(ClaspState.Flowing, 0.25f))
        assertEquals(null to 0.5f, claspPulse(ClaspState.Flowing, 0.75f))
        assertEquals(0.9f to null, claspPulse(ClaspState.Blocked, 0.9f))
        assertEquals(null to null, claspPulse(ClaspState.Succeeded, 0.5f))
    }
}
