package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RunSegmentTest {
    private fun seg(status: WorkflowRunStatus?, rehearsing: Boolean = false) = runSegmentLabel(rehearsing, status)

    @Test fun noRunShowsNoSegment() = assertEquals(RunSegment.None, seg(null))

    @Test fun aRehearsalWinsOverEverything() {
        assertEquals(RunSegment.Rehearsal, seg(null, rehearsing = true))
        assertEquals(RunSegment.Rehearsal, seg(WorkflowRunStatus.Completed, rehearsing = true))
    }

    @Test fun anyUnfinishedRunIsLiveIncludingOneWaitingOnTheKeeper() {
        assertEquals(RunSegment.Live, seg(WorkflowRunStatus.Created))
        assertEquals(RunSegment.Live, seg(WorkflowRunStatus.Running))
        assertEquals(RunSegment.Live, seg(WorkflowRunStatus.AwaitingHuman))
    }

    @Test fun aFinishedRunIsSuccessOrFailedAndStoppedIsFailed() {
        assertEquals(RunSegment.Success, seg(WorkflowRunStatus.Completed))
        assertEquals(RunSegment.Failed, seg(WorkflowRunStatus.Failed))
        assertEquals(RunSegment.Failed, seg(WorkflowRunStatus.Cancelled))
    }

    @Test fun theRestingBowlIsNotARun() {
        assertEquals(false, isRealRun("bowl--hive-1"))
        assertEquals(true, isRealRun("run-1"))
    }
}
