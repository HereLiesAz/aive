package com.hereliesaz.geministrator.log

import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import com.hereliesaz.geministrator.workflow.SessionActivityLine
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityByRoleTest {
    private fun line(at: Long, scope: String, text: String, role: String?) = LogLine(at, scope, text, role)

    @Test
    fun allActivityInterleavesEventsAndAgentStepsByTimeAndKeepsEachRole() {
        val events = listOf(line(10, "Build", "Started", "engineer"), line(30, "Build", "Completed", "engineer"))
        val agents = listOf(line(10, "Build", "[step] edits a file", "engineer"), line(20, "Review", "[thinking] reads", "reviewer"))
        val all = allActivityLines(events, agents)
        assertEquals(listOf("Started", "[step] edits a file", "[thinking] reads", "Completed"), all.map { it.text })
        assertEquals(listOf("engineer", "reviewer"), rolesIn(all))
        assertEquals(listOf("[thinking] reads"), linesForRole(all, "reviewer").map { it.text })
        assertEquals(all, linesForRole(all, null))
        assertEquals(emptyList(), linesForRole(all, "nobody"))
    }

    @Test
    fun theBuildersTagLinesWithTheRoleOfTheirTaskOrSession() {
        val build = TaskDefinitionId("build")
        val events = listOf(
            WorkflowCreated(workflowRunId = WorkflowRunId("r"), objective = "ship", occurredAtEpochMillis = 1L),
            TaskStarted(workflowRunId = WorkflowRunId("r"), taskDefinitionId = build, attempt = 1, occurredAtEpochMillis = 2L),
            TaskCompleted(workflowRunId = WorkflowRunId("r"), taskDefinitionId = build, occurredAtEpochMillis = 3L),
        )
        val lines = runEventLines(events, taskName = { "Build" }, roleOf = { if (it == build) "engineer" else null })
        assertEquals(listOf(null, "engineer", "engineer"), lines.map { it.role })

        val key = SessionActivityKey(TaskRunId("tr"), ProviderRunId("pr"))
        val feeds = mapOf(key to listOf(SessionActivityLine(id = 1, atEpochMillis = 5L, kind = SessionActivityKind.Progress, text = "runs tests")))
        val agent = agentActivityLines(feeds, scopeOf = { "Build" }, roleOf = sessionRole(mapOf(TaskRunId("tr") to "engineer")))
        assertEquals(listOf("engineer"), agent.map { it.role })
        assertEquals("[step] runs tests", agent.single().text)
    }
}
