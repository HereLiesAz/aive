package com.hereliesaz.aive

import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDistributedComputeEligibilityTest {
    private val strict = DistributedComputeConfiguration(
        sharingEnabled = true,
        allowMeteredNetwork = false,
        requireExternalPower = true,
    )

    @Test
    fun strictPolicyRequiresUnmeteredNetworkAndExternalPower() {
        assertTrue(
            androidComputePolicyAllowsWork(
                configuration = strict,
                meteredNetwork = false,
                onExternalPower = true,
            ),
        )
        assertFalse(
            androidComputePolicyAllowsWork(
                configuration = strict,
                meteredNetwork = true,
                onExternalPower = true,
            ),
        )
        assertFalse(
            androidComputePolicyAllowsWork(
                configuration = strict,
                meteredNetwork = false,
                onExternalPower = false,
            ),
        )
    }

    @Test
    fun permissivePolicyRecoversWhenConditionsChange() {
        val permissive = strict.copy(
            allowMeteredNetwork = true,
            requireExternalPower = false,
        )
        assertTrue(
            androidComputePolicyAllowsWork(
                configuration = permissive,
                meteredNetwork = true,
                onExternalPower = false,
            ),
        )
    }

    @Test
    fun disabledSharingAlwaysRefusesWork() {
        assertFalse(
            androidComputePolicyAllowsWork(
                configuration = strict.copy(sharingEnabled = false),
                meteredNetwork = false,
                onExternalPower = true,
            ),
        )
    }

    @Test
    fun aHotPhoneDeclinesNewRelayJobs() {
        kotlin.test.assertNull(thermalHoldReason(null, null))
        kotlin.test.assertNull(thermalHoldReason(1, 350))
        kotlin.test.assertNotNull(thermalHoldReason(2, 300))
        kotlin.test.assertNotNull(thermalHoldReason(null, 430))
        kotlin.test.assertNull(thermalHoldReason(null, Int.MIN_VALUE))
    }
}
