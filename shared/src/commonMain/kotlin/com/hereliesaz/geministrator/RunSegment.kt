package com.hereliesaz.geministrator

import androidx.compose.runtime.compositionLocalOf
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.run_segment_failed
import com.hereliesaz.geministrator.resources.run_segment_rehearsal
import com.hereliesaz.geministrator.resources.run_segment_success

/**
 * What the first segment of the Live / Edit switch says: there is no Live unless a run is live (owner request).
 * [None] draws no segment at all, so a hive that never ran shows only Edit.
 */
internal enum class RunSegment { None, Live, Rehearsal, Success, Failed }

/**
 * Pure: the segment for the run shown. [rehearsing] (a rehearsal, Rehearsal.kt) wins; no [status] is none; an
 * unfinished run, one waiting on the keeper or paused included, is Live; a completed run is Success; a failed,
 * escalated-and-ended, cancelled or user-stopped run is Failed (owner ruling: no separate Stopped).
 */
internal fun runSegmentLabel(rehearsing: Boolean, status: WorkflowRunStatus?): RunSegment = when {
    rehearsing -> RunSegment.Rehearsal
    status == null -> RunSegment.None
    status == WorkflowRunStatus.Completed -> RunSegment.Success
    status == WorkflowRunStatus.Failed || status == WorkflowRunStatus.Cancelled -> RunSegment.Failed
    else -> RunSegment.Live
}

/** A resting hive is shown on a placeholder run (`restingWorkflowRun`, id `bowl--…`), which is no run. */
internal fun isRealRun(runId: String): Boolean = !runId.startsWith("bowl--")

/** Read by every renderer of the view switch (HiveWorkspace, NewHive, TerrariumConnections); the terrarium provides it. */
internal val LocalRunSegment = compositionLocalOf { RunSegment.Live }

internal fun runSegmentString(segment: RunSegment): org.jetbrains.compose.resources.StringResource? = when (segment) {
    RunSegment.None -> null
    RunSegment.Live -> Res.string.build_live
    RunSegment.Rehearsal -> Res.string.run_segment_rehearsal
    RunSegment.Success -> Res.string.run_segment_success
    RunSegment.Failed -> Res.string.run_segment_failed
}

/** The segment's words, or null where there is no segment. */
@androidx.compose.runtime.Composable
internal fun runSegmentText(segment: RunSegment): String? =
    runSegmentString(segment)?.let { org.jetbrains.compose.resources.stringResource(it) }
