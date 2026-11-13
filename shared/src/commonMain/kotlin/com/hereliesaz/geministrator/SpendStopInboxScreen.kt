package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_inbox
import com.hereliesaz.geministrator.resources.spend_stop_eyebrow
import com.hereliesaz.geministrator.resources.spend_stop_how_to_lift
import com.hereliesaz.geministrator.resources.spend_stop_lift
import com.hereliesaz.geministrator.resources.spend_stop_change_caps
import com.hereliesaz.geministrator.resources.spend_stop_spent
import com.hereliesaz.geministrator.resources.spend_stop_stopped
import com.hereliesaz.geministrator.resources.spend_stop_title_hourly
import com.hereliesaz.geministrator.resources.spend_stop_title_run
import com.hereliesaz.geministrator.workflow.formatUsd
import org.jetbrains.compose.resources.stringResource

/**
 * The detail of Messages' stop row while a spend cap has stopped the hive (docs/Keepers-Room.md, This
 * device): which cap, what was spent, and the one act that resumes it. Like the Nanny's freeze, it
 * speaks for the whole hive, so it is the only row.
 */
@Composable
internal fun SpendStopDetail(
    presentation: LiveWorkflowPresentation,
    onLiftSpendStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the spend caps in the Keeper's room, so the cap that stopped the run can be raised. */
    onOpenLimits: (() -> Unit)? = null,
    /** A phone: the caps sit under This device, not Limits, and the page says which. */
    compact: Boolean = false,
) {
    val pause = presentation.run.spendPause ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AzphaltRecord(
            seed = "spend-stop",
            eyebrow = stringResource(Res.string.spend_stop_eyebrow),
            title = stringResource(
                when (pause.kind) {
                    SpendCapKind.RunCost -> Res.string.spend_stop_title_run
                    SpendCapKind.HourlySpend -> Res.string.spend_stop_title_hourly
                },
            ),
            body = stringResource(Res.string.spend_stop_spent, formatUsd(pause.spentUsd), formatUsd(pause.capUsd)),
            endCap = stringResource(Res.string.spend_stop_stopped),
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.spend_stop_how_to_lift, stringResource(spendLimitsSection(compact).title)), style = AzphaltType.body, color = azphaltOnSurface())
                    AzphaltPill(
                        label = stringResource(Res.string.spend_stop_lift),
                        seed = "spend-stop-lift",
                        onClick = onLiftSpendStop,
                        tone = PillTone.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    onOpenLimits?.let { open ->
                        AzphaltPill(
                            label = stringResource(Res.string.spend_stop_change_caps),
                            seed = "spend-stop-caps",
                            onClick = open,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )
    }
}
