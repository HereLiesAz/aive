package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CHECKPOINT_COMMIT_METADATA_KEY
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckpointSummaryTest {
    @Test
    fun showsTheShortCommitAndItsBranch() {
        val summary = checkpointSummary(
            listOf(
                artifact("plan", emptyMap()),
                artifact(
                    "change",
                    mapOf(CHECKPOINT_COMMIT_METADATA_KEY to "0123456789abcdef0123", "branch" to "haive/run-r1"),
                ),
            ),
        )

        assertEquals("0123456789ab · haive/run-r1", summary)
    }

    @Test
    fun withoutABranchShowsTheCommitAlone() {
        assertEquals("abc", checkpointSummary(listOf(artifact("change", mapOf(CHECKPOINT_COMMIT_METADATA_KEY to "abc")))))
    }

    @Test
    fun aTaskThatMadeNoCheckpointShowsNone() {
        assertNull(checkpointSummary(emptyList()))
        assertNull(checkpointSummary(listOf(artifact("unchanged", mapOf("headCommit" to "abc", "branch" to "haive/x")))))
    }

    private fun artifact(id: String, metadata: Map<String, String>) = ArtifactRef(
        id = ArtifactId(id),
        kind = ArtifactKind.CodeChange,
        taskRunId = TaskRunId("task"),
        label = id,
        textContent = id,
        metadata = metadata,
        createdAtEpochMillis = 0L,
    )
}
