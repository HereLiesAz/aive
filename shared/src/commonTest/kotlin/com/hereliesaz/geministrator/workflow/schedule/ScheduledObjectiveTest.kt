package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.domain.ContextProvenance
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledObjectiveTest {
    private fun schedule(withEvidence: Boolean) = WorkflowSchedule(
        id = "s", workflowId = "hive", name = "n", objective = "Do it", cadence = ScheduleCadence.daily(7),
        withEvidence = withEvidence, createdAtEpochMillis = 1L,
    )

    @Test
    fun aKeepersOwnScheduleObjectiveIsTheirsButOneFedModelWrittenEvidenceIsExternal() {
        assertEquals(ContextProvenance.KeeperWritten, schedule(withEvidence = false).objectiveProvenance())
        assertEquals(ContextProvenance.ExternalContent, schedule(withEvidence = true).objectiveProvenance())
    }
}
