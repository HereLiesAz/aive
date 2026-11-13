package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.distributed.EnrolledComputeNode
import com.hereliesaz.geministrator.domain.ComputePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hosts' "Your other devices": drawn only from state the Hosts part already holds, each row aimed at where it changes. */
class OtherDevicesTest {
    private fun node(id: String) = ComputeNodeDescriptor(
        nodeId = id,
        displayName = id,
        platform = ComputePlatform.Desktop,
        architecture = "x86_64",
        logicalProcessors = 4,
        memoryMiB = 8_192,
    )

    private fun enrolled(id: String, revoked: Boolean = false) = EnrolledComputeNode(
        nodeId = id,
        displayName = id,
        publicKeyFingerprint = "0123456789abcdef",
        enrolledAtEpochMillis = 1L,
        revokedAtEpochMillis = if (revoked) 2L else null,
    )

    private fun remote(enabled: Boolean) = RemoteAccessSetting(
        enabled = enabled,
        relayUrl = "",
        defaultRelayUrl = "",
        pairingCode = null,
        status = null,
        onEnabledChange = {},
        onRelayUrlChange = {},
        onResetPairing = {},
        onCopyPairingCode = {},
        onSharePairingCode = {},
    )

    @Test
    fun nothingToListWhenThereIsNoOtherDevice() {
        assertTrue(otherDevices(DistributedComputeUiState(), null).isEmpty())
    }

    @Test
    fun thisDeviceIsNeverListedAndARevokedNodeIsGone() {
        val state = DistributedComputeUiState(
            configuration = DistributedComputeConfiguration(nodeId = "me"),
            onlineNodes = listOf(node("me"), node("laptop")),
            enrolledNodes = listOf(enrolled("me"), enrolled("laptop"), enrolled("old", revoked = true), enrolled("desk")),
        )
        val devices = otherDevices(state, null)
        assertEquals(listOf("desk", "laptop"), devices.map { it.name })
        assertEquals(DeviceState.Offline, devices.first { it.name == "desk" }.state)
        assertEquals(DeviceState.Online, devices.first { it.name == "laptop" }.state)
        assertTrue(devices.all { it.target == KeeperSection.Compute })
    }

    @Test
    fun thePairedLinkOpensThisDevicePart() {
        val on = otherDevices(DistributedComputeUiState(), remote(true)).single()
        assertEquals(DeviceState.PairedOn, on.state)
        assertEquals(KeeperSection.Device, on.target)
        assertEquals(DeviceState.PairedOff, otherDevices(DistributedComputeUiState(), remote(false)).single().state)
    }
}
