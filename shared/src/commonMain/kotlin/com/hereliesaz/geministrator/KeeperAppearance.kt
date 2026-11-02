package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.settings_ground
import com.hereliesaz.geministrator.resources.settings_ground_explain
import com.hereliesaz.geministrator.resources.settings_motion
import com.hereliesaz.geministrator.resources.settings_reduce_motion_by_device
import com.hereliesaz.geministrator.resources.settings_reduce_motion_explain
import com.hereliesaz.geministrator.resources.settings_reduce_motion_off
import com.hereliesaz.geministrator.resources.settings_reduce_motion_on
import org.jetbrains.compose.resources.stringResource

/**
 * Keeper's room › Appearance. The ground is chosen from its six swatches, the current one
 * selected; every shell (Android, Desktop, Web) offers it here and nowhere else. Below it, the
 * keeper's Reduce motion switch.
 */
@Composable
internal fun KeeperAppearance() {
    ProviderSectionLabel(stringResource(Res.string.settings_ground))
    Text(
        stringResource(Res.string.settings_ground_explain),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    GroundSwatches(
        current = Azphalt.currentGround,
        onSelect = { Azphalt.setGround(it.name) },
    )
    ReduceMotionSwitch()
}

/**
 * The keeper's Reduce motion switch. It is OR-ed with the platform's signal
 * ([effectiveReduceMotion]), so it can only turn motion off; when the device already asks for less
 * motion, a line says so rather than the switch pretending to bring motion back.
 */
@Composable
private fun ReduceMotionSwitch() {
    val on = AzphaltMotion.keeperReduceMotion
    ProviderSectionLabel(stringResource(Res.string.settings_motion))
    Text(
        stringResource(Res.string.settings_reduce_motion_explain),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    AzphaltPill(
        label = stringResource(if (on) Res.string.settings_reduce_motion_on else Res.string.settings_reduce_motion_off),
        seed = "reduce-motion-toggle",
        selected = on,
        role = Role.Switch,
        onClick = { AzphaltMotion.updateKeeperReduceMotion(!on) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (AzphaltMotion.platformReduceMotion) {
        Text(
            stringResource(Res.string.settings_reduce_motion_by_device),
            style = AzphaltType.body,
            color = azphaltMutedOnSurface(),
        )
    }
}

/**
 * One radio group: a swatch per ground, filled with its page colour and named under it. Every
 * swatch is outlined in the surface's content colour so a ground matching the surface still shows;
 * the selected one gets a second, thicker ring.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroundSwatches(current: Azphalt.Ground, onSelect: (Azphalt.Ground) -> Unit) {
    val line = azphaltOnSurface()
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
        verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s),
    ) {
        Azphalt.grounds.forEach { ground ->
            val selected = ground == current
            Column(
                modifier = Modifier
                    .azphaltSelectedTransform(selected)
                    .clip(RoundedCornerShape(AzphaltSpacing.m))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(ground) })
                    .padding(AzphaltSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(AzphaltMinTouchTarget)
                        .then(if (selected) Modifier.border(3.dp, line, CircleShape).padding(5.dp) else Modifier)
                        .clip(CircleShape)
                        .background(ground.page)
                        .border(2.dp, line, CircleShape),
                )
                Text(
                    ground.name.uppercase(),
                    style = AzphaltType.endCap,
                    color = if (selected) line else azphaltMutedOnSurface(),
                )
            }
        }
    }
}
