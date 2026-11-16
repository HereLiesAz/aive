package com.hereliesaz.geministrator.distributed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputeWorkGateTest {
    private val sharing = DistributedComputeConfiguration(sharingEnabled = true)
    private val strict = sharing.copy(allowMeteredNetwork = false, requireExternalPower = true)

    @Test
    fun sharingOffHoldsBeforeAnythingElse() {
        val decision = computeWorkDecision(DistributedComputeConfiguration(), meteredNetwork = true, onExternalPower = false, thermalHold = true)
        assertEquals(ComputeWorkHold.SharingOff, decision.hold)
        assertFalse(decision.accepts)
    }

    @Test
    fun heatHoldsBeforeNetworkAndPower() {
        assertEquals(
            ComputeWorkHold.TooHot,
            computeWorkDecision(strict, meteredNetwork = true, onExternalPower = false, thermalHold = true).hold,
        )
    }

    @Test
    fun meteredNetworkHoldsUnlessAllowed() {
        assertEquals(ComputeWorkHold.MeteredNetwork, computeWorkDecision(sharing, meteredNetwork = true, onExternalPower = null).hold)
        assertTrue(computeWorkDecision(sharing.copy(allowMeteredNetwork = true), meteredNetwork = true, onExternalPower = null).accepts)
    }

    @Test
    fun unknownNetworkCountsAsUnmetered() {
        assertTrue(computeWorkDecision(sharing, meteredNetwork = null, onExternalPower = null).accepts)
    }

    @Test
    fun requiredPowerNeedsProofOfExternalPower() {
        assertEquals(ComputeWorkHold.NeedsExternalPower, computeWorkDecision(strict, meteredNetwork = false, onExternalPower = false).hold)
        assertEquals(ComputeWorkHold.PowerUnknown, computeWorkDecision(strict, meteredNetwork = false, onExternalPower = null).hold)
        assertTrue(computeWorkDecision(strict, meteredNetwork = false, onExternalPower = true).accepts)
    }

    @Test
    fun powerIsIgnoredWhenNotRequired() {
        assertTrue(computeWorkDecision(sharing, meteredNetwork = false, onExternalPower = false).accepts)
        assertTrue(computeWorkDecision(sharing, meteredNetwork = false, onExternalPower = null).accepts)
    }

    @Test
    fun aHoldIsReadBackFromNodeCapabilities() {
        ComputeWorkHold.entries.forEach { hold ->
            assertEquals(hold, ComputeWorkHold.of(setOf("workflow-executor", hold.capability)))
        }
        assertNull(ComputeWorkHold.of(setOf("workflow-executor", "android")))
    }

    @Test
    fun theThermalCapabilityKeepsItsPublishedName() {
        assertEquals("thermal-hold", ComputeWorkHold.TooHot.capability)
    }
}
