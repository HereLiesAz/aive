package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaspStateTest {
    @Test
    fun theGlowIsTheOutcomeThePulseIsTheWaiting() {
        assertEquals(Clasp(ClaspOutcome.None, ClaspFlow.Through), clasp(H2g2WorkflowState.Active, H2g2WorkflowState.Pending))
        assertEquals(Clasp(ClaspOutcome.Succeeded, ClaspFlow.Through), clasp(H2g2WorkflowState.Complete, H2g2WorkflowState.Active))
        assertEquals(Clasp(ClaspOutcome.Succeeded, ClaspFlow.Still), clasp(H2g2WorkflowState.Complete, H2g2WorkflowState.Complete))
        assertEquals(Clasp(ClaspOutcome.Failed, ClaspFlow.Still), clasp(H2g2WorkflowState.Failed, H2g2WorkflowState.Pending))
        assertEquals(Clasp(ClaspOutcome.Failed, ClaspFlow.ToClasp), clasp(H2g2WorkflowState.Complete, H2g2WorkflowState.Blocked))
        assertEquals(Clasp(ClaspOutcome.None, ClaspFlow.Still), clasp(H2g2WorkflowState.Pending, H2g2WorkflowState.Pending))
    }

    @Test
    fun throughCrossesTheClaspToClaspStopsAtIt() {
        assertEquals(0.5f to null, claspPulse(ClaspFlow.Through, 0.25f))
        assertEquals(null to 0.5f, claspPulse(ClaspFlow.Through, 0.75f))
        assertEquals(0.9f to null, claspPulse(ClaspFlow.ToClasp, 0.9f))
        assertEquals(null to null, claspPulse(ClaspFlow.Still, 0.5f))
    }
}
