package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.needs_budget
import com.hereliesaz.geministrator.resources.needs_echo
import com.hereliesaz.geministrator.resources.needs_echo_fevered
import com.hereliesaz.geministrator.resources.needs_echo_grounded
import com.hereliesaz.geministrator.resources.needs_echo_insane
import com.hereliesaz.geministrator.resources.needs_echo_self_absorbed
import com.hereliesaz.geministrator.resources.needs_integrity
import com.hereliesaz.geministrator.resources.needs_near_clone
import com.hereliesaz.geministrator.resources.needs_needs
import com.hereliesaz.geministrator.resources.needs_not_reported
import com.hereliesaz.geministrator.resources.needs_nothing_downstream
import com.hereliesaz.geministrator.resources.needs_output_used
import com.hereliesaz.geministrator.resources.needs_reviewed_by_itself
import com.hereliesaz.geministrator.resources.needs_reviews_of
import com.hereliesaz.geministrator.resources.needs_tokens_spent
import org.jetbrains.compose.resources.stringResource

private val NeedTrack = Color(0xFF4A4139)
// The palette only (docs/BRANDING.md): a healthy need fills Yellow; a grounding that has gone
// septic dims to muted Ink rather than taking a hue of its own.
private val EchoWarm = Azphalt.Yellow
private val EchoSeptic = Color(0xFF7A6E5F)
private val IntegrityGreen = Azphalt.Yellow
private val OutputTeal = Azphalt.Yellow

/** The four needs (docs/Swarm-OS.md), shown on the inspector's Ink surface. */
@Composable
internal fun CreatureNeedsPanel(needs: CreatureNeeds, modifier: Modifier = Modifier, titled: Boolean = true) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // In the creature panel the Needs tab already names it.
        if (titled) Text(stringResource(Res.string.needs_needs), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        val state = stringResource(
            when (needs.echoState) {
                EchoState.Grounded -> Res.string.needs_echo_grounded
                EchoState.SelfAbsorbed -> Res.string.needs_echo_self_absorbed
                EchoState.Fevered -> Res.string.needs_echo_fevered
                EchoState.Insane -> Res.string.needs_echo_insane
            },
        )
        NeedMeter(
            label = stringResource(Res.string.needs_echo, state),
            value = needs.echo,
            color = if (needs.echoState == EchoState.Insane) EchoSeptic else EchoWarm,
        )
        NeedMeter(stringResource(Res.string.needs_integrity), needs.integrity, IntegrityGreen)
        NeedMeter(
            label = stringResource(Res.string.needs_budget),
            value = needs.budget,
            color = Azphalt.Yellow,
            missing = needs.tokensSpent?.let { stringResource(Res.string.needs_tokens_spent, it.toString()) }
                ?: stringResource(Res.string.needs_not_reported),
        )
        NeedMeter(stringResource(Res.string.needs_output_used), needs.outputUsed, OutputTeal, stringResource(Res.string.needs_nothing_downstream))
        needs.nearClone?.let { NeedLine(stringResource(Res.string.needs_near_clone), it) }
        if (needs.selfReviews > 0) {
            NeedLine(
                stringResource(Res.string.needs_reviewed_by_itself),
                stringResource(Res.string.needs_reviews_of, needs.selfReviews, needs.reviews),
            )
        }
    }
}

@Composable
private fun NeedMeter(label: String, value: Int?, color: Color, missing: String = "") {
    val shown = value?.toString() ?: missing
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $shown" },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = AzphaltType.eyebrow, color = azphaltOnSurface())
            Text(shown, style = AzphaltType.body, color = azphaltOnSurface())
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(NeedTrack),
        ) {
            if (value != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(value.coerceIn(0, 100) / 100f)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun NeedLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(value, style = AzphaltType.body, color = azphaltOnSurface())
    }
}
