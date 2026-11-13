package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.delegation_fewer
import com.hereliesaz.geministrator.resources.delegation_gate_plan_approval
import com.hereliesaz.geministrator.resources.delegation_more
import com.hereliesaz.geministrator.resources.delegation_none
import com.hereliesaz.geministrator.resources.delegation_offer_accept
import com.hereliesaz.geministrator.resources.delegation_offer_body
import com.hereliesaz.geministrator.resources.delegation_offer_decline
import com.hereliesaz.geministrator.resources.delegation_offer_title
import com.hereliesaz.geministrator.resources.delegation_revoke
import com.hereliesaz.geministrator.resources.delegation_row_detail
import com.hereliesaz.geministrator.resources.delegation_threshold
import com.hereliesaz.geministrator.workflow.DelegableGateKind
import com.hereliesaz.geministrator.workflow.DelegationKey
import com.hereliesaz.geministrator.workflow.MAX_DELEGATION_THRESHOLD
import com.hereliesaz.geministrator.workflow.MIN_DELEGATION_THRESHOLD
import com.hereliesaz.geministrator.workflow.ProgressiveDelegationStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun delegableGateLabel(kind: DelegableGateKind): String = when (kind) {
    DelegableGateKind.PlanApproval -> stringResource(Res.string.delegation_gate_plan_approval)
}

private fun providerLabel(providerId: String): String = ProviderCatalog.entry(providerId)?.displayName ?: providerId

/**
 * The once-per-streak offer to stop asking (workflow/ProgressiveDelegation.kt). Keep asking declines
 * it; closing it any other way leaves it waiting for the next launch.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun DelegationOfferDialog(store: ProgressiveDelegationStore) {
    val state by store.state.collectAsState()
    var setAside by remember { mutableStateOf<DelegationKey?>(null) }
    val offer = state.pendingOfferRecord ?: return
    if (setAside == offer.key) return
    AzphaltAlertDialog(
        title = stringResource(Res.string.delegation_offer_title, offer.roleName, delegableGateLabel(offer.key.gateKind)),
        text = stringResource(Res.string.delegation_offer_body, offer.consecutiveApprovals, providerLabel(offer.key.providerId)),
        confirmLabel = stringResource(Res.string.delegation_offer_accept),
        onConfirm = { store.accept(offer.key, Clock.System.now().toEpochMilliseconds()) },
        onDismissRequest = { setAside = offer.key },
        dismissLabel = stringResource(Res.string.delegation_offer_decline),
        onDismiss = { store.decline(offer.key) },
    )
}

/** The Keeper's room's Delegations part: every delegation, each revocable, and when offers come. */
@Composable
internal fun DelegationsPart(store: ProgressiveDelegationStore, compact: Boolean) {
    val state by store.state.collectAsState()
    val delegations = state.delegations
    val openSource = LocalPlaceLinks.current.openSource
    if (delegations.isEmpty()) {
        Text(stringResource(Res.string.delegation_none, state.threshold), style = AzphaltType.body, color = azphaltOnSurface())
    }
    delegations.forEach { record ->
        ConnectionRow(
            name = record.roleName,
            detail = stringResource(
                Res.string.delegation_row_detail,
                delegableGateLabel(record.key.gateKind),
                providerLabel(record.key.providerId),
            ),
            status = null,
            connected = true,
            compact = compact,
            // Its source is named in the detail: the line opens that source's own screen.
            onDetailClick = openSource?.let { open -> { open(record.key.providerId) } },
        ) {
            AzphaltPill(
                stringResource(Res.string.delegation_revoke),
                "revoke-delegation-${record.key.roleId}-${record.key.gateKind.name}-${record.key.providerId}",
                onClick = { store.revoke(record.key) },
            )
        }
    }
    ProviderSectionLabel(stringResource(Res.string.delegation_threshold, state.threshold))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.threshold > MIN_DELEGATION_THRESHOLD) {
            AzphaltPill(
                stringResource(Res.string.delegation_fewer),
                "delegation-threshold-fewer",
                onClick = { store.setThreshold(state.threshold - 1) },
            )
        }
        if (state.threshold < MAX_DELEGATION_THRESHOLD) {
            AzphaltPill(
                stringResource(Res.string.delegation_more),
                "delegation-threshold-more",
                onClick = { store.setThreshold(state.threshold + 1) },
            )
        }
    }
}
