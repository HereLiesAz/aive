package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.devices_card_title
import com.hereliesaz.geministrator.resources.devices_host_eyebrow
import com.hereliesaz.geministrator.resources.devices_paired_name
import com.hereliesaz.geministrator.resources.devices_state_offline
import com.hereliesaz.geministrator.resources.devices_state_online
import com.hereliesaz.geministrator.resources.devices_state_paired_off
import com.hereliesaz.geministrator.resources.devices_state_paired_on
import org.jetbrains.compose.resources.stringResource

/** How one of the keeper's other devices stands. */
internal enum class DeviceState { Online, Offline, PairedOn, PairedOff }

/**
 * One of the keeper's other devices, drawn from state the Hosts part already holds (the compute pool's nodes and
 * the paired remote link). [target] is the part where it is changed.
 */
internal data class OtherDevice(
    val key: String,
    /** The device's own name, or null for the paired remote link (its name is a string resource). */
    val name: String?,
    val state: DeviceState,
    val target: KeeperSection,
)

/**
 * The keeper's other devices: every compute node but this one (online as the relay reports, else offline; a revoked one
 * is gone), and the paired remote link when remote access exists on this build. Pure: nothing is stored.
 */
internal fun otherDevices(compute: DistributedComputeUiState, remoteAccess: RemoteAccessSetting?): List<OtherDevice> {
    val localId = compute.configuration.nodeId
    val online = compute.onlineNodes.associateBy { it.nodeId }
    val names = LinkedHashMap<String, String>()
    compute.enrolledNodes.filter { !it.revoked }.forEach { names[it.nodeId] = it.displayName }
    compute.onlineNodes.forEach { names.putIfAbsent(it.nodeId, it.displayName) }
    val nodes = names.entries
        .filter { it.key != localId }
        .sortedBy { it.value.lowercase() }
        .map { (id, name) ->
            OtherDevice(
                key = "node:$id",
                name = name,
                state = if (id in online) DeviceState.Online else DeviceState.Offline,
                target = KeeperSection.Compute,
            )
        }
    val paired = remoteAccess?.let {
        OtherDevice(
            key = "paired",
            name = null,
            state = if (it.enabled) DeviceState.PairedOn else DeviceState.PairedOff,
            target = KeeperSection.Device,
        )
    }
    return listOfNotNull(paired) + nodes
}

/**
 * Hosts' "Your other devices" card: one row each, its state, and a tap to the part that changes it. It adds no screen:
 * it only lists what Compute and This device already hold. Nothing is drawn when there is no other device.
 */
@Composable
internal fun OtherDevicesCard(
    compute: DistributedComputeUiState,
    remoteAccess: RemoteAccessSetting?,
    onOpen: ((KeeperSection) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val devices = otherDevices(compute, remoteAccess)
    if (devices.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderSectionLabel(stringResource(Res.string.devices_card_title))
        devices.forEach { device ->
            AzphaltRecord(
                seed = "other-device-" + device.key,
                eyebrow = stringResource(Res.string.devices_host_eyebrow),
                title = device.name ?: stringResource(Res.string.devices_paired_name),
                endCap = when (device.state) {
                    DeviceState.Online -> stringResource(Res.string.devices_state_online)
                    DeviceState.Offline -> stringResource(Res.string.devices_state_offline)
                    DeviceState.PairedOn -> stringResource(Res.string.devices_state_paired_on)
                    DeviceState.PairedOff -> stringResource(Res.string.devices_state_paired_off)
                },
                onClick = onOpen?.let { open -> { open(device.target) } },
            )
        }
    }
}
