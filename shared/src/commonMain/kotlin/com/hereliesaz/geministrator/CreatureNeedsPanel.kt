package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
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
import com.hereliesaz.geministrator.resources.needs_echo_exposure
import com.hereliesaz.geministrator.resources.needs_integrity
import com.hereliesaz.geministrator.resources.needs_near_config
import com.hereliesaz.geministrator.resources.needs_needs
import com.hereliesaz.geministrator.resources.needs_not_reported
import com.hereliesaz.geministrator.resources.needs_nothing_downstream
import com.hereliesaz.geministrator.resources.needs_output_used
import com.hereliesaz.geministrator.resources.needs_near_config_review_paths
import com.hereliesaz.geministrator.resources.needs_reviews_of
import com.hereliesaz.geministrator.resources.needs_tokens_spent
import org.jetbrains.compose.resources.stringResource

private val NeedTrack = Color(0xFF4A4139)
// Exposure is a graph proxy; it must not render a diagnosis of sepsis.
private val EchoWarm = Azphalt.Yellow
private val IntegrityGreen = Azphalt.Yellow
private val OutputTeal = Azphalt.Yellow

/** The four needs (docs/Swarm-OS.md), shown on the inspector's Ink surface. */
@Composable
internal fun CreatureNeedsPanel(
    needs: CreatureNeeds,
    modifier: Modifier = Modifier,
    titled: Boolean = true,
    /** Opens the Mind tab, where the budget is raised: set, the Budget meter is a link to it. */
    onOpenMind: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // In the creature panel the Needs tab already names it.
        if (titled) Text(stringResource(Res.string.needs_needs), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        NeedMeter(
            label = stringResource(Res.string.needs_echo_exposure),
            value = needs.echo.takeIf { needs.echoComparablePaths > 0 },
            color = EchoWarm,
            missing = stringResource(Res.string.needs_not_reported),
        )
        NeedMeter(stringResource(Res.string.needs_integrity), needs.integrity, IntegrityGreen)
        NeedMeter(
            label = stringResource(Res.string.needs_budget),
            value = needs.budget,
            color = Azphalt.Yellow,
            missing = needs.tokensSpent?.let { stringResource(Res.string.needs_tokens_spent, it.toString()) }
                ?: stringResource(Res.string.needs_not_reported),
            onClick = onOpenMind,
        )
        NeedMeter(stringResource(Res.string.needs_output_used), needs.outputUsed, OutputTeal, stringResource(Res.string.needs_nothing_downstream))
        needs.nearClone?.let { NeedLine(stringResource(Res.string.needs_near_config), it) }
        if (needs.selfReviews > 0) {
            NeedLine(
                stringResource(Res.string.needs_near_config_review_paths),
                stringResource(Res.string.needs_reviews_of, needs.selfReviews, needs.reviews),
            )
        }
    }
}

@Composable
private fun NeedMeter(label: String, value: Int?, color: Color, missing: String = "", onClick: (() -> Unit)? = null) {
    val shown = value?.toString() ?: missing
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = "$label, $shown" }
            .then(
                if (onClick != null) {
                    Modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // A meter that opens where it is changed is underlined, like every other link.
            Text(
                label, style = AzphaltType.eyebrow, color = azphaltOnSurface(),
                textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            )
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
