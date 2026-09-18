package com.hereliesaz.geministrator.mesh

import com.hereliesaz.geministrator.domain.ComputeAccelerator
import com.hereliesaz.geministrator.domain.ComputePlacementPolicy
import com.hereliesaz.geministrator.domain.ComputeRequirements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComputeMeshSchedulerTest {
    @Test
    fun saturatedOriginOffloadsToEligiblePeer() {
        val local = device(
            id = "phone",
            cores = 8,
            memory = 8_000,
            load = ComputeDeviceLoad(activeLeases = 2, maxConcurrentLeases = 2),
        )
        val desktop = device(
            id = "desktop",
            cores = 16,
            memory = 32_000,
            load = ComputeDeviceLoad(activeLeases = 0, maxConcurrentLeases = 4),
        )
        val plan = ComputeMeshScheduler(local).plan(
            ComputePlacementPolicy.RemoteAllowed(
                requirements = ComputeRequirements(minimumLogicalCores = 4),
            ),
            listOf(desktop),
        )

        assertEquals("desktop", assertIs<ComputePlacementPlan.Remote>(plan).device.id.value)
    }

    @Test
    fun bitcosRequirementRejectsPeerThatDoesNotHaveEncoding() {
        val local = device(id = "phone", cores = 4, memory = 4_000)
        val generic = device(id = "desktop", cores = 16, memory = 32_000)
        val plan = ComputeMeshScheduler(local).plan(
            ComputePlacementPolicy.PreferredDevice(
                deviceId = "desktop",
                requirements = ComputeRequirements(
                    requiredWeightEncodings = setOf("bitcos-v1"),
                ),
                allowOtherPeers = false,
                allowLocalFallback = false,
            ),
            listOf(generic),
        )

        assertIs<ComputePlacementPlan.Blocked>(plan)
    }

    @Test
    fun distributedPlanAssignsOneStableSlicePerParticipant() {
        val local = device(id = "phone", cores = 8, memory = 8_000)
        val peers = listOf(
            device(id = "desktop-a", cores = 16, memory = 32_000),
            device(id = "desktop-b", cores = 12, memory = 24_000),
        )
        val plan = assertIs<ComputePlacementPlan.Distributed>(
            ComputeMeshScheduler(local).plan(
                ComputePlacementPolicy.Distributed(
                    maxDevices = 3,
                    minimumDevices = 3,
                    allowLocalParticipant = true,
                ),
                peers,
            ),
        )

        assertEquals(3, plan.participants.size)
        assertEquals(setOf(0, 1, 2), plan.participants.map { it.sliceIndex }.toSet())
        assertTrue(plan.participants.all { it.sliceCount == 3 })
        assertTrue(plan.participants.any { it.local })
    }

    private fun device(
        id: String,
        cores: Int,
        memory: Long,
        load: ComputeDeviceLoad = ComputeDeviceLoad(),
    ) = ComputeDeviceAdvertisement(
        id = ComputeDeviceId(id),
        displayName = id,
        platform = ComputeDevicePlatform.Android,
        architecture = "test",
        logicalCores = cores,
        memoryBytes = memory,
        accelerators = setOf(ComputeAccelerator.Cpu),
        grantedPermissions = ComputePermission.entries.toSet(),
        load = load,
        lastSeenEpochMillis = 1L,
    )
}
