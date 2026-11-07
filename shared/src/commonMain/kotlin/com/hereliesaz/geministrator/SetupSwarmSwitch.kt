package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.tab_position
import com.hereliesaz.geministrator.resources.view_setup
import com.hereliesaz.geministrator.resources.view_swarm
import org.jetbrains.compose.resources.stringResource

/**
 * The two ways to look at the hive and at the Memory swarm: **Setup**, the flowchart kept simple, and
 * **Swarm**, the swarm in full. An Ink capsule whose selected half is Yellow, a tab row to screen
 * readers with 48dp halves. [label] says what it switches ("Hive view", "Memory view").
 */
@Composable
internal fun SetupSwarmSwitch(
    swarm: Boolean,
    onSwarmChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val options = listOf(false to stringResource(Res.string.view_setup), true to stringResource(Res.string.view_swarm))
    Row(
        modifier = modifier
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(999.dp))
            .background(Azphalt.Ink)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AzphaltSurface(Azphalt.Ink) {
            options.forEachIndexed { index, (isSwarm, text) ->
                val selected = isSwarm == swarm
                val fill = if (selected) Azphalt.Yellow else Azphalt.Ink
                val position = stringResource(Res.string.tab_position, index + 1, options.size)
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = AzphaltMinTouchTarget, minHeight = AzphaltMinTouchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .background(fill)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSwarmChange(isSwarm) })
                        .semantics { stateDescription = position }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text.uppercase(), style = AzphaltType.capsule, color = fill.onOpposite())
                }
            }
        }
    }
}
