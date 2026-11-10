package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.a2a.A2aDrifters

/** Stable Store-offer creature selection: the same package key always maps to the same rig. */
internal fun edgeCreatureFor(key: String): String = A2aDrifters.creatureFor(key)

/** A row's name, bold. */
internal val EdgeTitle = AzphaltType.body.copy(fontWeight = FontWeight.Bold)

/** A row's line under its name. */
internal val EdgeLine = AzphaltType.body.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)

/**
 * One Store offer: its creature, its name, a line
 * or two under it, and its one action on the right. [below] holds any further actions, under the
 * text. The row is lifted Ink, so its pills fill Yellow.
 */
@Composable
internal fun CreatureOfferRow(
    rig: String,
    title: String,
    details: @Composable () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    creatureSize: Int = 56,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(AzphaltLiftedInk)
            .padding(8.dp),
    ) {
        AzphaltSurface(AzphaltLiftedInk) {
            StableCreature(rig, creatureSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = EdgeTitle, color = azphaltOnSurface())
                details()
                below?.let {
                    Box(Modifier.padding(top = 6.dp)) { it() }
                }
            }
            action?.invoke()
        }
    }
}

