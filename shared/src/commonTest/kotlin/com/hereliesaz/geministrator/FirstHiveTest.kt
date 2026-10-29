package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class FirstHiveTest {
    @Test
    fun aFirstHiveIsNamedFromTheFirstWordsItWasFed() {
        assertEquals("Ship the release notes", firstHiveName("  Ship the release notes for 2.4  "))
        assertEquals("First hive", firstHiveName("   "))
    }

    @Test
    fun theBadgeWalksThroughEveryWaitingCreatureNotJustTheFirst() {
        val tasks = listOf("a", "b", "c").map { TaskDefinition(TaskDefinitionId(it), it, it, null) }
        val runs = mapOf(
            "a" to TaskRunStatus.AwaitingApproval,
            "b" to TaskRunStatus.Running,
            "c" to TaskRunStatus.Escalated,
        ).map { (id, status) -> TaskDefinitionId(id) to TaskRun(TaskRunId("r-$id"), TaskDefinitionId(id), status, assignedRoleId = null) }.toMap()
        val live = LiveWorkflowPresentation(
            project = com.hereliesaz.geministrator.domain.Project(ProjectId("p"), "P", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
            definition = WorkflowDefinition(WorkflowDefinitionId("w"), "W", tasks = tasks),
            run = WorkflowRun(WorkflowRunId("r"), ProjectId("p"), WorkflowDefinitionId("w"), "o", WorkflowRunStatus.Running, runs, 1, 2),
            roles = emptyList(),
        )
        assertEquals("a", firstCreatureNeedingYou(live))
        val seenA = inboxItemKeys(live.run, "a").toSet()
        assertEquals("c", firstCreatureNeedingYou(live) { it !in seenA })
        // Once every wait has been seen, the badge still opens the first one waiting.
        val seenAll = inboxItemKeys(live.run).toSet()
        assertEquals("a", firstCreatureNeedingYou(live) { it !in seenAll })
    }
}
