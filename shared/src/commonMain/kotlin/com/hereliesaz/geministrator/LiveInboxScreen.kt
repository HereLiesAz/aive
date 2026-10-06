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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_inbox
import com.hereliesaz.geministrator.resources.inbox_loading_runtime
import com.hereliesaz.geministrator.resources.inbox_no_active_run_there_are_no
import com.hereliesaz.geministrator.resources.inbox_no_pending_decisions
import com.hereliesaz.geministrator.resources.inbox_no_project_yet_there_are_no
import com.hereliesaz.geministrator.resources.inbox_run_unavailable
import com.hereliesaz.geministrator.resources.inbox_runtime_disconnected
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveInboxScreen(
    runtimeState: ApplicationRuntimeState,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runtimeState is ApplicationRuntimeState.Live) {
        val presentation = runtimeState.presentation
        when {
            presentation.run.globalPause != null -> HallMonitorPauseInboxScreen(
                presentation = presentation,
                onTestSolution = { findingId, solutionIndex ->
                    onResolveEscalation(hallMonitorTrialActionId(findingId, solutionIndex), true)
                },
                modifier = modifier,
            )

            isHallMonitorTrial(presentation) -> HallMonitorTrialInboxScreen(
                presentation = presentation,
                onApproveTask = onApproveTask,
                onRejectPlan = onRejectPlan,
                onResolveEscalation = onResolveEscalation,
                modifier = modifier,
            )

            else -> InboxScreen(
                runtimeState = runtimeState,
                onApproveTask = onApproveTask,
                onRejectPlan = onRejectPlan,
                onResolveEscalation = onResolveEscalation,
                modifier = modifier,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.common_inbox), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            when (runtimeState) {
                ApplicationRuntimeState.Loading -> stringResource(Res.string.inbox_loading_runtime)
                is ApplicationRuntimeState.NoProject -> stringResource(Res.string.inbox_no_project_yet_there_are_no)
                is ApplicationRuntimeState.NoRun -> stringResource(Res.string.inbox_no_active_run_there_are_no)
                is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.inbox_runtime_disconnected, runtimeState.message)
                is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.inbox_run_unavailable, runtimeState.message)
                is ApplicationRuntimeState.Live -> stringResource(Res.string.inbox_no_pending_decisions)
            },
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
    }
}
