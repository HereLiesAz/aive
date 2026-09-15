package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveInboxScreen(
    runtimeState: ApplicationRuntimeState,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runtimeState is ApplicationRuntimeState.Live) {
        InboxScreen(
            runtimeState = runtimeState,
            onApproveTask = onApproveTask,
            onRejectPlan = onRejectPlan,
            onResolveEscalation = onResolveEscalation,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("INBOX", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            when (runtimeState) {
                ApplicationRuntimeState.Loading -> "Loading runtime…"
                ApplicationRuntimeState.NoProject -> "No project yet. There are no workflow decisions to review."
                is ApplicationRuntimeState.NoRun -> "No active run. There are no workflow decisions to review."
                is ApplicationRuntimeState.Disconnected -> "Runtime disconnected: ${runtimeState.message}"
                is ApplicationRuntimeState.ResumeFailed -> "Run unavailable: ${runtimeState.message}"
                is ApplicationRuntimeState.Live -> "No pending decisions."
            },
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
    }
}
