package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.geministrator.resources.keeper_ground_selected
import com.hereliesaz.geministrator.resources.settings_always_show_labels
import com.hereliesaz.geministrator.resources.settings_reduce_motion
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
import com.hereliesaz.geministrator.resources.settings_creature_labels
import com.hereliesaz.geministrator.resources.settings_creature_labels_always
import com.hereliesaz.geministrator.resources.settings_creature_labels_crowded
import com.hereliesaz.geministrator.resources.settings_creature_labels_explain
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
 * keeper's Reduce motion and Always show creature labels switches. [wide] draws the desktop
 * card's version: a grid of ground cards and switch rows.
 */
@Composable
internal fun KeeperAppearance(wide: Boolean = false) {
    if (wide) {
        WideKeeperAppearance()
        return
    }
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
    CreatureLabelsSwitch()
}

/** Lifts the [NAMED_CREATURE_LIMIT] so every creature keeps its label however full the hive is. */
@Composable
private fun CreatureLabelsSwitch() {
    val on = CreatureLabels.alwaysShow
    ProviderSectionLabel(stringResource(Res.string.settings_creature_labels))
    Text(
        stringResource(Res.string.settings_creature_labels_explain, NAMED_CREATURE_LIMIT),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    AzphaltToggle(
        label = stringResource(if (on) Res.string.settings_creature_labels_always else Res.string.settings_creature_labels_crowded),
        seed = "creature-labels-toggle",
        selected = on,
        onClick = { CreatureLabels.updateAlwaysShow(!on) },
        modifier = Modifier.fillMaxWidth(),
    )
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
    AzphaltToggle(
        label = stringResource(if (on) Res.string.settings_reduce_motion_on else Res.string.settings_reduce_motion_off),
        seed = "reduce-motion-toggle",
        selected = on,
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

/** Appearance on the wide Keeper's room card: ground cards three across, then the two switches. */
@Composable
private fun WideKeeperAppearance() {
    ProviderSectionLabel(stringResource(Res.string.settings_ground))
    Text(
        stringResource(Res.string.settings_ground_explain),
        style = AzphaltType.body,
        color = azphaltMutedOnSurface(),
    )
    val current = Azphalt.currentGround
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Azphalt.grounds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { ground -> GroundCard(ground, ground == current, Modifier.weight(1f)) { Azphalt.setGround(ground.name) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val motionOn = AzphaltMotion.keeperReduceMotion
    SwitchRow(
        title = stringResource(Res.string.settings_reduce_motion),
        line = stringResource(Res.string.settings_reduce_motion_explain),
        on = motionOn,
        onChange = { AzphaltMotion.updateKeeperReduceMotion(it) },
        note = stringResource(Res.string.settings_reduce_motion_by_device).takeIf { AzphaltMotion.platformReduceMotion },
    )
    val labelsOn = CreatureLabels.alwaysShow
    SwitchRow(
        title = stringResource(Res.string.settings_always_show_labels),
        line = stringResource(Res.string.settings_creature_labels_explain, NAMED_CREATURE_LIMIT),
        on = labelsOn,
        onChange = { CreatureLabels.updateAlwaysShow(it) },
    )
}

/** One ground: its swatch, its name and, when it is the current one, SELECTED in the corner. */
@Composable
private fun GroundCard(ground: Azphalt.Ground, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val strong = azphaltStrong()
    Box(
        modifier
            .height(92.dp)
            .clip(shape)
            .background(AzphaltLiftedInk)
            .border(if (selected) 2.dp else 1.dp, if (selected) strong else azphaltMutedOnSurface().copy(alpha = 0.4f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(10.dp),
    ) {
        AzphaltSurface(AzphaltLiftedInk) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(49.dp).clip(CircleShape).background(ground.page).border(1.dp, azphaltMutedOnSurface(), CircleShape))
                Text(ground.name, style = AzphaltType.lead, color = azphaltOnSurface())
            }
            if (selected) {
                Text(
                    stringResource(Res.string.keeper_ground_selected).uppercase(),
                    style = AzphaltType.endCap,
                    color = strong,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

/** A setting row: what it is, one line about it, and a switch; the whole row toggles. */
@Composable
private fun SwitchRow(title: String, line: String, on: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    val divider = azphaltMutedOnSurface().copy(alpha = 0.35f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = AzphaltType.lead, color = azphaltOnSurface())
            Text(line, style = AzphaltType.body, color = azphaltMutedOnSurface())
            note?.let { Text(it, style = AzphaltType.body, color = azphaltMutedOnSurface()) }
        }
        AzphaltSwitchTrack(on)
    }
}

/**
 * One toggle idiom for the Keeper's room: the setting's name on the left, a switch on the right, the whole row toggling. The
 * name never says ON or OFF; the switch shows it. [seed] is kept so a call reads like the pill it replaced.
 */
@Composable
internal fun AzphaltToggle(
    label: String,
    @Suppress("UNUSED_PARAMETER") seed: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AzphaltMinTouchTarget)
            .toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = AzphaltType.lead, color = azphaltOnSurface(), modifier = Modifier.weight(1f))
        AzphaltSwitchTrack(selected)
    }
}

/** The switch itself, drawn: on is the strong colour with a thumb in its opposite, off is muted. */
@Composable
internal fun AzphaltSwitchTrack(on: Boolean) {
    val strong = azphaltStrong()
    val track = if (on) strong else azphaltMutedOnSurface().copy(alpha = 0.45f)
    val thumb = if (on) strong.onOpposite() else azphaltOnSurface()
    Box(
        Modifier.size(width = 43.dp, height = 24.dp).clip(RoundedCornerShape(14.dp)).background(track).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(thumb))
    }
}
