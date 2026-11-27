package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.ProviderUsageRecorded

/**
 * Memory-read provenance, from the existing journal. A memory node counts as carried by a task only when its text
 * was in a request the provider reported as successfully generated, and the context packer and the recall's own
 * trimming kept it (`MemoryPromptRecall.blockAddresses`). It is carriage, not proof the model used the memory, and
 * not the memory layer's own recall activity. Missing, legacy, replayed, stale and uninstrumented evidence is
 * unknown, and an unknown task is absent, never an empty set.
 *
 * Recalls an agent asked for mid-session, the cue cloud and habits carry text no node address identifies, so they
 * are not counted here.
 */
internal fun reportedMemoryReads(run: WorkflowRun, reports: List<ProviderUsageRecorded>): Map<TaskDefinitionId, Set<String>> =
    reports.filter { event ->
        val task = run.taskRuns[event.taskDefinitionId]
        event.workflowRunId == run.id && task != null && event.providerId == task.assignedProviderId &&
            event.providerRunId == task.providerRunId && task.status != TaskRunStatus.Ready &&
            event.tokensAvoided == 0L && event.deliveredMemoryAddresses != null
    }.groupBy { it.taskDefinitionId }.mapValues { (_, events) ->
        events.flatMap { it.deliveredMemoryAddresses.orEmpty() }.toSet()
    }
