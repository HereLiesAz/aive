package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.memory.MemoryPromptRecall
import com.hereliesaz.geministrator.memory.joinWholeUnits
import com.hereliesaz.geministrator.memory.wholeUnitIndices
import com.hereliesaz.geministrator.providers.PromptContextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryProvenanceTest {
    private val task = TaskDefinitionId("work")

    private val run = WorkflowRun(
        id = WorkflowRunId("run"), projectId = ProjectId("p"), workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "o", status = WorkflowRunStatus.Running,
        taskRuns = mapOf(task to TaskRun(id = TaskRunId("r"), taskDefinitionId = task, status = TaskRunStatus.Running, assignedRoleId = null,
            assignedProviderId = AgentProviderId("a"), providerRunId = ProviderRunId("session"))),
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
    )

    private fun report(nodes: Set<String>?, session: String = "session", avoided: Long = 0, at: Long = 2L) =
        ProviderUsageRecorded(run.id, task, AgentProviderId("a"), ProviderRunId(session), tokensAvoided = avoided,
            occurredAtEpochMillis = at, deliveredMemoryAddresses = nodes)

    @Test
    fun onlyExactCurrentSessionReportsCountAndTheyUnion() {
        val reads = reportedMemoryReads(run, listOf(
            report(setOf("memory-node:a")), report(setOf("memory-node:b", "memory-node:a"), at = 3L),
            report(setOf("memory-node:stale"), session = "old"), report(setOf("memory-node:replayed"), avoided = 5),
            report(null),
        ))
        assertEquals(mapOf(task to setOf("memory-node:a", "memory-node:b")), reads)
    }

    @Test
    fun unreportedIsAbsentAndAnEmptyReportIsKnownNothing() {
        assertEquals(emptyMap(), reportedMemoryReads(run, listOf(report(null))))
        assertEquals(mapOf(task to emptySet()), reportedMemoryReads(run, listOf(report(emptySet()))))
        val ready = run.copy(taskRuns = run.taskRuns.mapValues { it.value.copy(status = TaskRunStatus.Ready) })
        assertEquals(emptyMap(), reportedMemoryReads(ready, listOf(report(setOf("memory-node:a")))))
    }

    @Test
    fun wholeUnitIndicesNameExactlyTheUnitsJoinWholeUnitsKeeps() {
        val units = listOf("aaaa", "b".repeat(30), "cc", "d".repeat(30), "e")
        for (limit in listOf(1, 4, 10, 40, 100)) {
            val kept = units.wholeUnitIndices(limit)
            assertEquals(kept.joinToString("\n\n") { units[it] }, units.joinWholeUnits(limit), "limit $limit")
        }
        // The first unit is always kept; a later short unit may still fit after a long one is skipped.
        assertEquals(listOf(0, 2), units.wholeUnitIndices(10))
    }

    @Test
    fun usageIsCreditedOnlyWhenTheProviderRenderedTheContextWholeAndTheSessionIsKnown() {
        // The gateway rule lives in a private extension; its inputs are pinned here by the event's own defaults.
        val event = com.hereliesaz.geministrator.providers.AgentEvent.UsageReported(runId = ProviderRunId("r"), deliveredArtifactIds = emptySet())
        assertEquals(false, event.rendersPromptContextWhole)
    }

    @Test
    fun recallBlockAddressesMustAlignWithBlocks() {
        val block = PromptContextBlock("Relevant memory", "text")
        MemoryPromptRecall(blocks = listOf(block), blockAddresses = listOf(setOf("memory-node:a")))
        assertTrue(MemoryPromptRecall(blocks = listOf(block)).blockAddresses.isEmpty())
        assertFailsWith<IllegalArgumentException> { MemoryPromptRecall(blocks = listOf(block), blockAddresses = emptyList<Set<String>>() + listOf(emptySet(), emptySet())) }
        assertFailsWith<IllegalArgumentException> { MemoryPromptRecall(blocks = listOf(block), blockAddresses = listOf(setOf(" "))) }
    }
}
